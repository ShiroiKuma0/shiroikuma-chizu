package net.osmand.plus.chizu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.activities.MapActivity;

import java.util.Locale;

/**
 * shiroikuma fork: the walk contract — 白い熊 自由作業盤 hands over a track it pulled off the
 * HUAWEI Band 11 Pro, this app files it and draws it, headlessly, and answers by broadcast.
 *
 * <pre>
 * &lt;pkg&gt;.action.IMPORT_TRACK  token gpx_uri|gpx_data|gpx_path [name] [track_id] [folder]
 *                           [gpx_out_uri] [thumb_out_uri] [map_out_uri] [out_dir]
 *                           [thumb_w thumb_h map_w map_h | thumb_px map_px]
 *                           [track_color] [night=day|night|auto] [show] [density]
 *                           → OK:&lt;track_id&gt;|&lt;gpx&gt;|&lt;thumb&gt;|&lt;map&gt;
 *                             |&lt;distance_m&gt;|&lt;duration_s&gt;, and every value again as its
 *                             own string extra. {@code active_time_s} is the extra worth
 *                             reading beside the band's own figures: it is the walk minus its
 *                             gaps, the one number the band measures too.
 * &lt;pkg&gt;.action.SHOW_TRACK    token track_id → OK:&lt;track_id&gt;, and the map comes to the front
 *                           on that walk. The one action here that is not headless.
 * &lt;pkg&gt;.action.EXPORT_BASEMAP
 *                           token zoom tile_x tile_y tiles_w tiles_h [tile_px]
 *                           out_uri|out_path [night=day|night|auto]
 *                           → OK:&lt;out&gt;|&lt;width&gt;|&lt;height&gt;|&lt;map_detail&gt;, and every
 *                             value again as its own string extra. The map under a block of
 *                             standard Web Mercator tiles and nothing else — no track, no
 *                             marker, and nothing added to this app's own list of tracks. See
 *                             {@link ChizuBaseMap} for why the request is in tiles.
 * </pre>
 *
 * <b>URI mode.</b> Any of {@code gpx_uri}, {@code gpx_out_uri}, {@code thumb_out_uri},
 * {@code map_out_uri} or {@code out_uri} puts the request in it, and then nothing is written to
 * shared storage at all: {@code out_dir} is read and discarded, and an artefact is produced only
 * where the caller named a URI to put it. 自由作業盤 keeps its workouts in its own database now
 * and names none of the pictures, so a walk is filed and measured and no PNG is drawn — it strokes
 * the route over its own cached {@code EXPORT_BASEMAP} cutout instead. The path form still works
 * unchanged; neither app had to ship first. Every URI is opened through {@link ChizuUris}, which
 * carries the note on why the caller's grant must be explicit.
 *
 * Same switch and same token as the 保存復元 export: {@link ChizuAutomation}. The reply is a
 * fresh broadcast, never a Binder — see {@link ChizuReplier}.
 */
public class ChizuTrackReceiver extends BroadcastReceiver {

	private static final String TAG = "ChizuAutomation";

	private static final String SUFFIX_IMPORT = ".action.IMPORT_TRACK";
	private static final String SUFFIX_SHOW = ".action.SHOW_TRACK";
	private static final String SUFFIX_BASEMAP = ".action.EXPORT_BASEMAP";

	private static final long INIT_TIMEOUT_MS = 180_000;

	@Override
	public void onReceive(@NonNull Context context, @NonNull Intent intent) {
		String action = intent.getAction();
		if (action == null) {
			return;
		}
		OsmandApplication app = (OsmandApplication) context.getApplicationContext();
		ChizuReplier replier = new ChizuReplier(app, goAsync(), isOrderedBroadcast(),
				intent.getStringExtra("reply_action"), intent.getStringExtra("reply_package"),
				intent.getStringExtra("reply_id"));

		// The same one gate as the export receiver: the switch, and the token only when this app
		// asks for one. OPEN_TRACK stays outside it entirely — that lives on
		// ChizuShowTrackActivity, and opening the app at a track is what a launcher icon does.
		String refusal = ChizuAutomation.refuse(app, intent.getStringExtra("token"));
		if (refusal != null) {
			replier.send(refusal);
			return;
		}
		if (action.endsWith(SUFFIX_IMPORT)) {
			ChizuTracks.Params params = read(app, intent);
			new Thread(() -> runImport(app, replier, params), "chizu-import-track").start();
		} else if (action.endsWith(SUFFIX_SHOW)) {
			String trackId = intent.getStringExtra("track_id");
			new Thread(() -> runShow(app, replier, trackId), "chizu-show-track").start();
		} else if (action.endsWith(SUFFIX_BASEMAP)) {
			ChizuBaseMap.Params params = readBasemap(intent);
			new Thread(() -> runBasemap(app, replier, params), "chizu-export-basemap").start();
		} else {
			replier.send("ERROR:unknown action");
		}
	}

