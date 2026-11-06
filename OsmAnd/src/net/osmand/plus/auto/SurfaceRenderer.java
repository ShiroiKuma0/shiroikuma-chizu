package net.osmand.plus.auto;

import static net.osmand.plus.views.OsmandMapTileView.DEFAULT_ELEVATION_ANGLE;
import static net.osmand.plus.views.MapViewWithLayers.SYMBOLS_UPDATE_INTERVAL;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.AppManager;
import androidx.car.app.CarContext;
import androidx.car.app.HostException;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import net.osmand.Location;
import net.osmand.core.android.AtlasMapRendererView;
import net.osmand.core.android.MapRendererContext;
import net.osmand.core.android.MapRendererView;
import net.osmand.core.android.MapRendererView.MapRendererViewListener;
import net.osmand.core.jni.ZoomLevel;
import net.osmand.data.RotatedTileBox;
import net.osmand.plus.AppInitEvents;
import net.osmand.plus.AppInitializeListener;
import net.osmand.plus.AppInitializer;
import net.osmand.plus.OsmAndConstants;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.auto.views.CarSurfaceView;
import net.osmand.plus.helpers.MapDisplayPositionManager;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.views.OsmandMapTileView;
import net.osmand.plus.views.OsmandMapTileView.ElevationListener;
import net.osmand.plus.views.corenative.NativeCoreContext;
import net.osmand.plus.views.layers.base.OsmandMapLayer.DrawSettings;

/**
 * A very simple implementation of a renderer for the app's background surface.
 */
public final class SurfaceRenderer implements DefaultLifecycleObserver, MapRendererViewListener, ElevationListener {
	private static final String TAG = "SurfaceRenderer";

	public static final float MIN_ALLOWED_ELEVATION_ANGLE_AA = 20;

	private static final double VISIBLE_AREA_Y_MIN_DETECTION_SIZE = 1.025;
	private static final int MAP_RENDER_MESSAGE = OsmAndConstants.UI_HANDLER_MAP_VIEW + 7;
	private static final int MAX_FRAME_RATE = 10; // 白い熊 地図 +041: was 20 — half the frames, half the copying
	/** 白い熊 地図 +045: car frames stop now and then (seen after the app restarted under a live car session). */
	private static final long CHIZU_STALL_MS = 10_000;
	private volatile long chizuLastFrameMs;
	private long chizuLastDrawMs;
	private volatile long chizuLastGlFrameMs;
	private long chizuLastErrorMs;
	private static final int CHIZU_HELD_MESSAGE = OsmAndConstants.UI_HANDLER_MAP_VIEW + 18;
	private int chizuRecoveries;
	private final Runnable chizuWatchdog = this::chizuCheckFrames;
	private Lifecycle chizuLifecycle;

	/** 白い熊 地図 +043: the frame rate in use; the test receiver may change it. */
	private static volatile int chizuFrameRate = MAX_FRAME_RATE;
	private static final long KEEP_3D_DELAY_MS = 400;
	public static final int PINCH_TO_ZOOM_ITERATION_DELAY = 200;

	private final CarContext carContext;
	private final CarSurfaceView surfaceView;
	private OsmandMapTileView mapView;
	private final Handler handler;

	@Nullable
	private AtlasMapRendererView offscreenMapRendererView;
	@Nullable
	private Surface surface;
	@Nullable
	private SurfaceContainer surfaceContainer;

	@Nullable
	private Rect visibleArea;
	@Nullable
	private Rect stableArea;

	private float cachedRatioY = 0f;
	private float cachedRatioX = 0f;
	private float cachedDefaultRatioY = 0f;
	private Rect cachedVisibleArea;

	private boolean darkMode;

	private SurfaceRendererCallback callback;

	// shiroikuma fork: the car map stays 3D. Only the 2D/3D button may flatten it; any other
	// path back to 2D (a recalculation, a lost fix, a renderer re-setup reading the flat
	// per-profile angle) is tilted back. Reset on every new session, so it always starts in 3D.
	private boolean flatByUser;
	private boolean loggedFlat;
	private final Runnable keep3D = this::keep3D;

	private static final float surfaceWidthMultiply = 0.5f;
	private int surfaceAdditionalWidth = 0;
	// Ratios are calculated dynamically using surfaceWidthMultiply
	private float minRatio = 0.5f;
	private float maxRatio = 0.5f;

	public void setCallback(@Nullable SurfaceRendererCallback callback) {
		this.callback = callback;
	}

