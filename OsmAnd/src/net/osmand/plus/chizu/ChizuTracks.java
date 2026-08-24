package net.osmand.plus.chizu;

import android.os.Environment;
import android.util.DisplayMetrics;
import android.util.Log;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.IndexConstants;
import net.osmand.data.LatLon;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.plus.shared.SharedUtil;
import net.osmand.plus.track.GpxSelectionParams;
import net.osmand.shared.data.KQuadRect;
import net.osmand.shared.gpx.GpxDataItem;
import net.osmand.shared.gpx.GpxDbHelper;
import net.osmand.shared.gpx.GpxFile;
import net.osmand.shared.gpx.GpxParameter;
import net.osmand.shared.gpx.GpxTrackAnalysis;
import net.osmand.shared.io.KFile;
import net.osmand.util.Algorithms;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * shiroikuma fork: taking a walk in from a sister app — 白い熊 自由作業盤 pulls the recorded
 * track off the HUAWEI Band 11 Pro, hands us the GPX, and gets back a track this app keeps and
 * two pictures of it.
 *
 * Everything here runs with no UI: the file lands in this app's own tracks folder and its row in
 * the tracks database, a normalized copy and the two PNGs land in a folder both apps can read,
 * and the numbers we compute travel back beside the band's own — never merged with them.
 */
public class ChizuTracks {

	private static final String TAG = "ChizuAutomation";

	/** 白い熊's own tracks folder, where the shared copies land when the caller names none. */
	private static final String DEFAULT_OUT_DIR = "〇/[666] 私資料/[666][147] tracks";
	/** Sub-folder of this app's tracks directory that imported walks are filed under. */
	private static final String DEFAULT_FOLDER = "自由作業盤";

	/** The fork's yellow, over the fork's black when there is no map to draw. */
	@ColorInt
	public static final int DEFAULT_TRACK_COLOR = 0xFFFFFF00;
	@ColorInt
	private static final int EMPTY_BACKGROUND = 0xFF000000;

	private ChizuTracks() {
	}

	/** Anything the caller can put right; its message is what follows {@code ERROR:}. */
	public static class TrackError extends Exception {

		public TrackError(@NonNull String message) {
			super(message);
		}
	}

	public static class Params {

		public String gpxData;
		public String gpxPath;
		public String name;
		public String trackId;
		public String folder = DEFAULT_FOLDER;
		public String outDir;
		public int thumbWidth = 480;
		public int thumbHeight = 360;
		public int mapWidth = 1440;
		public int mapHeight = 1080;
		@ColorInt
		public int color = DEFAULT_TRACK_COLOR;
		/** Pinned to the day style by default: a picture kept for years must not drift. */
		public Boolean night = Boolean.FALSE;
		public boolean show;
		public float density = 2f;
	}

	public static class Result {

		public String trackId;
		public String name;
		public String storedPath;
		public String gpxPath;
		public String thumbPath;
		public String mapPath;
		public String mapDetail;
		public int zoom;
		public float distanceM;
		public long durationS;
		public long movingS;
		public long startTime;
		public long endTime;
		public int points;
		public double elevationUp;
		public double elevationDown;
		public float avgSpeed;
		public float maxSpeed;
	}

	@NonNull
	public static Result importTrack(@NonNull OsmandApplication app, @NonNull Params params)
			throws TrackError {
		GpxFile gpx = load(params);
		if (!gpx.hasTrkPt()) {
			throw new TrackError("no track points");
		}
		String relative = relativePath(app, params);
		File stored = app.getAppPath(IndexConstants.GPX_INDEX_DIR + relative);
		File outDir = outDir(app, params.outDir);

		boolean existed = stored.exists();
		Algorithms.createParentDirsForFile(stored);
		gpx.setPath(stored.getAbsolutePath());
		if (params.color != 0) {
			gpx.setColor(params.color);
		}
		Exception failure = SharedUtil.writeGpxFile(stored, gpx);
		if (failure != null) {
			throw new TrackError("cannot write " + stored.getAbsolutePath() + ": " + reason(failure));
		}
		GpxTrackAnalysis analysis = gpx.getAnalysis(stored.lastModified());
		register(app, stored, analysis, params.color, existed);
		select(app, gpx, params.show);

		Result result = new Result();
		result.trackId = relative;
		result.name = stored.getName();
		result.storedPath = stored.getAbsolutePath();
		fill(result, analysis);

		String base = stripExtension(stored.getName());
		result.gpxPath = copy(stored, new File(outDir, base + ".gpx"));
		draw(app, gpx, params, outDir, base, result);
		return result;
	}

