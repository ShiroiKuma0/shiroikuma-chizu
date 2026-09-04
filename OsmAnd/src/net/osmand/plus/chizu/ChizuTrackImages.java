package net.osmand.plus.chizu;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Log;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.data.RotatedTileBox;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.render.MapRenderRepositories;
import net.osmand.plus.resources.ResourceManager;
import net.osmand.plus.track.helpers.SelectedGpxFile;
import net.osmand.plus.views.Renderable.RenderableSegment;
import net.osmand.plus.views.Renderable.StandardTrack;
import net.osmand.shared.data.KQuadRect;
import net.osmand.shared.gpx.GpxFile;
import net.osmand.shared.gpx.primitives.TrkSegment;
import net.osmand.shared.gpx.primitives.WptPt;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * shiroikuma fork: a map picture drawn with no map on screen — one walk over the map for
 * {@code IMPORT_TRACK}, or the bare map of a tile block for {@code EXPORT_BASEMAP}.
 *
 * The legacy rasterizer ({@link MapRenderRepositories}) turns the installed offline maps into
 * a plain {@link Bitmap} on a worker thread — no {@code MapActivity}, no GL surface, which the
 * OpenGL core would demand. The track is then stroked over that bitmap on an ordinary canvas.
 *
 * Two things upstream's own {@code MapBitmapDrawer} gets wrong for our purpose, and why this is
 * a separate class rather than a subclass:
 * <ul>
 * <li>it asks {@code updateRenderedMapNeeded()} first, which answers "no" for a box already
 *     drawn — and then the callback never comes and the request hangs for ever;</li>
 * <li>it strokes the track in one flat colour, and our yellow is invisible on a day map.
 *     Every line here is laid over a dark halo, so it reads on any background.</li>
 * </ul>
 *
 * The rasterizer is a single shared instance, so every picture is drawn under {@link #LOCK}.
 */
public class ChizuTrackImages {

	private static final String TAG = "ChizuAutomation";

	/** One picture at a time: the app owns exactly one rasterizer and one bitmap in it. */
	private static final Object LOCK = new Object();

	private static final long RENDER_TIMEOUT_MS = 120_000;
	/** The track keeps this share of the picture; the rest is margin. */
	private static final float FIT = 0.86f;
	private static final int MIN_ZOOM = 3;
	private static final int MAX_ZOOM = 17;

	/** What was actually underneath the track. */
	public enum Detail {
		MAP, BASEMAP, NONE
	}

	public static class Shot {

		public final Bitmap bitmap;
		public final Detail detail;
		public final int zoom;

		Shot(@NonNull Bitmap bitmap, @NonNull Detail detail, int zoom) {
			this.bitmap = bitmap;
			this.detail = detail;
			this.zoom = zoom;
		}
	}

	private final OsmandApplication app;
	private final GpxFile gpx;

	public ChizuTrackImages(@NonNull OsmandApplication app, @NonNull GpxFile gpx) {
		this.app = app;
		this.gpx = gpx;
	}

	/**
	 * Draws the track over the map. Never throws and never returns null: with no map data for
	 * the area the track is stroked on a plain background instead, and {@link Shot#detail} says
	 * so, so the caller can tell 白い熊 why the picture has no streets in it.
	 *
	 * @param nightMode {@code false} pins the day style, {@code true} the night one, {@code null}
	 *                  follows the app — which is right for a live map and wrong for a picture
	 *                  filed away and looked at years later beside its neighbours.
	 */
	@NonNull
	public Shot draw(int width, int height, float density, @ColorInt int trackColor,
			@ColorInt int emptyBackground, @Nullable Boolean nightMode) {
		synchronized (LOCK) {
			RotatedTileBox tileBox = fit(gpx.getRect(), width, height, density);
			Shot shot = shoot(app, tileBox, emptyBackground, nightMode);
			Canvas canvas = new Canvas(shot.bitmap);
			drawTrack(canvas, tileBox, width, height, trackColor);
			return shot;
		}
	}

	/**
	 * The map alone, over exactly the box asked for and with nothing laid on top — what
	 * {@code EXPORT_BASEMAP} hands 自由作業盤 to cache and draw its own walks over. No track,
	 * no marker, and nothing that reaches this app's own list of tracks.
	 */
	@NonNull
	public static Shot area(@NonNull OsmandApplication app, @NonNull RotatedTileBox tileBox,
			@ColorInt int emptyBackground, @Nullable Boolean nightMode) {
		synchronized (LOCK) {
			return shoot(app, tileBox, emptyBackground, nightMode);
		}
	}

	/** The map underneath, on a bitmap of our own. The caller holds {@link #LOCK}. */
	@NonNull
	private static Shot shoot(@NonNull OsmandApplication app, @NonNull RotatedTileBox tileBox,
			@ColorInt int emptyBackground, @Nullable Boolean nightMode) {
		Detail detail = Detail.NONE;
		Bitmap picture = null;
		Bitmap rendered;
		MapRenderRepositories renderer = app.getResourceManager().getRenderer();
		renderer.setNightModeOverride(nightMode);
		try {
			rendered = renderMap(app, tileBox);
		} finally {
			renderer.setNightModeOverride(null);
		}
		if (rendered != null) {
			detail = detail(renderer.getCheckedRenderedState());
			try {
				// the rasterizer hands back its own live bitmap and reuses it next time
				picture = rendered.copy(Bitmap.Config.ARGB_8888, true);
			} catch (Throwable error) {
				Log.e(TAG, "cannot copy the rendered map", error);
			}
		}
		if (picture == null) {
			detail = Detail.NONE;
			picture = Bitmap.createBitmap(tileBox.getPixWidth(), tileBox.getPixHeight(),
					Bitmap.Config.ARGB_8888);
			picture.eraseColor(emptyBackground);
		}
		return new Shot(picture, detail, tileBox.getZoom());
	}

	public static void write(@NonNull Bitmap bitmap, @NonNull File file) throws IOException {
		try (FileOutputStream out = new FileOutputStream(file)) {
			write(bitmap, out);
		}
	}

	/**
	 * The same picture into a stream the caller owns — a content URI handed to us by 自由作業盤.
	 * The stream is closed here either way: it is the far app's file descriptor, and leaving one
	 * open holds a lock on a file that app is waiting to read.
	 */
	public static void write(@NonNull Bitmap bitmap, @NonNull OutputStream out) throws IOException {
		try (OutputStream stream = out) {
			if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
				throw new IOException("PNG compression refused");
			}
			stream.flush();
		}
	}

	// ---------- the map underneath ----------

	@Nullable
	private static Bitmap renderMap(@NonNull OsmandApplication app, @NonNull RotatedTileBox tileBox) {
		ResourceManager resources = app.getResourceManager();
		MapRenderRepositories renderer = resources.getRenderer();
		CountDownLatch done = new CountDownLatch(1);
		AtomicBoolean interrupted = new AtomicBoolean();
		try {
			// forceLoadMap, and deliberately no updateRenderedMapNeeded() gate in front of it:
			// a box the rasterizer has already drawn would answer "no" and never call back
			resources.updateRendererMap(tileBox, wasInterrupted -> {
				interrupted.set(wasInterrupted);
				done.countDown();
			}, true);
			if (!done.await(RENDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
				Log.w(TAG, "map render timed out after " + RENDER_TIMEOUT_MS + " ms");
				return null;
			}
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			return null;
		} catch (Throwable error) {
			Log.e(TAG, "map render failed", error);
			return null;
		}
		if (interrupted.get()) {
			Log.w(TAG, "map render was interrupted — falling back to a plain background");
			return null;
		}
		Bitmap bitmap = renderer.getBitmap();
		if (bitmap == null || bitmap.isRecycled()) {
			return null;
		}
		if (bitmap.getWidth() != tileBox.getPixWidth() || bitmap.getHeight() != tileBox.getPixHeight()) {
			// somebody else's render won the shared bitmap; the track would land in the wrong place
			Log.w(TAG, "rendered bitmap is not the size we asked for");
			return null;
		}
		return bitmap;
	}

	@NonNull
	private static Detail detail(int renderedState) {
		if ((renderedState & 2) != 0) {
			return Detail.MAP;
		}
		return (renderedState & 1) != 0 ? Detail.BASEMAP : Detail.NONE;
	}

	// ---------- the track on top ----------

	private void drawTrack(@NonNull Canvas canvas, @NonNull RotatedTileBox tileBox,
			int width, int height, @ColorInt int trackColor) {
		float stroke = Math.max(2.5f, Math.min(width, height) / 140f);
		SelectedGpxFile selected = new SelectedGpxFile();
		selected.setGpxFile(gpx, app);

		Paint halo = stroke(stroke + Math.max(2f, stroke * 0.9f), haloColor(trackColor));
		Paint line = stroke(stroke, trackColor);
		for (TrkSegment segment : selected.getPointsToDisplay()) {
			if (segment.getPoints().isEmpty()) {
				continue;
			}
			if (segment.getRenderer() == null) {
				segment.setRenderer(new StandardTrack(segment.getPoints(), 17.2));
			}
			if (segment.getRenderer() instanceof RenderableSegment renderable) {
				renderable.drawSegment(tileBox.getZoom(), halo, canvas, tileBox);
				renderable.drawSegment(tileBox.getZoom(), line, canvas, tileBox);
			}
		}
		drawEnds(canvas, tileBox, width, height, trackColor);
	}

	/** Which end of the walk is which — a plain dot at the start, a ringed one at the finish. */
	private void drawEnds(@NonNull Canvas canvas, @NonNull RotatedTileBox tileBox,
			int width, int height, @ColorInt int trackColor) {
		WptPt first = null;
		WptPt last = null;
		for (TrkSegment segment : gpx.getNonEmptyTrkSegments(false)) {
			List<WptPt> points = segment.getPoints();
			if (points.isEmpty()) {
				continue;
			}
			if (first == null) {
				first = points.get(0);
			}
			last = points.get(points.size() - 1);
		}
		if (first == null) {
			return;
		}
		float radius = Math.max(3.5f, Math.min(width, height) / 55f);
		@ColorInt int halo = haloColor(trackColor);
		mark(canvas, tileBox, first, radius, trackColor, halo);
		if (last != null && last != first) {
			mark(canvas, tileBox, last, radius, halo, trackColor);
		}
	}

	private void mark(@NonNull Canvas canvas, @NonNull RotatedTileBox tileBox, @NonNull WptPt point,
			float radius, @ColorInt int fill, @ColorInt int ring) {
		float x = tileBox.getPixXFromLatLon(point.getLat(), point.getLon());
		float y = tileBox.getPixYFromLatLon(point.getLat(), point.getLon());
		Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		paint.setStyle(Paint.Style.FILL);
		paint.setColor(fill);
		canvas.drawCircle(x, y, radius, paint);
		paint.setStyle(Paint.Style.STROKE);
		paint.setStrokeWidth(Math.max(1.5f, radius / 3.5f));
		paint.setColor(ring);
		canvas.drawCircle(x, y, radius, paint);
	}

	@NonNull
	private Paint stroke(float width, @ColorInt int color) {
		Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		paint.setStyle(Paint.Style.STROKE);
		paint.setStrokeCap(Paint.Cap.ROUND);
		paint.setStrokeJoin(Paint.Join.ROUND);
		paint.setStrokeWidth(width);
		paint.setColor(color);
		return paint;
	}

	/** Black under a bright line, white under a dark one — legible either way. */
	@ColorInt
	private int haloColor(@ColorInt int trackColor) {
		double luminance = 0.2126 * Color.red(trackColor)
				+ 0.7152 * Color.green(trackColor)
				+ 0.0722 * Color.blue(trackColor);
		return luminance >= 110 ? 0xFF000000 : 0xFFFFFFFF;
	}

	// ---------- framing ----------

	/**
	 * The tightest zoom at which the whole track still sits inside the margin. Upstream climbs
	 * from 15 and lets the track graze the edge; this walks down from the top so a short walk
	 * gets all the detail it can, with air around it.
	 */
	@NonNull
	public static RotatedTileBox fit(@NonNull KQuadRect rect, int width, int height, float density) {
		RotatedTileBox tileBox = new RotatedTileBox.RotatedTileBoxBuilder()
				.setLocation(rect.centerY(), rect.centerX())
				.setZoom(MAX_ZOOM)
				.density(density)
				.setMapDensity(density)
				.setPixelDimensions(width, height, 0.5f, 0.5f)
				.build();
		while (tileBox.getZoom() > MIN_ZOOM && !fits(tileBox, rect, width, height)) {
			tileBox.setZoom(tileBox.getZoom() - 1);
		}
		return tileBox;
	}

	private static boolean fits(@NonNull RotatedTileBox tileBox, @NonNull KQuadRect rect, int width, int height) {
		return inside(tileBox, rect.getTop(), rect.getLeft(), width, height)
				&& inside(tileBox, rect.getBottom(), rect.getRight(), width, height);
	}

	private static boolean inside(@NonNull RotatedTileBox tileBox, double lat, double lon, int width, int height) {
		float x = tileBox.getPixXFromLatLon(lat, lon);
		float y = tileBox.getPixYFromLatLon(lat, lon);
		float marginX = width * (1 - FIT) / 2;
		float marginY = height * (1 - FIT) / 2;
		return x >= marginX && x <= width - marginX && y >= marginY && y <= height - marginY;
	}
}
