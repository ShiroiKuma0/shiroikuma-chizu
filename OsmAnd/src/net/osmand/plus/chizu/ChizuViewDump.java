package net.osmand.plus.chizu;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.auto.NavigationSession;

import java.util.Locale;

/**
 * 白い熊 地図: while the car screen has the map, the phone screen's bottom fifth is written to the
 * drive log every few seconds — each visible view with its id, class, bounds and background — but
 * only when it changed since the last time. That catches what leaves a black box at the bottom of
 * the phone during Android Auto without anyone having to look at the phone.
 */
public final class ChizuViewDump {

	private static final long INTERVAL_MS = 5_000;
	private static final int MAX_LINES = 60;

	private final MapActivity activity;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private boolean running;
	private String lastDump = "";

	private final Runnable tick = new Runnable() {
		@Override
		public void run() {
			if (!running) {
				return;
			}
			check();
			handler.postDelayed(this, INTERVAL_MS);
		}
	};

	public ChizuViewDump(@NonNull MapActivity activity) {
		this.activity = activity;
	}

	public void start() {
		if (!running) {
			running = true;
			handler.postDelayed(tick, INTERVAL_MS);
		}
	}

	public void stop() {
		running = false;
		handler.removeCallbacks(tick);
	}

	private void check() {
		OsmandApplication app = activity.getApp();
		NavigationSession session = app.getCarNavigationSession();
		if (session == null || !session.hasStarted()) {
			lastDump = "";
			return;
		}
		View root = activity.getWindow().getDecorView();
		int height = root.getHeight();
		if (height == 0) {
			return;
		}
		StringBuilder sb = new StringBuilder();
		int[] lines = {0};
		walk(root, 0, height * 4 / 5, sb, lines);
		String dump = sb.toString();
		if (!dump.equals(lastDump)) {
			lastDump = dump;
			ChizuDriveLog.log("car screen active: phone bottom views (planning=" + app.getRoutingHelper().isRoutePlanningMode()
					+ ", following=" + app.getRoutingHelper().isFollowingMode() + ")\n" + dump);
		}
	}

	private void walk(@NonNull View v, int depth, int fromY, @NonNull StringBuilder sb, int[] lines) {
		if (v.getVisibility() != View.VISIBLE || v.getWidth() == 0 || v.getHeight() == 0 || lines[0] >= MAX_LINES) {
			return;
		}
		int[] at = new int[2];
		v.getLocationOnScreen(at);
		int bottom = at[1] + v.getHeight();
		if (bottom > fromY) {
			lines[0]++;
			sb.append(String.format(Locale.US, "    %s%s %s [%d,%d][%d,%d]%s%s\n", indent(depth),
					v.getClass().getSimpleName(), idName(v), at[0], at[1], at[0] + v.getWidth(), bottom,
					background(v.getBackground()), v.getAlpha() < 1f ? " alpha=" + v.getAlpha() : ""));
		}
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++) {
				walk(g.getChildAt(i), depth + 1, fromY, sb, lines);
			}
		}
	}

	@NonNull
	private static String indent(int depth) {
		StringBuilder s = new StringBuilder();
		for (int i = 0; i < Math.min(depth, 20); i++) {
			s.append(' ');
		}
		return s.toString();
	}

	@NonNull
	private static String idName(@NonNull View v) {
		if (v.getId() == View.NO_ID) {
			return "-";
		}
		try {
			return v.getResources().getResourceEntryName(v.getId());
		} catch (Exception e) {
			return "#" + Integer.toHexString(v.getId());
		}
	}

	@NonNull
	private static String background(@Nullable Drawable d) {
		if (d == null) {
			return "";
		}
		if (d instanceof ColorDrawable) {
			return String.format(Locale.US, " bg=#%08X", ((ColorDrawable) d).getColor());
		}
		return " bg=" + d.getClass().getSimpleName();
	}
}
