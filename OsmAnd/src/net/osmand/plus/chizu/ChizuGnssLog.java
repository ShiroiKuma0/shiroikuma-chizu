package net.osmand.plus.chizu;

import android.content.Context;
import android.location.GnssStatus;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;

import java.util.Arrays;
import java.util.Locale;

/**
 * 白い熊 地図 raw satellite log (Diagnostics → “Raw satellite fixes”, lines “gnss …” in the drive log).
 * <p>
 * Every fix the receiver hands over, BEFORE 地図 filters it, with what became of it (kept, taken as
 * a poor fix, dropped), and the sky behind it: satellites used and in view, the four strongest
 * signals, and every few seconds the whole sky — each satellite's system, number, elevation,
 * azimuth, signal and whether it was used. Written to tell a valley hiding the low satellites (few
 * low ones on two sides, accuracy above 50 m) from a receiver that stops altogether (“engine
 * stopped”, no fixes) from 地図 itself throwing good fixes away.
 */
public final class ChizuGnssLog {

	private static final long SKY_EVERY_MS = 5000;

	private static volatile int used;
	private static volatile int inView;
	private static volatile float top4;
	private static volatile long lastStatusMs;
	private static long lastSkyMs;
	private static int lastSkyUsed = -1;
	private static volatile long lastFixMs;

	private ChizuGnssLog() {
	}

	public static boolean on() {
		return ChizuDiag.isOn(ChizuDiag.Group.GNSS);
	}

	public static void onStarted() {
		ChizuDriveLog.log("gnss engine started");
	}

	public static void onStopped() {
		ChizuDriveLog.log("gnss engine stopped");
	}

	public static void onFirstFix(int ttffMs) {
		ChizuDriveLog.log("gnss first fix after " + ttffMs + " ms");
	}

	public static void onStatus(@NonNull GnssStatus status) {
		int n = status.getSatelliteCount();
		int u = 0;
		float[] cn0 = new float[n];
		for (int i = 0; i < n; i++) {
			if (status.usedInFix(i)) {
				u++;
			}
			cn0[i] = status.getCn0DbHz(i);
		}
		Arrays.sort(cn0);
		float sum = 0;
		int k = 0;
		for (int i = n - 1; i >= 0 && k < 4; i--, k++) {
			sum += cn0[i];
		}
		used = u;
		inView = n;
		top4 = k == 0 ? 0 : sum / k;
		long now = SystemClock.elapsedRealtime();
		lastStatusMs = now;
		if (!on()) {
			return;
		}
		if (now - lastSkyMs < SKY_EVERY_MS && u == lastSkyUsed) {
			return;
		}
		lastSkyMs = now;
		lastSkyUsed = u;
		// the sky: lowest first, so the valley walls show as missing or unused low satellites
		Integer[] order = new Integer[n];
		for (int i = 0; i < n; i++) {
			order[i] = i;
		}
		Arrays.sort(order, (a, b) -> Float.compare(status.getElevationDegrees(a), status.getElevationDegrees(b)));
		StringBuilder sb = new StringBuilder(64 + n * 20);
		sb.append(String.format(Locale.US, "gnss sky used=%d/%d top4=%.1f", u, n, top4));
		int low = 0, lowUsed = 0;
		for (Integer i : order) {
			float el = status.getElevationDegrees(i);
			if (el < 30) {
				low++;
				if (status.usedInFix(i)) {
					lowUsed++;
				}
			}
		}
		sb.append(" below30=").append(lowUsed).append('/').append(low).append(" |");
		for (Integer i : order) {
			sb.append(' ').append(system(status.getConstellationType(i))).append(status.getSvid(i))
					.append(String.format(Locale.US, ":e%.0f/a%.0f/%.0f", status.getElevationDegrees(i),
							status.getAzimuthDegrees(i), status.getCn0DbHz(i)));
			if (android.os.Build.VERSION.SDK_INT >= 26 && status.hasCarrierFrequencyHz(i) && status.getCarrierFrequencyHz(i) < 1.3e9f) {
				sb.append("L5");
			}
			if (status.usedInFix(i)) {
				sb.append('*');
			}
		}
		ChizuDriveLog.log(sb.toString());
	}

