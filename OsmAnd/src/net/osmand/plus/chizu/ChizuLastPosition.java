package net.osmand.plus.chizu;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.plus.simulation.SimulationProvider;

import java.util.Locale;

/**
 * 白い熊 地図 +088: the last position, kept across app starts (白い熊, 2026-11-18: “before this update, sitting
 * indoors I saw the circle position — now nothing”). The system's network locator had simply not answered
 * that time, and a freshly started 地図 knew no older position to show. Now the last one is written down as
 * positions come and shown as the grey ring until a current one replaces it.
 */
public final class ChizuLastPosition {

	public static final String PROVIDER = "last";
	private static final String KEY = "last_position";
	private static final long EVERY_MS = 20_000;
	private static long notedMs;

	private ChizuLastPosition() {
	}

	private static SharedPreferences prefs(@NonNull Context c) {
		return c.getApplicationContext().getSharedPreferences(ChizuTheme.PREFS_NAME, Context.MODE_PRIVATE);
	}

	/** A position was shown: written down at most every {@value #EVERY_MS} ms. Estimates are not kept. */
	public static void note(@NonNull Context c, @Nullable Location l) {
		long now = SystemClock.elapsedRealtime();
		if (l == null || now - notedMs < EVERY_MS || SimulationProvider.isTunnelLocationSimulated(l)
				|| PROVIDER.equals(l.getProvider())) {
			return;
		}
		notedMs = now;
		prefs(c).edit().putString(KEY, String.format(Locale.US, "%.6f,%.6f,%d", l.getLatitude(), l.getLongitude(),
				System.currentTimeMillis())).apply();
	}

	@Nullable
	public static Location load(@NonNull Context c) {
		String s = prefs(c).getString(KEY, null);
		if (s == null) {
			return null;
		}
		try {
			String[] p = s.split(",");
			Location l = new Location(PROVIDER);
			l.setLatitude(Double.parseDouble(p[0]));
			l.setLongitude(Double.parseDouble(p[1]));
			l.setTime(Long.parseLong(p[2]));
			return l;
		} catch (Exception e) {
			return null;
		}
	}
}