	// ---------- IMPORT_TRACK ----------

	private void runImport(@NonNull OsmandApplication app, @NonNull ChizuReplier replier,
			@NonNull ChizuTracks.Params params) {
		try {
			awaitInit(app);
			ChizuTracks.Result result = ChizuTracks.importTrack(app, params);
			Bundle extras = new Bundle();
			extras.putString("track_id", result.trackId);
			extras.putString("name", result.name);
			extras.putString("stored_path", result.storedPath);
			// where each artefact went, under the name of the form it went there in — both
			// families always present, and the one that does not apply is empty rather than
			// missing, so a reader never has to tell "not produced" from "extra not sent"
			extras.putString("gpx_path", params.uriMode ? "" : text(result.gpxPath));
			extras.putString("thumb_path", params.uriMode ? "" : text(result.thumbPath));
			extras.putString("map_path", params.uriMode ? "" : text(result.mapPath));
			extras.putString("gpx_uri", params.uriMode ? text(result.gpxPath) : "");
			extras.putString("thumb_uri", params.uriMode ? text(result.thumbPath) : "");
			extras.putString("map_uri", params.uriMode ? text(result.mapPath) : "");
			// both come out of the render, so with no picture asked for there is nothing behind
			// them: empty, never a stale value and never a zero that reads as a real zoom
			extras.putString("map_detail", text(result.mapDetail));
			extras.putString("zoom", result.zoom > 0 ? String.valueOf(result.zoom) : "");
			extras.putString("distance_m", decimal(result.distanceM));
			extras.putString("duration_s", String.valueOf(result.durationS));
			extras.putString("moving_time_s", String.valueOf(result.movingS));
			extras.putString("active_time_s", String.valueOf(result.activeS));
			extras.putString("points", String.valueOf(result.points));
			extras.putString("start_time", String.valueOf(result.startTime));
			extras.putString("end_time", String.valueOf(result.endTime));
			extras.putString("elevation_up", decimal(result.elevationUp));
			extras.putString("elevation_down", decimal(result.elevationDown));
			extras.putString("avg_speed", decimal(result.avgSpeed));
			extras.putString("max_speed", decimal(result.maxSpeed));

			replier.send("OK:" + result.trackId + "|" + text(result.gpxPath) + "|"
					+ text(result.thumbPath) + "|" + text(result.mapPath) + "|"
					+ decimal(result.distanceM) + "|" + result.durationS, extras);
		} catch (ChizuTracks.TrackError error) {
			replier.send("ERROR:" + error.getMessage());
		} catch (Throwable error) {
			Log.e(TAG, "import failed", error);
			replier.send("ERROR:" + error.getClass().getSimpleName());
		}
	}