	/**
	 * A fix as the receiver gave it, before any filter.
	 *
	 * @param verdict “kept”, “poor” (taken although worse than 50 m), “dropped” (thrown away),
	 *                or “net-ignored” for a network fix while navigating
	 */
	public static void onFix(@Nullable Context context, @NonNull Location l, @NonNull String verdict) {
		long now = SystemClock.elapsedRealtime();
		long gap = lastFixMs == 0 ? -1 : now - lastFixMs;
		if (!"net-ignored".equals(verdict)) {
			lastFixMs = now;
		}
		if (!on()) {
			return;
		}
		boolean screen = true;
		if (context != null) {
			PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
			screen = pm == null || pm.isInteractive();
		}
		ChizuDriveLog.log(String.format(Locale.US,
				"gnss fix %s %s %.6f,%.6f acc=%.1f spd=%.1f brg=%s used=%d/%d top4=%.1f since=%dms status_age=%dms screen=%s",
				verdict, l.getProvider(), l.getLatitude(), l.getLongitude(),
				l.hasAccuracy() ? l.getAccuracy() : -1f, l.hasSpeed() ? l.getSpeed() : -1f,
				l.hasBearing() ? String.format(Locale.US, "%.0f", l.getBearing()) : "-",
				used, inView, top4, gap, lastStatusMs == 0 ? -1 : now - lastStatusMs, screen ? "on" : "off"));
	}

	private static final long EXTRAS_EVERY_MS = 30_000;
	private static final float EXTRAS_FROM_ACC = 30f;
	private static long lastExtrasMs;

	/**
	 * +075: what EMUI's “fused” engine says about a fix worse than {@value #EXTRAS_FROM_ACC} m — its extras,
	 * speed and bearing accuracy — once every {@value #EXTRAS_EVERY_MS} ms. On 2026-11-13 the engine
	 * dead-reckoned through every loss of the satellites with the accuracy capped at 55 m; whether it
	 * says so anywhere is the question this answers.
	 */
	public static void onExtras(@NonNull android.location.Location l) {
		if (!on() || !l.hasAccuracy() || l.getAccuracy() <= EXTRAS_FROM_ACC) {
			return;
		}
		long now = SystemClock.elapsedRealtime();
		if (now - lastExtrasMs < EXTRAS_EVERY_MS) {
			return;
		}
		lastExtrasMs = now;
		StringBuilder sb = new StringBuilder(128);
		sb.append(String.format(Locale.US, "gnss extras %s acc=%.1f", l.getProvider(), l.getAccuracy()));
		if (android.os.Build.VERSION.SDK_INT >= 26) {
			sb.append(" spd_acc=").append(l.hasSpeedAccuracy() ? String.format(Locale.US, "%.1f", l.getSpeedAccuracyMetersPerSecond()) : "-");
			sb.append(" brg_acc=").append(l.hasBearingAccuracy() ? String.format(Locale.US, "%.0f", l.getBearingAccuracyDegrees()) : "-");
			sb.append(" vert_acc=").append(l.hasVerticalAccuracy() ? String.format(Locale.US, "%.0f", l.getVerticalAccuracyMeters()) : "-");
		}
		if (android.os.Build.VERSION.SDK_INT >= 31) {
			sb.append(" mock=").append(l.isMock());
		}
		android.os.Bundle b = l.getExtras();
		if (b == null || b.isEmpty()) {
			sb.append(" extras=none");
		} else {
			for (String k : b.keySet()) {
				Object v = b.get(k);
				sb.append(' ').append(k).append('=').append(v instanceof Object[] ? Arrays.toString((Object[]) v) : v);
			}
		}
		ChizuDriveLog.log(sb.toString());
	}

	@NonNull
	private static String system(int type) {
		switch (type) {
			case GnssStatus.CONSTELLATION_GPS:
				return "G";
			case GnssStatus.CONSTELLATION_GLONASS:
				return "R";
			case GnssStatus.CONSTELLATION_GALILEO:
				return "E";
			case GnssStatus.CONSTELLATION_BEIDOU:
				return "C";
			case GnssStatus.CONSTELLATION_QZSS:
				return "J";
			case GnssStatus.CONSTELLATION_SBAS:
				return "S";
			case GnssStatus.CONSTELLATION_IRNSS:
				return "I";
			default:
				return "?";
		}
	}
}
