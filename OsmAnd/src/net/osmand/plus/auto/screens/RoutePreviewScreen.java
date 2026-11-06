package net.osmand.plus.auto.screens;

import static net.osmand.plus.base.ContextMenuFragment.MenuState.HEADER_ONLY;
import static net.osmand.search.core.ObjectType.GPX_TRACK;
import static net.osmand.search.core.ObjectType.ROUTE;

import android.os.CountDownTimer;
import android.text.SpannableString;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.CarContext;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.Distance;
import androidx.car.app.model.DistanceSpan;
import androidx.car.app.model.DurationSpan;
import androidx.car.app.model.Header;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Pane;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.MapWithContentTemplate;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import net.osmand.PlatformUtil;
import net.osmand.StateChangedListener;
import net.osmand.data.QuadRect;
import net.osmand.data.ValueHolder;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.auto.NavigationSession;
import net.osmand.plus.auto.TripUtils;
import net.osmand.plus.chizu.ChizuCar;
import net.osmand.plus.helpers.TargetPoint;
import net.osmand.plus.helpers.TargetPointsHelper;
import net.osmand.plus.routing.IRouteInformationListener;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.routing.RoutingHelperUtils;
import net.osmand.plus.search.listitems.QuickSearchListItem;
import net.osmand.plus.shared.SharedUtil;
import net.osmand.plus.track.data.GPXInfo;
import net.osmand.plus.track.helpers.GpxFileLoaderTask;
import net.osmand.plus.track.helpers.SelectedGpxFile;
import net.osmand.plus.views.MapActions;
import net.osmand.search.core.SearchResult;
import net.osmand.shared.gpx.GpxFile;
import net.osmand.util.Algorithms;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The route preview screen for the app.
 */