	@NonNull
	private ChizuTracks.Params read(@NonNull OsmandApplication app, @NonNull Intent intent) {
		ChizuTracks.Params params = new ChizuTracks.Params();
		params.gpxUri = ChizuUris.value(intent.getStringExtra("gpx_uri"));
		params.gpxOutUri = ChizuUris.value(intent.getStringExtra("gpx_out_uri"));
		params.thumbOutUri = ChizuUris.value(intent.getStringExtra("thumb_out_uri"));
		params.mapOutUri = ChizuUris.value(intent.getStringExtra("map_out_uri"));
		params.uriMode = params.gpxUri != null || params.gpxOutUri != null
				|| params.thumbOutUri != null || params.mapOutUri != null;
		if (params.gpxUri != null) {
			// here and not on the worker: this runs at delivery, while the caller's grant is
			// youngest, and the walk is 150–220 KB — nothing to hesitate over on the main thread.
			// The worker starts by waiting up to three minutes for the app to initialize, which is
			// far past any grant scoped to the broadcast.
			try {
				params.gpxBytes = ChizuUris.read(app, params.gpxUri);
			} catch (Throwable error) {
				Log.e(TAG, "cannot read " + params.gpxUri, error);
				params.gpxError = "cannot read gpx_uri: " + ChizuUris.reason(error);
			}
		}
		params.gpxData = intent.getStringExtra("gpx_data");
		params.gpxPath = intent.getStringExtra("gpx_path");
		params.name = intent.getStringExtra("name");
		params.trackId = intent.getStringExtra("track_id");
		params.outDir = intent.getStringExtra("out_dir");
		String folder = intent.getStringExtra("folder");
		if (folder != null && !folder.trim().isEmpty()) {
			params.folder = folder;
		}
		// the explicit w/h form is the contract; the square edge is the courtesy fallback
		int thumbEdge = number(intent, "thumb_px", 0);
		int mapEdge = number(intent, "map_px", 0);
		params.thumbWidth = number(intent, "thumb_w", thumbEdge > 0 ? thumbEdge : params.thumbWidth);
		params.thumbHeight = number(intent, "thumb_h", thumbEdge > 0 ? thumbEdge : params.thumbHeight);
		params.mapWidth = number(intent, "map_w", mapEdge > 0 ? mapEdge : params.mapWidth);
		params.mapHeight = number(intent, "map_h", mapEdge > 0 ? mapEdge : params.mapHeight);
		params.thumbWidth = clamp(params.thumbWidth);
		params.thumbHeight = clamp(params.thumbHeight);
		params.mapWidth = clamp(params.mapWidth);
		params.mapHeight = clamp(params.mapHeight);

		params.color = color(intent.getStringExtra("track_color"), ChizuTracks.DEFAULT_TRACK_COLOR);
		params.night = night(intent.getStringExtra("night"));
		params.show = flag(intent, "show");
		float density = decimal(intent, "density", app.getResources().getDisplayMetrics().density);
		params.density = Math.max(1f, Math.min(4f, density));
		return params;
	}

	// ---------- SHOW_TRACK ----------

	private void runShow(@NonNull OsmandApplication app, @NonNull ChizuReplier replier,
			@Nullable String trackId) {
		try {
			awaitInit(app);
			if (!ChizuTracks.prepareShow(app, trackId)) {
				replier.send("ERROR:unknown track: " + trackId);
				return;
			}
			// A receiver has no window, and since Android 10 an app without one may not start an
			// activity: this call is silently refused when 地図 is in the background, which is the
			// usual case. It costs nothing to try, and it does not matter — the target is pending,
			// so whoever brings the map up next lands on the track. The caller that has the
			// foreground should prefer ChizuShowTrackActivity, which has no such problem.
			app.runInUIThread(() -> MapActivity.launchMapActivityMoveToTop(app));
			replier.send("OK:" + trackId);
		} catch (Throwable error) {
			Log.e(TAG, "show failed", error);
			replier.send("ERROR:" + error.getClass().getSimpleName());
		}
	}

	// ---------- EXPORT_BASEMAP ----------

	private void runBasemap(@NonNull OsmandApplication app, @NonNull ChizuReplier replier,
			@NonNull ChizuBaseMap.Params params) {
		try {
			awaitInit(app);
			ChizuBaseMap.Result result = ChizuBaseMap.render(app, params);
			Bundle extras = new Bundle();
			boolean uriMode = params.outUri != null;
			extras.putString("out_path", uriMode ? "" : result.outPath);
			extras.putString("out_uri", uriMode ? result.outPath : "");
			extras.putString("width", String.valueOf(result.width));
			extras.putString("height", String.valueOf(result.height));
			extras.putString("map_detail", result.mapDetail);
			// the request echoed back, so a reply identifies its own cutout without being matched up
			extras.putString("zoom", String.valueOf(params.zoom));
			extras.putString("tile_x", String.valueOf(params.tileX));
			extras.putString("tile_y", String.valueOf(params.tileY));
			extras.putString("tiles_w", String.valueOf(params.tilesW));
			extras.putString("tiles_h", String.valueOf(params.tilesH));
			extras.putString("tile_px", String.valueOf(params.tilePx));

			replier.send("OK:" + result.outPath + "|" + result.width + "|" + result.height
					+ "|" + result.mapDetail, extras);
		} catch (ChizuTracks.TrackError error) {
			replier.send("ERROR:" + error.getMessage());
		} catch (Throwable error) {
			Log.e(TAG, "basemap failed", error);
			replier.send("ERROR:" + error.getClass().getSimpleName());
		}
	}