	/** The map picture and its thumbnail, each drawn at its own size — never one downscaled. */
	private static void draw(@NonNull OsmandApplication app, @NonNull GpxFile gpx,
			@NonNull Params params, @NonNull File outDir, @NonNull String base,
			@NonNull Result result) throws TrackError {
		ChizuTrackImages images = new ChizuTrackImages(app, gpx);
		ChizuTrackImages.Shot large = images.draw(params.mapWidth, params.mapHeight, params.density,
				params.color, EMPTY_BACKGROUND, params.night);
		ChizuTrackImages.Shot thumb = images.draw(params.thumbWidth, params.thumbHeight, params.density,
				params.color, EMPTY_BACKGROUND, params.night);
		File mapFile = new File(outDir, base + ".png");
		File thumbFile = new File(outDir, base + "_thumb.png");
		try {
			ChizuTrackImages.write(large.bitmap, mapFile);
			ChizuTrackImages.write(thumb.bitmap, thumbFile);
		} catch (IOException error) {
			throw new TrackError("cannot write the picture: " + reason(error));
		} finally {
			large.bitmap.recycle();
			thumb.bitmap.recycle();
		}
		result.mapPath = mapFile.getAbsolutePath();
		result.thumbPath = thumbFile.getAbsolutePath();
		result.mapDetail = large.detail.name().toLowerCase();
		result.zoom = large.zoom;
	}

	// ---------- the file ----------

	@NonNull
	private static GpxFile load(@NonNull Params params) throws TrackError {
		GpxFile gpx;
		if (params.gpxData != null && !params.gpxData.trim().isEmpty()) {
			gpx = SharedUtil.loadGpxFile(
					new ByteArrayInputStream(params.gpxData.getBytes(StandardCharsets.UTF_8)));
		} else if (params.gpxPath != null && !params.gpxPath.trim().isEmpty()) {
			File source = new File(params.gpxPath.trim());
			if (!source.isFile() || !source.canRead()) {
				throw new TrackError("cannot read " + source.getAbsolutePath());
			}
			gpx = SharedUtil.loadGpxFile(source);
		} else {
			throw new TrackError("no gpx: pass gpx_data or gpx_path");
		}
		if (gpx.getError() != null) {
			throw new TrackError("gpx unreadable: " + reason(gpx.getError()));
		}
		return gpx;
	}

	/**
	 * Where the track lives inside this app. A {@code track_id} we handed out before comes back
	 * to the same file, so re-sharing a walk corrects it instead of breeding copies.
	 */
	@NonNull
	private static String relativePath(@NonNull OsmandApplication app, @NonNull Params params) {
		if (params.trackId != null && !params.trackId.trim().isEmpty()) {
			String id = normalizeId(app, params.trackId);
			if (!id.isEmpty()) {
				return withGpxExtension(id);
			}
		}
		String folder = safeName(params.folder != null ? params.folder : DEFAULT_FOLDER);
		String name = safeName(params.name != null ? params.name : "");
		if (name.isEmpty()) {
			name = "track " + System.currentTimeMillis();
		}
		return (folder.isEmpty() ? "" : folder + "/") + withGpxExtension(name);
	}

	/**
	 * The id we hand out is relative to the tracks directory — {@code 自由作業盤/walk.gpx}. A
	 * hand-written one tends to carry more: the whole {@code stored_path}, or the {@code tracks/}
	 * the id is relative TO. Both are reduced to the same id here, because the alternative is
	 * filing a second copy of the walk under {@code tracks/tracks/…} while the first sits there
	 * uncorrected — a duplicate is exactly what {@code track_id} exists to prevent.
	 */
	@NonNull
	private static String normalizeId(@NonNull OsmandApplication app, @NonNull String trackId) {
		String id = trackId.trim().replace('\\', '/');
		String tracksDir = app.getAppPath(IndexConstants.GPX_INDEX_DIR).getAbsolutePath();
		if (id.startsWith(tracksDir)) {
			id = id.substring(tracksDir.length());
		}
		String tracksSegment = IndexConstants.GPX_INDEX_DIR.replace("/", "");
		StringBuilder path = new StringBuilder();
		boolean atStart = true;
		for (String part : id.split("/")) {
			String clean = safeName(part);
			if (clean.isEmpty()) {
				continue;
			}
			boolean leadingTracksDir = atStart && clean.equals(tracksSegment);
			atStart = false;
			if (leadingTracksDir) {
				continue;
			}
			if (path.length() > 0) {
				path.append('/');
			}
			path.append(clean);
		}
		return path.toString();
	}

