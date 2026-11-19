package net.osmand.plus.chizu;

import static android.location.LocationManager.GPS_PROVIDER;

import android.content.Context;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;

import java.util.Locale;

/**
 * 白い熊 地図 +082: satellite assistance at the start of a drive (白い熊, 2026-11-18).
 * <p>
 * That morning the receiver ran for a whole 16-minute drive without using one satellite: its last fix
 * was 16 hours old, it searched for 40 satellites from a stored list and heard none of them (the ones
 * overhead were not on it), and gave the list up only after 7 minutes. The phone drives in aeroplane
 * mode; its internet comes from a modem in the car that takes about a minute to start. OsmAnd asks the
 * system for assistance only when the phone's map screen opens, at most every 16 hours, and calls the
 * asking “A-GPS data downloaded” whether anything was or not.
 * <p>
 * So, when the car connects: nothing for {@value #FIRST_AFTER_MS} ms; then, as soon as there is a
 * checked internet connection, the system is asked for the predicted orbits and the time, and again
 * every {@value #AGAIN_MS} ms until the receiver has a fix. What came of it is measured, not assumed: Android reports for
 * each satellite whether the receiver holds its orbit, and that count is logged and shown before and
 * after. An app can do no more than ask; it cannot fetch the data and hand it over itself.
 * <p>
 * +083: <b>the receiver's data is never discarded.</b> +082 sent “delete_aiding_data” once when the data
 * was stale; with no extras that drops everything, and on the drive of 2026-11-18 14:33 the receiver's
 * list fell from 39 satellites to 3 — almanac gone, a blind search for the rest of the drive. And the
 * orbits are asked for under the name Android has used since version 10, “force_psds_injection”; the old
 * “force_xtra_injection” is an unknown command there and was silently ignored (it is still sent as well,
 * for Huawei's own receiver of it). On this phone neither is known to fetch anything: the framework has no
 * server configured for it, and the assistance that worked came from the receiver's own daemon.
 */
public final class ChizuAgps {

	private static final long TICK_MS = 10_000;
	private static final long FIRST_AFTER_MS = 30_000;
	private static final long AGAIN_MS = 60_000;
	private static final long STALE_MS = 4 * 3600_000L;
	private static final long GIVE_UP_MS = 30 * 60_000L;
	private static final int MSG_ID = net.osmand.plus.OsmAndConstants.UI_HANDLER_LOCATION_SERVICE + 33;

	private static boolean running;
	private static long startMs;
	private static long lastAskMs;
	private static int asked;
	private static boolean stale;
	private static boolean discarded;
	private static boolean waitingLogged;
	private static int ephAtFirstAsk = -1;
	/** Wall time of the last asking, 0 when never; for the “no position” message. */
	private static volatile long lastAskWallMs;

	private ChizuAgps() {
	}

	/** The car has connected. Main thread. */
	public static void onDriveStart(@NonNull OsmandApplication app) {
		if (running) {
			return;
		}
		long now = SystemClock.elapsedRealtime();
		if (ChizuGnssLog.fixWithin(5000)) {
			ChizuDriveLog.log("agps: the receiver has a fix at the car's start, nothing to ask for");
			return;
		}
		running = true;
		startMs = now;
		lastAskMs = 0;
		asked = 0;
		discarded = false;
		waitingLogged = false;
		ephAtFirstAsk = -1;
		long age = lastFixAgeMs(app);
		stale = age < 0 || age > STALE_MS;
		ChizuDriveLog.log(String.format(Locale.US, "agps: car connected, last satellite fix %s old (%s), %s",
				age < 0 ? "?" : age / 60_000 + " min", stale ? "stale" : "fresh", ChizuGnssLog.orbits()));
		app.runInUIThreadAndCancelPrevious(MSG_ID, () -> tick(app), TICK_MS);
	}

