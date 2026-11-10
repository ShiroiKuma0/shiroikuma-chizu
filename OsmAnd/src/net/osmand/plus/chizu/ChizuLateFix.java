package net.osmand.plus.chizu;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * 白い熊 地図 +067: fixes that reach the app late.
 * <p>
 * On 2026-11-09 the phone's location service held the satellite fixes back for 4–13 s at a time and then
 * handed over the whole backlog within a fraction of a second (ten fixes in 200 ms at 10:35:45). While
 * that lasted the car marker trailed 40–100 m behind the car, then jumped; and every fix of the backlog
 * was judged for leaving the route in turn, although only the newest one says where the car is.
 * <ol>
 * <li><b>Only the newest of a backlog.</b> A late fix ({@value #LATE_MS} ms or more) waits
 * {@value #SETTLE_MS} ms; one arriving meanwhile replaces it. A fix on time goes straight through and
 * drops any waiting one.</li>
 * <li><b>Moved ahead.</b> A late fix of a moving car is moved forward along its heading by speed × delay,
 * at most {@value #AHEAD_MAX_MS} ms worth, so the marker stands where the car is now; the snapping onto
 * the road then puts it back on the road.</li>
 * </ol>
 */
public final class ChizuLateFix {

	private static final long LATE_MS = 1500;
	private static final long SETTLE_MS = 150;
	private static final long AHEAD_FROM_MS = 1000;
	private static final long AHEAD_MAX_MS = 4000;
	private static final float AHEAD_MIN_SPEED = 3f;

	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Consumer<Location> sink;
	@Nullable
	private Location pending;
	private int dropped;
	private final Runnable flush = this::flush;

	public ChizuLateFix(@NonNull Consumer<Location> sink) {
		this.sink = sink;
	}

	/** How long ago the fix was taken, by the uptime clock; -1 when the fix does not say. */
	public static long ageMs(@NonNull Location l) {
		return l.chizuElapsedMs > 0 ? SystemClock.elapsedRealtime() - l.chizuElapsedMs : -1;
	}

	/** Called on the main thread with every fix, in the order they arrive. */
	public void offer(@NonNull Location l) {
		long age = ageMs(l);
		if (age < LATE_MS) {
			if (pending != null) {
				ChizuDriveLog.log("loc late: " + (dropped + 1) + " waiting fix(es) dropped, an on-time fix came");
				pending = null;
				dropped = 0;
				handler.removeCallbacks(flush);
			}
			sink.accept(ahead(l, age));
			return;
		}
		if (pending != null) {
			dropped++;
		}
		pending = l;
		handler.removeCallbacks(flush);
		handler.postDelayed(flush, SETTLE_MS);
	}

	private void flush() {
		Location l = pending;
		if (l == null) {
			return;
		}
		pending = null;
		long age = ageMs(l);
		ChizuDriveLog.log(String.format(Locale.US, "loc late %d ms: newest of %d used", age, dropped + 1));
		dropped = 0;
		sink.accept(ahead(l, age));
	}

	/** The fix moved forward to where a car going on at its speed and heading is now. */
	@NonNull
	static Location ahead(@NonNull Location l, long age) {
		if (age < AHEAD_FROM_MS || !l.hasSpeed() || !l.hasBearing() || l.getSpeed() < AHEAD_MIN_SPEED) {
			return l;
		}
		double m = l.getSpeed() * Math.min(age, AHEAD_MAX_MS) / 1000.0;
		double brg = Math.toRadians(l.getBearing());
		double lat = l.getLatitude() + Math.toDegrees(m * Math.cos(brg) / 6_371_000.0);
		double lon = l.getLongitude() + Math.toDegrees(m * Math.sin(brg)
				/ (6_371_000.0 * Math.cos(Math.toRadians(l.getLatitude()))));
		Location moved = new Location(l);
		moved.setLatitude(lat);
		moved.setLongitude(lon);
		ChizuDriveLog.log(String.format(Locale.US, "loc late %d ms: moved %.0f m ahead", age, m));
		return moved;
	}
}
