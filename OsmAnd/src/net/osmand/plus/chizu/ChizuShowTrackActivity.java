package net.osmand.plus.chizu;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;

/**
 * shiroikuma fork: "open this walk in 地図", as one intent.
 *
 * <pre>
 * &lt;pkg&gt;.action.OPEN_TRACK   track_id → the map opens on that track
 * </pre>
 *
 * The broadcast {@code SHOW_TRACK} can do everything except the last step: a receiver has no
 * window, and since Android 10 an app without one may not start an activity, so 地図 was asked to
 * bring itself to the front from the background and was silently refused. Here the caller has the
 * foreground and hands it to us; we hold it — invisibly — while the track is found, and only then
 * start the map, which is an ordinary foreground start with nothing timed and nothing raced.
 *
 * No token. This does nothing a launcher icon cannot: it opens the app and chooses which track is
 * on screen. Gating it would buy no safety and would break 白い熊's button silently every time the
 * token drifted. The token still guards {@link ChizuTrackReceiver} — the headless work.
 */
public class ChizuShowTrackActivity extends Activity {

	private static final String TAG = "ChizuAutomation";

	private static final String EXTRA_TRACK_ID = "track_id";
	private static final long INIT_TIMEOUT_MS = 180_000;

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		OsmandApplication app = (OsmandApplication) getApplication();
		Intent intent = getIntent();
		String trackId = intent != null ? intent.getStringExtra(EXTRA_TRACK_ID) : null;

		// off the main thread: a cold start has to finish initializing before a track can be found
		new Thread(() -> {
			awaitInit(app);
			boolean found = ChizuTracks.prepareShow(app, trackId);
			runOnUiThread(() -> openMap(found, trackId));
		}, "chizu-open-track").start();
	}

	private void openMap(boolean found, @Nullable String trackId) {
		if (isFinishing() || isDestroyed()) {
			return;
		}
		if (!found) {
			// still open the map: 白い熊 pressed a button, and a button that does nothing is what
			// this whole action exists to stop. The toast says why the walk is not on it.
			Log.w(TAG, "unknown track: " + trackId);
			Toast.makeText(this, R.string.chizu_track_not_found, Toast.LENGTH_LONG).show();
		}
		MapActivity.launchMapActivityMoveToTop(this);
		finish();
	}

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