	private static void tick(@NonNull OsmandApplication app) {
		if (!running) {
			return;
		}
		long now = SystemClock.elapsedRealtime();
		if (ChizuCarGps.active()) {
			running = false;
			ChizuDriveLog.log("agps: the car gives its own position, nothing more to ask for (asked " + asked + " times)");
			return;
		}
		if (ChizuGnssLog.fixWithin(5000)) {
			running = false;
			String text = app.getString(R.string.chizu_agps_fix, (now - startMs) / 1000, ChizuGnssLog.orbits());
			ChizuDriveLog.log("agps: " + text + ", asked " + asked + " times" + (discarded ? ", old data discarded" : ""));
			if (asked > 0) {
				app.getToastHelper().showCarToast(text, true);
			}
			return;
		}
		if (now - startMs > GIVE_UP_MS || app.getCarNavigationSession() == null) {
			running = false;
			ChizuDriveLog.log("agps: stops without a fix, asked " + asked + " times, " + ChizuGnssLog.orbits());
			return;
		}
		if (now - startMs >= FIRST_AFTER_MS && (lastAskMs == 0 || now - lastAskMs >= AGAIN_MS)) {
			if (!online(app)) {
				if (!waitingLogged) {
					waitingLogged = true;
					ChizuDriveLog.log("agps: no checked internet connection yet, waiting");
					app.getToastHelper().showCarToast(app.getString(R.string.chizu_agps_waiting), true);
				}
			} else {
				waitingLogged = false;
				boolean discard = false; // +083: never
				if (ephAtFirstAsk < 0) {
					ephAtFirstAsk = ChizuGnssLog.ephemerides();
				}
				String before = ChizuGnssLog.orbits();
				ask(app, discard);
				discarded |= discard;
				asked++;
				lastAskMs = now;
				ChizuDriveLog.log(String.format(Locale.US, "agps: asked #%d%s after %d s without a fix, %s",
						asked, discard ? " (stale data discarded first)" : "", (now - startMs) / 1000, before));
				app.getToastHelper().showCarToast(app.getString(discard ? R.string.chizu_agps_discarded : R.string.chizu_agps_asked,
						asked, before), true);
			}
		}
		app.runInUIThreadAndCancelPrevious(MSG_ID, () -> tick(app), TICK_MS);
	}

	/** Asks the system for the predicted orbits and the time; {@code discard} drops the receiver's stored data first. */
	public static boolean ask(@NonNull OsmandApplication app, boolean discard) {
		try {
			LocationManager service = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
			Bundle bundle = new Bundle();
			service.sendExtraCommand(GPS_PROVIDER, "force_psds_injection", bundle);
			boolean a = service.sendExtraCommand(GPS_PROVIDER, "force_xtra_injection", bundle);
			boolean b = service.sendExtraCommand(GPS_PROVIDER, "force_time_injection", bundle);
			lastAskWallMs = System.currentTimeMillis();
			if (!a || !b) {
				ChizuDriveLog.log("agps: the system refused the asking (orbits " + a + ", time " + b + ")");
			}
			return a && b;
		} catch (Exception e) {
			ChizuDriveLog.log("agps: asking failed: " + e);
			return false;
		}
	}

	public static long lastAskWallMs() {
		return lastAskWallMs;
	}

	/** A connection the system has checked to reach the internet — the car's own Wi-Fi does not count. */
	public static boolean online(@NonNull OsmandApplication app) {
		try {
			ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
			Network n = cm.getActiveNetwork();
			NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
			return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
					&& c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
		} catch (Exception e) {
			return false;
		}
	}

	/** How old the system's last satellite fix is, -1 when unknown. */
	@SuppressWarnings("MissingPermission")
	private static long lastFixAgeMs(@NonNull OsmandApplication app) {
		try {
			LocationManager service = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
			android.location.Location l = service.getLastKnownLocation(GPS_PROVIDER);
			return l == null ? -1 : Math.max(0, System.currentTimeMillis() - l.getTime());
		} catch (Exception e) {
			return -1;
		}
	}
}