	public interface SurfaceRendererCallback {
		void onFrameRendered(@NonNull Canvas canvas, @NonNull Rect visibleArea, @NonNull Rect stableArea);
		void onElevationChanging(float angle);
	}

	private void setupSurfaceView(@NonNull SurfaceContainer surfaceContainer) {
		if (getApp().useOpenGlRenderer()) {
			surfaceAdditionalWidth = (int) ((float) surfaceContainer.getWidth() * surfaceWidthMultiply);
		}

		surfaceView.setSurfaceParams(surfaceContainer.getWidth() + surfaceAdditionalWidth,
				surfaceContainer.getHeight(), surfaceContainer.getDpi());

		minRatio = (1f - surfaceWidthMultiply) / 2.0f;
		maxRatio = 1f - (1f - surfaceWidthMultiply) / 2.0f;
	}

	private void changeVisibleArea(@NonNull Rect visibleArea) {
		cachedVisibleArea = visibleArea;
		Log.i(TAG, "Visible area changed " + surface + ". stableArea: "
				+ stableArea + " visibleArea:" + visibleArea);
		SurfaceRenderer.this.visibleArea = visibleArea;
		// 白い熊 地図 +062: the area beside the car's panel, as this car reports it in this session
		if (!visibleArea.isEmpty() && visibleArea.left >= getWidth() / 5) {
			chizuPanelArea = new Rect(visibleArea);
		}
		if (!visibleArea.isEmpty() && mapView != null && surfaceContainer != null) {
			MapDisplayPositionManager displayPositionManager = getDisplayPositionManager();

			int visibleAreaHeight = visibleArea.height();
			int containerWidth = surfaceContainer.getWidth();
			int containerHeight = surfaceContainer.getHeight();

			int centerX = visibleArea.centerX();
			cachedRatioX = (float) centerX / containerWidth;

			float cameraCenterShiftX = 0.5f;
			if (offscreenMapRendererView != null) {
				float dRatio = 0.5f + (1.0f - surfaceWidthMultiply) * (((1.0f - maxRatio) + minRatio) * 0.5f);

				if (cachedRatioX < minRatio) {
					cameraCenterShiftX = 0.5f - (minRatio - cachedRatioX) * dRatio;
					cachedRatioX = minRatio;
				} else if (cachedRatioX > maxRatio) {
					cameraCenterShiftX = 0.5f + (cachedRatioX - maxRatio) * dRatio;
					cachedRatioX = maxRatio;
				}
			} else {
				cameraCenterShiftX = cachedRatioX;
			}

			float ratioY = cachedRatioY;
			float defaultRatioY = displayPositionManager.getNavigationMapPosition().getRatioY();
			if (defaultRatioY != cachedDefaultRatioY || (float) containerHeight / visibleAreaHeight > VISIBLE_AREA_Y_MIN_DETECTION_SIZE) {
				float centerY = (visibleAreaHeight * defaultRatioY) + visibleArea.top;
				ratioY = centerY / containerHeight;
				cachedRatioY = ratioY;
				cachedDefaultRatioY = defaultRatioY;
			}
			displayPositionManager.setCustomMapRatio(cameraCenterShiftX, ratioY);
		}
		renderFrame();
	}