public final class RoutePreviewScreen extends BaseAndroidAutoScreen implements IRouteInformationListener,
		DefaultLifecycleObserver {

	public static final long CARD_COUNTDOWN_MS = 10_000;
	private static final long COUNTDOWN_INTERVAL_MS = 1000;

	private static final org.apache.commons.logging.Log LOG = PlatformUtil.getLog(RoutePreviewScreen.class);

	@NonNull
	private final Action settingsAction;
	@NonNull
	private final SearchResult searchResult;
	@NonNull
	private List<Row> routeRows = new ArrayList<>();

	@Nullable
	private GpxFile routeGpxFile;


	private boolean calculateRoute;
	private boolean calculating;
	private final long countdownMs;
	@Nullable
	private CountDownTimer countdownTimer;
	private int countdownSeconds;
	private boolean countdownDone;

	private final StateChangedListener<Void> stateChangedListener = new StateChangedListener<>() {
		@Override
		public void stateChanged(Void change) {
			if (routeGpxFile != null) {
				QuadRect mapRect = new QuadRect();
				Algorithms.extendRectToContainRect(mapRect, SharedUtil.jQuadRect(routeGpxFile.getRect()));
				adjustMapToRect(getApp().getMapViewTrackingUtilities().getDefaultLocation(), mapRect);
			}
		}
	};

	public RoutePreviewScreen(@NonNull CarContext carContext, @NonNull Action settingsAction,
	                          @NonNull SearchResult searchResult, boolean calculateRoute) {
		this(carContext, settingsAction, searchResult, calculateRoute, 0);
	}

	public RoutePreviewScreen(@NonNull CarContext carContext, @NonNull Action settingsAction,
	                          @NonNull SearchResult searchResult, boolean calculateRoute,
	                          long countdownMs) {
		super(carContext);
		this.settingsAction = settingsAction;
		this.searchResult = searchResult;
		this.calculateRoute = calculateRoute;
		this.countdownMs = countdownMs;
		getLifecycle().addObserver(this);
		calculating = calculateRoute;
		setMarker(RoutePreviewScreen.class.getSimpleName());
	}

	@Override
	protected boolean shouldRestoreMapState() {
		return true;
	}

	private void prepareRoute() {
		if (searchResult.objectType == GPX_TRACK) {
			GPXInfo gpxInfo = ((GPXInfo) searchResult.relatedObject);
			File file = gpxInfo.getFile();
			if (file == null) {
				return;
			}
			SelectedGpxFile selectedGpxFile = getApp().getSelectedGpxHelper().getSelectedFileByPath(file.getAbsolutePath());
			if (selectedGpxFile == null) {
				GpxFileLoaderTask.loadGpxFile(file, null, gpxFile -> {
					buildRouteByGivenGpx(gpxFile);
					return true;
				});
			} else {
				buildRouteByGivenGpx(selectedGpxFile.getGpxFile());
			}
		} else if (searchResult.objectType == ROUTE) {
			restorePreviousRoute();
		} else {
			getApp().getOsmandMap().getMapActions().replaceDestination(
					searchResult.location, QuickSearchListItem.getPointDescriptionObject(getApp(), searchResult).first);
			invalidate();
		}
	}

	private void restorePreviousRoute() {
		OsmandApplication app = getApp();
		RoutingHelper routingHelper = app.getRoutingHelper();
		TargetPointsHelper targetPointsHelper = app.getTargetPointsHelper();
		if (routingHelper.isFollowingMode() || routingHelper.isRoutePlanningMode()) {
			targetPointsHelper.restoreTargetPoints(true);
		} else {
			targetPointsHelper.restoreTargetPoints(false);
			TargetPoint start = targetPointsHelper.getPointToStart();
			MapActions mapActions = app.getOsmandMap().getMapActions();
			if (start != null) {
				mapActions.enterRoutePlanningModeGivenGpx(null, start.getLatLon(), start.getOriginalPointDescription(), true, true, HEADER_ONLY);
			} else {
				mapActions.enterRoutePlanningModeGivenGpx(null, null, null, true, true, HEADER_ONLY);
			}
		}
		invalidate();
	}

	private void buildRouteByGivenGpx(@NonNull GpxFile gpxFile) {
		routeGpxFile = gpxFile;
		getApp().getOsmandMap().getMapActions().buildRouteByGivenGpx(gpxFile);
		invalidate();
	}

	private void updateRoute(boolean newRoute) {
		OsmandApplication app = getApp();
		RoutingHelper routingHelper = app.getRoutingHelper();
		TargetPointsHelper targetPointsHelper = app.getTargetPointsHelper();

		Distance distance = null;
		int leftTimeSec = 0;
		if (newRoute && routingHelper.isRoutePlanningMode()) {
			distance = TripUtils.getDistance(app, routingHelper.getLeftDistance());
			leftTimeSec = routingHelper.getLeftTime();
		}
		if (distance != null && leftTimeSec > 0) {
			List<Row> routeRows = new ArrayList<>();
			SpannableString description = new SpannableString("  •  ");
			description.setSpan(DistanceSpan.create(distance), 0, 1, 0);
			description.setSpan(DurationSpan.create(leftTimeSec), 4, 5, 0);

			TargetPoint finish = targetPointsHelper.getPointToNavigate();
			String title = finish != null ? finish.getRoutePointDescription(app, true) : null;

			if (Algorithms.isEmpty(title)) {
				String name = QuickSearchListItem.getName(app, searchResult);
				String typeName = QuickSearchListItem.getTypeName(app, searchResult);
				title = Algorithms.isEmpty(name) ? typeName : name;
			}
			// shiroikuma fork: yellow distance-and-time line
			routeRows.add(new Row.Builder().setTitle(title)
					.addText(ChizuCar.colored(getCarContext(), description)).build());
			this.routeRows = routeRows;
			calculating = app.getRoutingHelper().isRouteBeingCalculated();
			invalidate();
			startCountdownIfReady();
		} else if (routingHelper.getRoute().hasMissingMaps()) {
			cancelCountdown();
			showMissingMapsScreen();
		}
	}

	private void showMissingMapsScreen() {
		NavigationSession session = getSession();
		if (session != null) {
			session.showMissingMapsScreen();
		}
	}

	@Override
	public void onCreate(@NonNull LifecycleOwner owner) {
		super.onCreate(owner);
		chizuPreviewFlat(true); // 白い熊 地図 +052
		OsmandApplication app = getApp();
		// 白い熊 地図 +057: routes that arrive without changing the selection are measured too
		if (app.getRoutingHelper().getChizuCandidates() != null) {
			app.getRoutingHelper().getChizuCandidates().addListener(chizuCandidatesListener);
		}
		app.getRoutingHelper().addListener(this);
		app.getTargetPointsHelper().addListener(stateChangedListener);
		if (calculateRoute) {
			prepareRoute();
		} else {
			updateRoute(true);
		}
	}

	@Override
	public void onDestroy(@NonNull LifecycleOwner owner) {
		super.onDestroy(owner);
		chizuPreviewFlat(false); // 白い熊 地図 +052
		getApp().getUiHandler().removeCallbacks(chizuRefit);
		cancelCountdown();
		OsmandApplication app = getApp();
		RoutingHelper routingHelper = app.getRoutingHelper();
		routingHelper.removeListener(this);
		if (routingHelper.isRoutePlanningMode() && !routingHelper.isPauseNavigation()) {
			app.stopNavigation();
		}
		getApp().getTargetPointsHelper().removeListener(stateChangedListener);
		getLifecycle().removeObserver(this);
	}

	@Override
	public void onResume(@NonNull LifecycleOwner owner) {
		if (getApp().getRoutingHelper().isRouteCalculated()) {
			zoomMapToRoute();
		}
		startCountdownIfReady();
	}

	@Override
	public void onStop(@NonNull LifecycleOwner owner) {
		cancelCountdown();
	}

	// held here: the candidates keep their listeners only as weak references
	private final Runnable chizuRefit = () -> {
		if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.CREATED)
				&& getApp().getRoutingHelper().isRouteCalculated()) {
			zoomMapToRoute();
		}
	};
	private final net.osmand.plus.routing.ChizuRouteCandidates.Listener chizuCandidatesListener = () -> {
		getApp().getUiHandler().removeCallbacks(chizuRefit);
		getApp().getUiHandler().postDelayed(chizuRefit, 500);
	};

	private void chizuPreviewFlat(boolean flat) {
		net.osmand.plus.auto.NavigationSession session = getApp().getCarNavigationSession();
		net.osmand.plus.auto.SurfaceRenderer surface = session == null ? null : session.getNavigationCarSurface();
		if (surface != null) {
			surface.chizuSetPreviewFlat(flat);
		}
	}

	public boolean hasStartCountdown() {
		return countdownMs > 0;
	}

	public boolean isCountdownRunning() {
		return countdownTimer != null;
	}

	private void startCountdownIfReady() {
		if (!hasStartCountdown() || countdownDone || isCountdownRunning()
				|| calculating || Algorithms.isEmpty(routeRows)
				|| !getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
			return;
		}
		if (getApp().getRoutingHelper().getRoute().hasMissingMaps()) {
			cancelCountdown();
			showMissingMapsScreen();
		} else {
			countdownSeconds = toCountdownSeconds(countdownMs);
			countdownTimer = new CountDownTimer(countdownMs, COUNTDOWN_INTERVAL_MS) {
				@Override
				public void onTick(long millisUntilFinished) {
					int seconds = toCountdownSeconds(millisUntilFinished);
					if (seconds != countdownSeconds) {
						countdownSeconds = seconds;
						invalidate();
					}
				}

				@Override
				public void onFinish() {
					countdownTimer = null;
					countdownDone = true;
					if (getApp().getRoutingHelper().getRoute().hasMissingMaps()) {
						invalidate();
						showMissingMapsScreen();
					} else {
						onNavigate();
					}
				}
			}.start();
			invalidate();
		}
	}

	private static int toCountdownSeconds(long millis) {
		return (int) Math.ceil(millis / (double) COUNTDOWN_INTERVAL_MS);
	}

	private void cancelCountdown() {
		if (!hasStartCountdown() || countdownDone) {
			return;
		}
		countdownDone = true;
		if (countdownTimer != null) {
			countdownTimer.cancel();
			countdownTimer = null;
		}
		invalidate();
	}

	private Template createTemplateWithRoute(@NonNull Header header) {
		Pane.Builder paneBuilder = new Pane.Builder();
		for (Row row : routeRows) {
			paneBuilder.addRow(row);
		}
		String startTitle = getApp().getString(R.string.shared_string_control_start);
		if (isCountdownRunning()) {
			startTitle = getApp().getString(R.string.ltr_or_rtl_combine_with_brackets, startTitle,
					String.valueOf(countdownSeconds));
		}
		// shiroikuma fork: filled yellow Start
		paneBuilder.addAction(ChizuCar.filledAction(getCarContext())
				.setTitle(startTitle)
				.setOnClickListener(this::onNavigate)
				.build());
		PaneTemplate.Builder paneTemplateBuilder = new PaneTemplate.Builder(paneBuilder.build());
		paneTemplateBuilder.setHeader(header);
		return paneTemplateBuilder.build();
	}

	private Template createLoadingStateTemplate(@NonNull Header header) {
		ListTemplate.Builder builder = new ListTemplate.Builder();
		builder.setLoading(true).setHeader(header);
		return builder.build();
	}

	@NonNull
	@Override
	public Template getTemplate() {
		MapWithContentTemplate.Builder builder = new MapWithContentTemplate.Builder()
				.setActionStrip(new ActionStrip.Builder().addAction(settingsAction).build());
		Header header = new Header.Builder()
				.setTitle(getCarContext().getString(R.string.current_route))
				.setStartHeaderAction(Action.BACK)
				.build();
		Template contentTemplate;
		if (calculating || Algorithms.isEmpty(routeRows)) {
			contentTemplate = createLoadingStateTemplate(header);
			if (!calculating) {
				finish();
			}
		} else {
			contentTemplate = createTemplateWithRoute(header);
		}
		builder.setContentTemplate(contentTemplate);
		return builder.build();
	}

	private void onRouteSelected(int index) {
	}

	private void onRoutesVisible(int startIndex, int endIndex) {
	}

	private void onNavigate() {
		cancelCountdown();
		getApp().getOsmandMap().getMapActions().startNavigation();
		setResult(searchResult);
		finish();
	}

	@Override
	public void newRouteIsCalculated(boolean newRoute, ValueHolder<Boolean> showToast) {
		// 白い熊 地図: a preview left in the stack once navigation is running must not frame the
		// rerouted route — that unlinked the car map and turned it north-up and flat on every reroute
		if (getApp().getRoutingHelper().isFollowingMode()) {
			return;
		}
		zoomMapToRoute();
		updateRoute(newRoute);
	}

	private void zoomMapToRoute() {
		RoutingHelper rh = getApp().getRoutingHelper();
		QuadRect mapRect = RoutingHelperUtils.getRouteRect(getApp(), rh.getRoute());
		// 白い熊 地図 +054: every route offered is in view, as on the phone — not only the selected one
		if (mapRect != null && rh.getChizuCandidates() != null) {
			for (net.osmand.plus.routing.ChizuRouteCandidates.Candidate c : rh.getChizuCandidates().getCandidates()) {
				QuadRect r = RoutingHelperUtils.getRouteRect(getApp(), c.route);
				if (r != null && !r.hasInitialState()) {
					mapRect.left = Math.min(mapRect.left, r.left);
					mapRect.right = Math.max(mapRect.right, r.right);
					mapRect.top = Math.max(mapRect.top, r.top);
					mapRect.bottom = Math.min(mapRect.bottom, r.bottom);
				}
			}
		}
		// 白い熊 地図 +063: the points of every route offered, for the fit to check against the panel card
		if (mapRect != null) {
			java.util.List<net.osmand.Location> all = new java.util.ArrayList<>(rh.getRoute().getImmutableAllLocations());
			if (rh.getChizuCandidates() != null) {
				for (net.osmand.plus.routing.ChizuRouteCandidates.Candidate c : rh.getChizuCandidates().getCandidates()) {
					all.addAll(c.route.getImmutableAllLocations());
				}
			}
			int step = Math.max(1, all.size() / 600);
			java.util.List<net.osmand.data.LatLon> pts = new java.util.ArrayList<>();
			for (int i = 0; i < all.size(); i += step) {
				pts.add(new net.osmand.data.LatLon(all.get(i).getLatitude(), all.get(i).getLongitude()));
			}
			net.osmand.plus.helpers.TargetPoint dest = getApp().getTargetPointsHelper().getPointToNavigate();
			if (dest != null) {
				pts.add(dest.getLatLon());
			}
			net.osmand.data.LatLon here = getApp().getMapViewTrackingUtilities().getDefaultLocation();
			if (here != null) {
				pts.add(here);
			}
			setChizuFitPoints(pts);
		}
		// during navigation the map follows the location. Route listeners get the event even if this preview has just been closed
		if (mapRect != null && !rh.isFollowingMode()) {
			adjustMapToRect(getApp().getMapViewTrackingUtilities().getDefaultLocation(), mapRect);
		}
	}

	@Override
	public void routeWasCancelled() {
		if (isCountdownRunning()) {
			cancelCountdown();
		}
		if (!getApp().getRoutingHelper().isRoutePlanningMode()) {
			finish();
		}
	}

	@Override
	public void routeWasFinished() {
	}
}
