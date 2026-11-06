package net.osmand.plus.chizu;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.slider.Slider;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.util.Algorithms;

import java.util.Locale;

/**
 * 白い熊 地図 +045: the overlay can no longer vanish unnoticed (白い熊, 2026-11-06: “manipulating
 * something in the car I move it to 0 and don't notice”). OsmAnd's on-map transparency slider sits at
 * the bottom of the phone map and takes any swipe across it; at 0 the satellite overlay is invisible and
 * the vector map turns opaque, with nothing to say so.
 * <ol>
 * <li><b>Locked slider.</b> It shows, dimmed, and ignores touches until long-pressed; then it is live
 * until {@value #UNLOCK_MS} ms after the last touch.</li>
 * <li><b>Warning.</b> An overlay set but under {@value #LOW_PCT} % opacity is said on the car screen and
 * the phone — on every such change and on every car-screen start.</li>
 * <li><b>Restored on Go.</b> Starting navigation with the overlay that low puts back the last opacity
 * at or above {@value #LOW_PCT} %, and says so.</li>
 * </ol>
 * Every change of the overlay opacity is written to the drive log with what made it.
 */
public final class ChizuOverlayGuard {

	private static final int LOW_PCT = 15;
	private static final int LOW = 255 * LOW_PCT / 100;
	private static final long UNLOCK_MS = 10_000;
	private static final float LOCKED_ALPHA = 0.45f;
	private static final String PREFS = "chizu_overlay_guard";
	private static final String GOOD = "good_";

	private static OsmandApplication app;
	@Nullable
	private static String pendingSource;
	// held here: OsmAnd keeps preference listeners only as weak references
	private static net.osmand.StateChangedListener<Integer> opacityListener;
	private static net.osmand.StateChangedListener<String> overlayListener;

	private ChizuOverlayGuard() {
	}

	public static void init(@NonNull OsmandApplication application) {
		app = application;
		OsmandSettings st = app.getSettings();
		opacityListener = ChizuOverlayGuard::onChanged;
		st.MAP_OVERLAY_TRANSPARENCY.addListener(opacityListener);
		overlayListener = value -> {
			ChizuDriveLog.log("overlay " + (value == null ? "removed" : "set to " + value) + " in "
					+ st.getApplicationMode().getStringKey() + " by " + ChizuDriveLog.caller());
			app.runInUIThread(() -> check("overlay chosen"));
		};
		st.MAP_OVERLAY.addListener(overlayListener);
	}

	private static SharedPreferences prefs() {
		return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}

	private static void onChanged(@Nullable Integer value) {
		if (app == null || value == null) {
			return;
		}
		OsmandSettings st = app.getSettings();
		String mode = st.getApplicationMode().getStringKey();
		String source = pendingSource != null ? pendingSource : ChizuDriveLog.caller();
		pendingSource = null;
		ChizuDriveLog.log(String.format(Locale.US, "overlay opacity %d/255 (%d %%) in %s by %s",
				value, pct(value), mode, source));
		if (value >= LOW) {
			prefs().edit().putInt(GOOD + mode, value).apply();
		} else {
			app.runInUIThread(() -> check("opacity changed"));
		}
	}

	private static int pct(int value) {
		return Math.round(value * 100f / 255);
	}

	private static boolean low(@NonNull OsmandSettings st) {
		return !Algorithms.isEmpty(st.MAP_OVERLAY.get()) && st.MAP_OVERLAY_TRANSPARENCY.get() < LOW;
	}

	/** Says so on the car screen and the phone when the overlay is set but next to invisible. */
	public static void check(@NonNull String why) {
		if (app == null) {
			return;
		}
		OsmandSettings st = app.getSettings();
		if (!low(st)) {
			return;
		}
		int pct = pct(st.MAP_OVERLAY_TRANSPARENCY.get());
		ChizuDriveLog.log("overlay warning (" + why + "): " + st.MAP_OVERLAY.get() + " at " + pct + " %");
		app.getToastHelper().showToast(app.getString(R.string.chizu_overlay_hidden, pct), true);
	}

	/** On Go: an overlay left next to invisible gets its last good opacity back. */
	public static void onNavigationStart() {
		if (app == null) {
			return;
		}
		OsmandSettings st = app.getSettings();
		if (!low(st)) {
			return;
		}
		ApplicationMode mode = st.getApplicationMode();
		int good = prefs().getInt(GOOD + mode.getStringKey(), -1);
		int was = st.MAP_OVERLAY_TRANSPARENCY.get();
		if (good < LOW) {
			ChizuDriveLog.log("overlay at " + pct(was) + " % on Go, no earlier opacity to restore");
			check("navigation started");
			return;
		}
		pendingSource = "restore on Go";
		st.MAP_OVERLAY_TRANSPARENCY.set(good);
		app.getOsmandMap().refreshMap(true);
		app.getToastHelper().showToast(app.getString(R.string.chizu_overlay_restored, pct(was), pct(good)), true);
	}

	/**
	 * Locks the on-map transparency slider: dimmed and deaf to touches until long-pressed, then live
	 * until {@value #UNLOCK_MS} ms after the last touch.
	 */
	@SuppressLint("ClickableViewAccessibility")
	public static void lock(@NonNull Slider slider) {
		Handler handler = new Handler(Looper.getMainLooper());
		boolean[] unlocked = {false};
		Runnable relock = () -> {
			if (unlocked[0]) {
				unlocked[0] = false;
				slider.setAlpha(LOCKED_ALPHA);
				pendingSource = null;
				ChizuDriveLog.log("overlay slider locked again");
			}
		};
		GestureDetector detector = new GestureDetector(slider.getContext(), new GestureDetector.SimpleOnGestureListener() {
			@Override
			public boolean onDown(@NonNull MotionEvent e) {
				return true;
			}

			@Override
			public void onLongPress(@NonNull MotionEvent e) {
				if (!unlocked[0]) {
					unlocked[0] = true;
					slider.setAlpha(1f);
					slider.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
					ChizuDriveLog.log("overlay slider unlocked by long press");
					handler.removeCallbacks(relock);
					handler.postDelayed(relock, UNLOCK_MS);
				}
			}
		});
		slider.setAlpha(LOCKED_ALPHA);
		slider.setOnTouchListener((v, event) -> {
			detector.onTouchEvent(event);
			if (!unlocked[0]) {
				return true; // locked: the slider never sees the touch
			}
			pendingSource = "on-map slider (unlocked)";
			handler.removeCallbacks(relock);
			handler.postDelayed(relock, UNLOCK_MS);
			return false;
		});
	}
}