	public final SurfaceCallback mSurfaceCallback = new SurfaceCallback() {
		@Override
		public void onSurfaceAvailable(@NonNull SurfaceContainer surfaceContainer) {
			synchronized (SurfaceRenderer.this) {
				Log.i(TAG, "Surface available " + surfaceContainer);
				net.osmand.plus.chizu.ChizuDriveLog.log("car surface available " + surfaceContainer.getWidth() + "x" + surfaceContainer.getHeight()
						+ " mapView=" + (mapView != null)); // 白い熊 地図 +045
				if (surface != null) {
					surface.release();
				}

				SurfaceRenderer.this.surfaceContainer = surfaceContainer;
				surface = surfaceContainer.getSurface();
				setupSurfaceView(surfaceContainer);

				if (cachedVisibleArea != null) {
					changeVisibleArea(cachedVisibleArea);
				}

				darkMode = isNightMode();
				OsmandMapTileView mapView = SurfaceRenderer.this.mapView;
				if (mapView != null) {
					mapView.setupRenderingView();
				}
				renderFrame();
				chizuLastFrameMs = android.os.SystemClock.elapsedRealtime();
				getApp().getUiHandler().removeCallbacks(chizuWatchdog);
				getApp().runInUIThread(chizuWatchdog, CHIZU_STALL_MS);
				// 白い熊 地図 +045: a next-to-invisible overlay is said at every car-screen start
				getApp().runInUIThread(() -> net.osmand.plus.chizu.ChizuOverlayGuard.check("car screen started"), 3000);
			}
		}

		@Override
		public void onVisibleAreaChanged(@NonNull Rect visibleArea) {
			synchronized (SurfaceRenderer.this) {
				changeVisibleArea(visibleArea);
			}
		}

		@Override
		public void onStableAreaChanged(@NonNull Rect stableArea) {
			synchronized (SurfaceRenderer.this) {
				Log.i(TAG, "Stable area changed " + surface + ". stableArea: "
						+ stableArea + " visibleArea:" + visibleArea);
				SurfaceRenderer.this.stableArea = stableArea;
				renderFrame();
			}
		}

		@Override
		public void onSurfaceDestroyed(@NonNull SurfaceContainer surfaceContainer) {
			synchronized (SurfaceRenderer.this) {
				Log.i(TAG, "Surface destroyed");
				net.osmand.plus.chizu.ChizuDriveLog.log("car surface destroyed"); // 白い熊 地図 +045
				if (surface != null) {
					surface.release();
					surface = null;
				}
				OsmandMapTileView mapView = SurfaceRenderer.this.mapView;
				if (mapView != null) {
					getDisplayPositionManager().restoreMapRatio();
					mapView.setupRenderingView();
				}
			}
		}

		@Override
		public void onScroll(float distanceX, float distanceY) {
			synchronized (SurfaceRenderer.this) {
				OsmandMapTileView mapView = SurfaceRenderer.this.mapView;
				if (mapView != null) {
					mapView.scrollMap(distanceX, distanceY);
				}
			}
		}

		@Override
		public void onFling(float velocityX, float velocityY) {
			OsmandMapTileView mapView = SurfaceRenderer.this.mapView;
			if (mapView != null) {
				mapView.flingMap(0, 0, velocityX, velocityY);
			}
		}

		long lastScaleTime = 0;
		Boolean lastZoomDirection;

		@Override
		public void onScale(float focusX, float focusY, float scaleFactor) {
			boolean zoomDirection = scaleFactor > 1;
			if (System.currentTimeMillis() - lastScaleTime > PINCH_TO_ZOOM_ITERATION_DELAY || lastZoomDirection == null || lastZoomDirection != zoomDirection) {
				handleScale(focusX, focusY, scaleFactor);
				lastScaleTime = System.currentTimeMillis();
				lastZoomDirection = zoomDirection;
			}
		}
	};

	public SurfaceRenderer(@NonNull CarContext carContext, @NonNull Lifecycle lifecycle) {
		this.handler = new Handler();
		this.carContext = carContext;
		this.surfaceView = new CarSurfaceView(carContext, this);
		this.chizuLifecycle = lifecycle;
		lifecycle.addObserver(this);
	}

	private void sendRenderFrameMsg() {
		if (!handler.hasMessages(MAP_RENDER_MESSAGE)) {
			Message msg = Message.obtain(handler, () -> {
				handler.removeMessages(MAP_RENDER_MESSAGE);
				renderFrame();
			});
			msg.what = MAP_RENDER_MESSAGE;
			handler.sendMessage(msg);
		}
	}

	@Override
	public void onCreate(@NonNull LifecycleOwner owner) {
		Log.i(TAG, "SurfaceRenderer created");
		net.osmand.plus.chizu.ChizuDriveLog.log("car surface callback registered (renderer created)"); // 白い熊 地図 +045
		try {
			carContext.getCarService(AppManager.class).setSurfaceCallback(mSurfaceCallback);
		} catch (SecurityException | HostException e) {
			Log.e(TAG, "setSurfaceCallback failed ", e);
			net.osmand.plus.chizu.ChizuDriveLog.log("car surface callback failed: " + e);
		}
		chizuLastFrameMs = android.os.SystemClock.elapsedRealtime();
		getApp().getUiHandler().removeCallbacks(chizuWatchdog);
		getApp().runInUIThread(chizuWatchdog, CHIZU_STALL_MS);
	}

	/**
	 * Callback called when the car configuration changes.
	 */
	public void onCarConfigurationChanged() {
		boolean newDarkMode = isNightMode();
		if (darkMode != newDarkMode && mapView != null) {
			mapView.refreshMap(true);
		}
		renderFrame();
	}

	@Override
	public void onUpdateFrame(MapRendererView mapRendererView) {
	}