	@NonNull
	private static String withGpxExtension(@NonNull String name) {
		return name.toLowerCase().endsWith(IndexConstants.GPX_FILE_EXT) ? name
				: name + IndexConstants.GPX_FILE_EXT;
	}

	/** Everything a filename cannot hold, out; the Japanese in a walk's name stays. */
	@NonNull
	private static String safeName(@NonNull String name) {
		String clean = name.replaceAll("[/\\\\:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
		while (clean.startsWith(".")) {
			clean = clean.substring(1).trim();
		}
		return clean.length() > 96 ? clean.substring(0, 96).trim() : clean;
	}

	@NonNull
	private static String stripExtension(@NonNull String name) {
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	@NonNull
	private static File outDir(@NonNull OsmandApplication app, @Nullable String requested)
			throws TrackError {
		File dir = requested != null && !requested.trim().isEmpty()
				? new File(requested.trim())
				: new File(Environment.getExternalStorageDirectory(), DEFAULT_OUT_DIR);
		if (!dir.getAbsolutePath().startsWith(app.getAppPath("").getAbsolutePath())
				&& !ChizuStorage.hasAllFilesAccess()) {
			throw new TrackError("no-storage-access");
		}
		if (!dir.exists() && !dir.mkdirs()) {
			throw new TrackError("cannot create directory " + dir.getAbsolutePath());
		}
		if (!dir.isDirectory()) {
			throw new TrackError("not a directory: " + dir.getAbsolutePath());
		}
		return dir;
	}

	@NonNull
	private static String copy(@NonNull File source, @NonNull File destination) throws TrackError {
		try {
			Algorithms.fileCopy(source, destination);
		} catch (IOException error) {
			throw new TrackError("cannot write " + destination.getAbsolutePath() + ": " + reason(error));
		}
		return destination.getAbsolutePath();
	}

	// ---------- this app's own bookkeeping ----------

	private static void register(@NonNull OsmandApplication app, @NonNull File stored,
			@NonNull GpxTrackAnalysis analysis, @ColorInt int color, boolean existed) {
		try {
			GpxDbHelper helper = app.getGpxDbHelper();
			KFile file = SharedUtil.kFile(stored);
			GpxDataItem item = existed ? helper.getItem(file) : null;
			boolean fresh = item == null;
			if (fresh) {
				item = new GpxDataItem(file);
			}
			item.setParameter(GpxParameter.COLOR, color);
			item.setParameter(GpxParameter.API_IMPORTED, true);
			item.setAnalysis(analysis);
			if (fresh) {
				helper.add(item);
			} else {
				helper.updateDataItem(item);
			}
		} catch (Throwable error) {
			// the track is on disk either way; a missing database row only costs it its stats row
			Log.e(TAG, "cannot register the track in the database", error);
		}
	}

	/** Hidden by default — a shared walk must not silently redraw 白い熊's map. */
	private static void select(@NonNull OsmandApplication app, @NonNull GpxFile gpx, boolean show) {
		app.runInUIThread(() -> {
			try {
				GpxSelectionParams params = GpxSelectionParams.newInstance().syncGroup().saveSelection();
				params = show ? params.showOnMap() : params.hideFromMap();
				app.getSelectedGpxHelper().selectGpxFile(gpx, params);
			} catch (Throwable error) {
				Log.e(TAG, "cannot select the track", error);
			}
		});
	}

	private static void fill(@NonNull Result result, @NonNull GpxTrackAnalysis analysis) {
		result.distanceM = analysis.getTotalDistance();
		result.durationS = analysis.getTimeSpan() / 1000;
		result.movingS = analysis.getTimeMoving() / 1000;
		result.startTime = analysis.getStartTime();
		result.endTime = analysis.getEndTime();
		result.points = analysis.getPoints();
		result.elevationUp = analysis.getDiffElevationUp();
		result.elevationDown = analysis.getDiffElevationDown();
		result.avgSpeed = analysis.getAvgSpeed();
		result.maxSpeed = analysis.getMaxSpeed();
	}

	@NonNull
	private static String reason(@Nullable Throwable error) {
		if (error == null) {
			return "unknown";
		}
		String message = error.getMessage();
		return message != null && !message.isEmpty() ? message : error.getClass().getSimpleName();
	}

	/**
	 * Selects the track and records where the map is to open — a **pending** target, which
	 * OsmAnd applies when the map resumes, in place of the centre it restores.
	 *
	 * The first version set the camera on the map view directly. With no {@code MapActivity}
	 * alive there is nothing to set it on, and the one that starts afterwards restores its own
	 * saved centre over the top: the track was selected and the map opened wherever it had been.
	 * Upstream's own comment on {@code setLastKnownMapLocation} says as much — use
	 * {@code setMapLocationToShow} when the point is meant to be shown.
	 *
	 * @return false when no track answers to that id; the caller decides what to say about it.
	 */
	public static boolean prepareShow(@NonNull OsmandApplication app, @Nullable String trackId) {
		File file = resolve(app, trackId);
		if (file == null) {
			return false;
		}
		GpxFile gpx = SharedUtil.loadGpxFile(file);
		if (gpx.getError() != null) {
			Log.e(TAG, "cannot read " + file.getName() + ": " + reason(gpx.getError()));
			return false;
		}
		gpx.setPath(file.getAbsolutePath());
		KQuadRect rect = gpx.getRect();
		LatLon centre = new LatLon(rect.centerY(), rect.centerX());
		int zoom = fitZoom(app, rect);
		OsmandSettings settings = app.getSettings();

		// Where the map opens. MapActivity restores this on EVERY resume, near the top of
		// onResume and before it looks at anything else — it is the thing that was overwriting
		// us, so we write the walk into it rather than fight it.
		settings.setLastKnownMapLocation(centre);
		settings.setLastKnownMapZoom(zoom);
		settings.setLastKnownMapZoomFloatPart(0);

		// The pending target as well, but for its side effect rather than for its move: reading
		// it is what unlinks the map from the GPS fix, so a live recording cannot drag the view
		// back to 白い熊 the moment the walk appears. The move itself cannot be relied on —
		// onPause blocks the animation thread, onResume consumes this target at line ~710 and
		// only re-enables animations a hundred lines later, so startMoving() returns without
		// doing anything and the target is already cleared. Upstream's own bug, harmless to us:
		// the map is at the walk before the target is read, so the move is a no-op anyway.
		settings.setMapLocationToShow(centre.getLatitude(), centre.getLongitude(), zoom);
		app.runInUIThread(() -> {
			try {
				app.getSelectedGpxHelper().selectGpxFile(gpx, GpxSelectionParams.getDefaultSelectionParams());
			} catch (Throwable error) {
				Log.e(TAG, "cannot select the track", error);
			}
		});
		return true;
	}

	/** The tightest zoom at which the whole track still fits the screen. */
	public static int fitZoom(@NonNull OsmandApplication app, @NonNull KQuadRect rect) {
		DisplayMetrics metrics = app.getResources().getDisplayMetrics();
		int width = Math.max(320, metrics.widthPixels);
		int height = Math.max(320, metrics.heightPixels);
		return ChizuTrackImages.fit(rect, width, height, metrics.density).getZoom();
	}

	/** The file behind a {@code track_id}, for the actions that only need to find it again. */
	@Nullable
	public static File resolve(@NonNull OsmandApplication app, @Nullable String trackId) {
		if (trackId == null || trackId.trim().isEmpty()) {
			return null;
		}
		File direct = new File(trackId.trim());
		if (direct.isAbsolute() && direct.isFile()) {
			return direct;
		}
		String relative = normalizeId(app, trackId);
		if (relative.isEmpty()) {
			return null;
		}
		File file = app.getAppPath(IndexConstants.GPX_INDEX_DIR + withGpxExtension(relative));
		return file.isFile() ? file : null;
	}
}