	@NonNull
	private ChizuBaseMap.Params readBasemap(@NonNull Intent intent) {
		ChizuBaseMap.Params params = new ChizuBaseMap.Params();
		params.zoom = number(intent, "zoom", 0);
		params.tileX = number(intent, "tile_x", -1);
		params.tileY = number(intent, "tile_y", -1);
		params.tilesW = number(intent, "tiles_w", 0);
		params.tilesH = number(intent, "tiles_h", 0);
		params.tilePx = number(intent, "tile_px", 256);
		params.outUri = ChizuUris.value(intent.getStringExtra("out_uri"));
		params.outPath = intent.getStringExtra("out_path");
		params.night = night(intent.getStringExtra("night"));
		return params;
	}

	// ---------- extras ----------

	/** Every value may arrive as a string: the sister apps send string extras only. */
	private int number(@NonNull Intent intent, @NonNull String key, int fallback) {
		Object value = intent.getExtras() != null ? intent.getExtras().get(key) : null;
		if (value instanceof Number number) {
			return number.intValue();
		}
		if (value instanceof String text) {
			try {
				return (int) Double.parseDouble(text.trim());
			} catch (NumberFormatException ignored) {
			}
		}
		return fallback;
	}

	private float decimal(@NonNull Intent intent, @NonNull String key, float fallback) {
		Object value = intent.getExtras() != null ? intent.getExtras().get(key) : null;
		if (value instanceof Number number) {
			return number.floatValue();
		}
		if (value instanceof String text) {
			try {
				return Float.parseFloat(text.trim());
			} catch (NumberFormatException ignored) {
			}
		}
		return fallback;
	}

	private boolean flag(@NonNull Intent intent, @NonNull String key) {
		Object value = intent.getExtras() != null ? intent.getExtras().get(key) : null;
		if (value instanceof Boolean bool) {
			return bool;
		}
		if (value instanceof String text) {
			String clean = text.trim();
			return "true".equalsIgnoreCase(clean) || "1".equals(clean) || "yes".equalsIgnoreCase(clean);
		}
		return false;
	}

	private int color(@Nullable String value, int fallback) {
		if (value == null || value.trim().isEmpty()) {
			return fallback;
		}
		try {
			return Color.parseColor(value.trim());
		} catch (IllegalArgumentException error) {
			Log.w(TAG, "unparseable track_color " + value + " — keeping the default");
			return fallback;
		}
	}

	/** Day unless told otherwise: a picture filed away for years must not drift with the clock. */
	@Nullable
	private Boolean night(@Nullable String value) {
		if (value == null || value.trim().isEmpty()) {
			return Boolean.FALSE;
		}
		String clean = value.trim().toLowerCase(Locale.US);
		if ("auto".equals(clean)) {
			return null;
		}
		return "night".equals(clean) || "true".equals(clean) || "1".equals(clean);
	}

	/** Empty, never null: an absent value in this contract is a string with nothing in it. */
	@NonNull
	private String text(@Nullable String value) {
		return value != null ? value : "";
	}

	private int clamp(int size) {
		return Math.max(64, Math.min(4096, size));
	}

	@NonNull
	private String decimal(double value) {
		return String.format(Locale.US, "%.1f", value);
	}

	/** A cold-started process must let the app finish loading before any of this works. */
	private void awaitInit(@NonNull OsmandApplication app) {
		long deadline = SystemClock.elapsedRealtime() + INIT_TIMEOUT_MS;
		while (app.isApplicationInitializing() && SystemClock.elapsedRealtime() < deadline) {
			try {
				Thread.sleep(200);
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}
}