	/**
	 * Callback called when OpenGL rendering result is ready and needs to be drawn on output canvas.
	 */
	@Override
	public void onFrameReady(MapRendererView mapRendererView) {
		//renderFrame();
		net.osmand.plus.chizu.ChizuPerfLog.carAsk("gl frame ready"); // 白い熊 地図 +046
		sendRenderFrameMsg();
		// 白い熊 地図 +047: setMaximumFrameRate holds only the phone's on-screen renderer; the car's off-screen
		// one drew ~38 frames a second while the map moved (8–11 even at a limit of 2) — 70 % of a core for
		// frames the car never showed. This runs on its GL thread, so waiting here holds it to the rate.
		if (mapRendererView == offscreenMapRendererView) {
			long now = android.os.SystemClock.uptimeMillis();
			long wait = chizuLastGlFrameMs + 1000 / Math.max(1, chizuFrameRate) - now;
			if (wait > 0 && wait <= 1000) {
				try {
					Thread.sleep(wait);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			chizuLastGlFrameMs = android.os.SystemClock.uptimeMillis();
		}
	}

	/**
	 * Handles the map zoom-in and zoom-out events.
	 */
	public void handleScale(float focusX, float focusY, float scaleFactor) {
		synchronized (this) {
			float x = focusX;
			float y = focusY;
			Rect visibleArea = this.visibleArea;
			if (visibleArea != null) {
				// If a focal point value is negative, use the center point of the visible area.
				if (x < 0) {
					x = visibleArea.centerX();
				}
				if (y < 0) {
					y = visibleArea.centerY();
				}
			}
			OsmandMapTileView mapView = this.mapView;
			if (mapView != null) {
				if (scaleFactor > 1) {
					mapView.zoomInAndAdjustTiltAngle();
				} else if (scaleFactor < 1) {
					mapView.zoomOutAndAdjustTiltAngle();
				}
			}
		}
	}

	/**
	 * 白い熊 地図 +045: every {@value #CHIZU_STALL_MS} ms while the car surface lives — when no frame has
	 * reached it for that long, write down why and attach the map to the car screen again.
	 */
	private void chizuCheckFrames() {
		if (chizuLifecycle == null || !chizuLifecycle.getCurrentState().isAtLeast(Lifecycle.State.CREATED)) {
			return; // the car session is over
		}
		Surface surface = this.surface;
		long idle = android.os.SystemClock.elapsedRealtime() - chizuLastFrameMs;
		if (surface == null || !surface.isValid()) {
			// a session with no surface draws nothing: seen after the app restarted under a live car
			// session — ask the host again for the surface
			if (idle >= CHIZU_STALL_MS && chizuRecoveries < 6) {
				chizuRecoveries++;
				net.osmand.plus.chizu.ChizuDriveLog.log("car session has no surface for " + idle / 1000
						+ " s — asking the car for it again (#" + chizuRecoveries + ")");
				try {
					carContext.getCarService(AppManager.class).setSurfaceCallback(mSurfaceCallback);
				} catch (RuntimeException e) {
					net.osmand.plus.chizu.ChizuDriveLog.log("car surface callback failed: " + e);
				}
			}
			getApp().runInUIThread(chizuWatchdog, CHIZU_STALL_MS);
			return;
		}
		if (idle >= CHIZU_STALL_MS) {
			OsmandMapTileView mv = mapView;
			android.view.View v = mv == null ? null : mv.getView();
			String state = "mapView=" + (mv == null ? "none" : "set")
					+ " view=" + (v == null ? "none" : v.getClass().getSimpleName())
					+ " renderer=" + (mv == null || mv.getMapRenderer() == null ? "none"
					: mv.getMapRenderer() == offscreenMapRendererView ? "car" : "other")
					+ " offscreen=" + (offscreenMapRendererView != null);
			chizuRecoveries++;
			net.osmand.plus.chizu.ChizuDriveLog.log("car frames stalled " + idle / 1000 + " s: " + state
					+ " — attaching the map to the car screen again (#" + chizuRecoveries + ")");
			synchronized (this) {
				if (mv != null) {
					mv.setView(surfaceView);
					if (mv.getMapRenderer() != offscreenMapRendererView) {
						setupOffscreenRenderer();
					}
					mv.refreshMap();
				}
			}
			renderFrame();
		}
		getApp().runInUIThread(chizuWatchdog, CHIZU_STALL_MS);
	}

	/**
	 * 白い熊 地図 +047: after 地図 restarts under a running car session, the surface Android Auto hands over
	 * refuses every draw (lockCanvas: IllegalArgumentException) and the car screen freezes on its last
	 * picture. Switching to another app on the car screen and back gives a fresh, working surface; the app
	 * can't ask for one itself, so after {@value #CHIZU_LOCK_FAIL_MS} ms of refused draws it closes its car
	 * session — the car's launcher shows, and the next tap on 地図 starts with a fresh surface.
	 */
	private static final long CHIZU_LOCK_FAIL_MS = 4000;
	private long chizuLockFailSince;
	private boolean chizuLockFailHandled;

	private void chizuOnLockFailed(@NonNull Exception e, long now) {
		if (!(e instanceof IllegalArgumentException) || chizuLockFailHandled) {
			return;
		}
		if (chizuLockFailSince == 0) {
			chizuLockFailSince = now;
		}
		// only when the draws have failed for a while and no frame got through meanwhile
		if (now - chizuLockFailSince < CHIZU_LOCK_FAIL_MS || now - chizuLastFrameMs < CHIZU_LOCK_FAIL_MS) {
			return;
		}
		chizuLockFailHandled = true;
		net.osmand.plus.chizu.ChizuDriveLog.log("car surface refuses every draw for " + (now - chizuLockFailSince) / 1000
				+ " s — closing the car session so the next start gets a fresh surface");
		getApp().runInUIThread(() -> {
			try {
				getApp().getToastHelper().showCarToast(getApp().getString(net.osmand.plus.R.string.chizu_car_surface_lost), true);
			} catch (RuntimeException ignored) {
			}
			getApp().runInUIThread(() -> {
				try {
					carContext.finishCarApp();
				} catch (RuntimeException ex) {
					net.osmand.plus.chizu.ChizuDriveLog.log("car session close failed: " + ex);
				}
			}, 2500);
		});
	}

	/** 白い熊 地図 +043 test receiver: the car map's frame rate, now and for the next session. */
	public void chizuSetFrameRate(int fps) {
		chizuFrameRate = fps;
		synchronized (this) {
			if (offscreenMapRendererView != null) {
				offscreenMapRendererView.setMaximumFrameRate(fps);
			}
		}
	}

	public static void chizuSetDefaultFrameRate(int fps) {
		chizuFrameRate = fps;
	}

	/** 白い熊 地図 +043 test receiver: flat or 3D, as if the 2D/3D button were pressed. */
	public boolean chizuSetFlat(boolean flat) {
		OsmandMapTileView mv = mapView;
		if (mv == null) {
			return false;
		}
		boolean isFlat = mv.getElevationAngle() >= DEFAULT_ELEVATION_ANGLE;
		if (isFlat != flat) {
			handleTilt();
		}
		return true;
	}

	/**
	 * Handles the map 2D/3D button press events.
	 */
	public void handleTilt() {
		synchronized (this) {
			if (mapView != null && mapView.getAnimatedDraggingThread() != null && offscreenMapRendererView != null) {
				int adjustedTiltAngle = mapView.getAdjustedTiltAngle(mapView.getZoom(), true);
				float newAngle = mapView.getElevationAngle() < DEFAULT_ELEVATION_ANGLE ? DEFAULT_ELEVATION_ANGLE : adjustedTiltAngle;
				flatByUser = newAngle >= DEFAULT_ELEVATION_ANGLE;
				getApp().getSettings().setLastKnownMapElevation(newAngle);
				mapView.getAnimatedDraggingThread().startTilting(newAngle, 0.0f);
			}
		}
	}

	/**
	 * Handles the map re-centering events.
	 */
	public void handleRecenter() {
		OsmandMapTileView mapView = this.mapView;
		if (mapView != null) {
			mapView.backToLocation();
		}
	}

	/**
	 * Updates the location coordinate string drawn on the surface.
	 */
	public void updateLocation(@Nullable Location location) {
		//renderFrame();
	}

	@NonNull
	private MapDisplayPositionManager getDisplayPositionManager() {
		return getApp().getMapViewTrackingUtilities().getMapDisplayPositionManager();
	}

	@NonNull
	private OsmandApplication getApp() {
		return (OsmandApplication) carContext.getApplicationContext();
	}

	public OsmandMapTileView getMapView() {
		return mapView;
	}

	public void setMapView(OsmandMapTileView mapView) {
		net.osmand.plus.chizu.ChizuDriveLog.log("car map view " + (mapView == null ? "detached" : "attached") + " surface=" + (surface != null)
				+ " by " + net.osmand.plus.chizu.ChizuDriveLog.caller()); // 白い熊 地図 +045
		if (mapView == null) {
			stopOffscreenRenderer();
			return;
		}
		this.mapView = mapView;
		if (surface != null) {
			mapView.setView(surfaceView);
		}
		// The renderer needs only the native core, like the phone map (OsmandMapTileView.setupRenderingView):
		// waiting for the whole app init would show the non-OpenGL map with 2D icons for several seconds
		if (getApp().isApplicationInitializing() && !NativeCoreContext.isInit()) {
			getApp().getAppInitializer().addListener(new AppInitializeListener() {
				@Override
				public void onProgress(@NonNull AppInitializer init, @NonNull AppInitEvents event) {
					if (event == AppInitEvents.NATIVE_OPEN_GL_INITIALIZED) {
						init.removeListener(this);
						setupOffscreenRenderer();
					}
				}

				@Override
				public void onFinish(@NonNull AppInitializer init) {
					init.removeListener(this);
					setupOffscreenRenderer();
				}
			});
		} else
			setupOffscreenRenderer();
	}

	public synchronized void setupOffscreenRenderer() {
		Log.i(TAG, "setupOffscreenRenderer");
		if (getApp().useOpenGlRenderer()) {
			if (surface != null && surface.isValid()) {
				if (offscreenMapRendererView != null) {
					MapRendererContext mapRendererContext = NativeCoreContext.getMapRendererContext();
					if (mapRendererContext != null && mapRendererContext.getMapRendererView() != offscreenMapRendererView) {
						// The context doesn't refer to the view anymore: either the phone took its
						// renderer over, or recreateAndroidAutoRenderer() dropped it. In the latter
						// case the renderer is still alive and nothing else is going to stop it
						if (mapView != null && mapView.getMapRenderer() == offscreenMapRendererView) {
							mapView.detachMapRenderer();
						}
						offscreenMapRendererView.stopRenderer();
						offscreenMapRendererView = null;
					}
				}
				if (offscreenMapRendererView == null) {
					MapRendererContext mapRendererContext = NativeCoreContext.getMapRendererContext();
					if (mapRendererContext != null) {
						MapRendererView mapRendererView = null;
						if (mapView != null && mapView.getMapRenderer() != null) {
							mapView.detachMapRenderer();
						}
						if (mapRendererContext.getMapRendererView() != null) {
							mapRendererView = mapRendererContext.getMapRendererView();
							mapRendererContext.setMapRendererView(null);
						}
						NativeCoreContext.setMapRendererContext(getApp(), surfaceView.getDensity());
						mapRendererContext = NativeCoreContext.getMapRendererContext();
						if (mapRendererContext != null) {
							if (surfaceContainer != null) {
								setupSurfaceView(surfaceContainer);
							}

							offscreenMapRendererView = new AtlasMapRendererView(carContext);

							boolean enableMSAA = getApp().getSettings().ENABLE_MSAA.get();

							mapRendererContext.presetMapRendererOptions(offscreenMapRendererView, enableMSAA);
							offscreenMapRendererView.setupRenderer(carContext, getWidth(), getHeight(), mapRendererView);
							offscreenMapRendererView.setMinZoomLevel(ZoomLevel.swigToEnum(mapView.getMinZoom()));
							offscreenMapRendererView.setMaxZoomLevel(ZoomLevel.swigToEnum(mapView.getMaxZoom()));
							offscreenMapRendererView.setAzimuth(0);
							offscreenMapRendererView.setFlatEarth(!getApp().getSettings().SPHERICAL_MAP.get());
							offscreenMapRendererView.removeAllSymbolsProviders();
							offscreenMapRendererView.resumeSymbolsUpdate();
							offscreenMapRendererView.setSymbolsUpdateInterval(SYMBOLS_UPDATE_INTERVAL);
							offscreenMapRendererView.setSymbolsFade(getApp().getSettings().BATTERY_SAVING_MODE.get() ? 0 : 300, -1);
							offscreenMapRendererView.setMaximumFrameRate(chizuFrameRate);
							mapRendererContext.setMapRendererView(offscreenMapRendererView);
							mapView.setMinAllowedElevationAngle(MIN_ALLOWED_ELEVATION_ANGLE_AA);
							float elevationAngle = mapView.normalizeElevationAngle(getApp().getSettings().getLastKnownMapElevation());
							mapView.setMapRenderer(offscreenMapRendererView, false);
							mapView.setElevationAngle(elevationAngle);
							mapView.addElevationListener(this);
							onElevationChanging(mapView.getElevationAngle()); // shiroikuma fork: start in 3D
							getApp().getOsmandMap().getMapLayers().updateMapSource(mapView, null);
							PluginsHelper.refreshLayers(getApp(), null);
							offscreenMapRendererView.addListener(this);
							mapView.getAnimatedDraggingThread().toggleAnimations();

							if (cachedVisibleArea != null) {
								changeVisibleArea(cachedVisibleArea);
							}
						}
					}
				}
			}
		}
	}

	public synchronized void stopOffscreenRenderer() {
		Log.i(TAG, "stopOffscreenRenderer");
		if (offscreenMapRendererView != null) {
			if (mapView != null) {
				mapView.removeElevationListener(this);
				mapView.getAnimatedDraggingThread().toggleAnimations();
				if (mapView.getMapRenderer() == offscreenMapRendererView) {
					mapView.detachMapRenderer();
				}
			}
			MapRendererContext mapRendererContext = NativeCoreContext.getMapRendererContext();
			if (mapRendererContext != null) {
				mapRendererContext.suspendMapRendererView(offscreenMapRendererView);
			}
			offscreenMapRendererView = null;
		}
	}

	public int getWidth() {
		return surfaceView.getWidth();
	}

	public int getHeight() {
		return surfaceView.getHeight();
	}

	public int getDpi() {
		return surfaceView.getDpi();
	}

	public float getDensity() {
		return surfaceView.getDensity();
	}

	public boolean hasSurface() {
		return surface != null && surface.isValid();
	}

	public boolean hasOffscreenRenderer() {
		return offscreenMapRendererView != null;
	}

	public void renderFrame() {
		if (mapView == null || surface == null || !surface.isValid()) {
			// Surface is not available, or has been destroyed, skip this frame.
			return;
		}
		DrawSettings drawSettings = new DrawSettings(isNightMode(), false);
		RotatedTileBox tileBox = mapView.getRotatedTileBox();
		try {
			renderFrame(tileBox, drawSettings);
		} catch (Exception e) {
			// 白い熊 地図 +046: was swallowed — and a car screen that never draws looked just like this
			long now = android.os.SystemClock.elapsedRealtime();
			chizuOnLockFailed(e, now);
			if (now - chizuLastErrorMs > 10_000) {
				chizuLastErrorMs = now;
				net.osmand.plus.chizu.ChizuDriveLog.log("car frame failed: " + e + " at "
						+ (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "?"));
			}
		}
	}

	public void renderFrame(RotatedTileBox tileBox, DrawSettings drawSettings) {
		if (mapView == null || surface == null || !surface.isValid()) {
			// Surface is not available, or has been destroyed, skip this frame.
			return;
		}
		// 白い熊 地図 +046: the copy to the car screen is held to the frame rate too. The map engine was
		// capped, but every map refresh copied a frame of its own — 15–20 a second while the phone's map
		// was in the background, three times the CPU. A frame asked for too soon is drawn once, later.
		long chizuNow = android.os.SystemClock.elapsedRealtime();
		long chizuWait = chizuLastDrawMs + 1000 / Math.max(1, chizuFrameRate) - chizuNow;
		if (chizuWait > 0 && !drawSettings.isUpdateVectorRendering()) {
			net.osmand.plus.chizu.ChizuPerfLog.carAsk("held back");
			if (!handler.hasMessages(CHIZU_HELD_MESSAGE)) {
				Message held = Message.obtain(handler, this::renderFrame);
				held.what = CHIZU_HELD_MESSAGE;
				handler.sendMessageDelayed(held, chizuWait);
			}
			return;
		}
		handler.removeMessages(CHIZU_HELD_MESSAGE);
		chizuLastDrawMs = chizuNow;
		long chizuT0 = android.os.SystemClock.elapsedRealtime();
		Canvas canvas = surface.lockCanvas(null);
		try {
			canvas.drawColor(Color.LTGRAY);
			boolean newDarkMode = isNightMode();
			boolean updateVectorRendering = drawSettings.isUpdateVectorRendering() || darkMode != newDarkMode;
			darkMode = newDarkMode;
			drawSettings = new DrawSettings(newDarkMode, updateVectorRendering);
			if (offscreenMapRendererView != null) {
				float leftOffset = 0.0f;
				if (surfaceAdditionalWidth != 0) {
					leftOffset = -surfaceAdditionalWidth * ((maxRatio - cachedRatioX) / (maxRatio - minRatio));
				}
				canvas.drawBitmap(offscreenMapRendererView.getBitmap(), leftOffset, 0, null);
			}
			mapView.drawOverMap(canvas, tileBox, drawSettings);
			SurfaceRendererCallback callback = this.callback;
			if (callback != null) {
				Rect visibleArea = this.visibleArea;
				Rect stableArea = this.stableArea;
				if (visibleArea != null && stableArea != null) {
					callback.onFrameRendered(canvas, visibleArea, stableArea);
				}
			}
		} finally {
			surface.unlockCanvasAndPost(canvas);
			net.osmand.plus.chizu.ChizuPerfLog.carFrame(android.os.SystemClock.elapsedRealtime() - chizuT0); // 白い熊 地図 +041
			chizuLastFrameMs = android.os.SystemClock.elapsedRealtime();
			chizuLockFailSince = 0;
		}
	}


	/**
	 * 白い熊 地図 +063: the map is drawn from an off-screen picture wider than the car screen, shifted left by
	 * this much; a pixel of the map engine is at screen x = its x + this.
	 */
	public float chizuLeftOffset() {
		return surfaceAdditionalWidth == 0 || maxRatio == minRatio ? 0f
				: -surfaceAdditionalWidth * ((maxRatio - cachedRatioX) / (maxRatio - minRatio));
	}

	/** 白い熊 地図 +062: the free area beside the panel, reported by this car in this session; null until seen. */
	@Nullable
	public volatile Rect chizuPanelArea;

	@Nullable
	public Rect getVisibleArea() {
		return visibleArea;
	}

	public double getVisibleAreaWidth() {
		return visibleArea != null ? visibleArea.width() : 0f;
	}

	public int getSurfaceAdditionalWidth() {
		return surfaceAdditionalWidth;
	}

	private boolean isNightMode() {
		OsmandApplication app = (OsmandApplication) carContext.getApplicationContext();
		return app.getDaynightHelper().isNightModeForCar(carContext);
	}

	public float getCachedRatioX() {
		return cachedRatioX;
	}

	public float getCachedRatioY() {
		return cachedRatioY;
	}

	@Override
	public void onElevationChanging(float angle) {
		SurfaceRendererCallback callback = this.callback;
		if (callback != null) {
			callback.onElevationChanging(angle);
		}
		boolean flat = angle >= DEFAULT_ELEVATION_ANGLE;
		if (flat != loggedFlat) {
			loggedFlat = flat;
			net.osmand.plus.chizu.ChizuDriveLog.log("car map " + (flat ? "flat" : "3D") + " angle=" + angle + " byUser=" + flatByUser
					+ " compass=" + getApp().getSettings().getCompassMode()); // 白い熊 地図 drive log
		}
		if (angle >= DEFAULT_ELEVATION_ANGLE && !flatByUser && !chizuPreviewFlat) {
			getApp().runInUIThread(keep3D, KEEP_3D_DELAY_MS);
		}
	}

	/**
	 * 白い熊 地図 +052: a route preview is shown flat and north-up, fitted to the free part of the screen; the
	 * keep-3D rule tilted it back and the route came out small, low and cut on the wide car screen. When the
	 * preview ends the map goes 3D again.
	 */
	private volatile boolean chizuPreviewFlat;

	public void chizuSetPreviewFlat(boolean flat) {
		if (chizuPreviewFlat == flat) {
			return;
		}
		chizuPreviewFlat = flat;
		net.osmand.plus.chizu.ChizuDriveLog.log("car route preview " + (flat ? "flat" : "ends, back to 3D"));
		if (!flat) {
			getApp().runInUIThread(keep3D, KEEP_3D_DELAY_MS);
		}
	}

	private void keep3D() {
		synchronized (this) {
			OsmandMapTileView mapView = this.mapView;
			if (flatByUser || chizuPreviewFlat || mapView == null || offscreenMapRendererView == null
					|| mapView.getMapRenderer() != offscreenMapRendererView
					|| mapView.getElevationAngle() < DEFAULT_ELEVATION_ANGLE
					|| mapView.getAnimatedDraggingThread() == null) {
				return;
			}
			float angle = mapView.getAdjustedTiltAngle(mapView.getZoom(), true);
			net.osmand.plus.chizu.ChizuDriveLog.log("car map tilted back to " + angle); // 白い熊 地図 drive log
			getApp().getSettings().setLastKnownMapElevation(angle);
			mapView.getAnimatedDraggingThread().startTilting(angle, 0.0f);
		}
	}

	@Override
	public void onStopChangingElevation(float angle) {
	}
}
