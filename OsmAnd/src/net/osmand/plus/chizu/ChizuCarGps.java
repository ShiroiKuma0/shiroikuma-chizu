package net.osmand.plus.chizu;

import android.location.Location;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.hardware.CarHardwareManager;
import androidx.car.app.hardware.common.CarValue;
import androidx.car.app.hardware.common.OnCarDataAvailableListener;
import androidx.car.app.hardware.info.CarHardwareLocation;
import androidx.car.app.hardware.info.CarSensors;
import androidx.core.content.ContextCompat;

import java.util.Locale;

/**
 * 白い熊 地図 +083: a probe — does the car hand its own position to the phone through Android Auto?
 * <p>
 * The phone's receiver gets no fix in the car without assistance (白い熊, 2026-11-18), and the car has an
 * antenna of its own. Android Auto can pass the car's position to a navigation app (car API level 3);
 * whether a head unit does is up to its maker.
 * <p>
 * +085: 白い熊's car does — on the probe drive of 2026-11-18 it answered once a second for 18 minutes, 1.2–1.8 m
 * accurate, with speed and bearing, while the phone's receiver used no satellite at all. So <b>the car's
 * position is the position whenever the car gives one</b>: each answer goes to the location provider as a
 * custom location, which also keeps the phone's own fixes out for {@value #HOLD_PHONE_MS} ms; when the car
 * stops answering, the phone's fixes are taken again by themselves. Every tenth answer is logged
 * (“car gps …”), the fixes themselves as “loc ph car …”.
 */
public final class ChizuCarGps {

	private static final long LOG_EVERY_MS = 10_000;
	/** The provider name of a position that came from the car. */
	public static final String PROVIDER = "car";
	private static final long HOLD_PHONE_MS = 3000;
	private static volatile long lastFedMs;
	private static volatile net.osmand.Location lastLoc;
	private static net.osmand.plus.OsmandApplication app;

	private static CarSensors sensors;
	private static OnCarDataAvailableListener<CarHardwareLocation> listener;
	private static long lastLogMs;
	private static int lastStatus = -1;
	private static int count;

	private ChizuCarGps() {
	}

	/** The position shown is the car's: it gave one within the last {@value #HOLD_PHONE_MS} ms. */
	public static boolean active() {
		long last = lastFedMs;
		return last != 0 && SystemClock.elapsedRealtime() - last <= HOLD_PHONE_MS;
	}

	/** The car's last position while it is giving one, for the Wi-Fi locator to learn from; else null. */
	@androidx.annotation.Nullable
	public static net.osmand.Location lastGood() {
		return active() ? lastLoc : null;
	}

	public static void start(@NonNull CarContext context) {
		stop();
		app = (net.osmand.plus.OsmandApplication) context.getApplicationContext();
		try {
			int level = context.getCarAppApiLevel();
			if (level < 3) {
				ChizuDriveLog.log("car gps: the car's API level is " + level + ", positions need 3");
				return;
			}
			CarSensors s = context.getCarService(CarHardwareManager.class).getCarSensors();
			OnCarDataAvailableListener<CarHardwareLocation> l = ChizuCarGps::onData;
			s.addCarHardwareLocationListener(CarSensors.UPDATE_RATE_NORMAL, ContextCompat.getMainExecutor(context), l);
			sensors = s;
			listener = l;
			lastLogMs = 0;
			lastStatus = -1;
			count = 0;
			ChizuDriveLog.log("car gps: asked the car for its position (API level " + level + ")");
		} catch (Throwable e) {
			ChizuDriveLog.log("car gps: asking failed: " + e);
		}
	}

	public static void stop() {
		try {
			if (sensors != null && listener != null) {
				sensors.removeCarHardwareLocationListener(listener);
				ChizuDriveLog.log("car gps: stopped after " + count + " answers");
			}
		} catch (Throwable e) {
			ChizuDriveLog.log("car gps: stopping failed: " + e);
		}
		sensors = null;
		listener = null;
		lastFedMs = 0;
	}

	private static void feed(@NonNull Location l) {
		net.osmand.plus.OsmandApplication a = app;
		if (a == null) {
			return;
		}
		net.osmand.Location loc = net.osmand.plus.OsmAndLocationProvider.convertLocation(l, a);
		if (loc == null) {
			return;
		}
		loc.setProvider(PROVIDER);
		if (lastFedMs == 0 || !active()) {
			ChizuDriveLog.log("car gps: the car's position is the position now");
		}
		lastFedMs = SystemClock.elapsedRealtime();
		lastLoc = loc;
		a.getLocationProvider().setCustomLocation(loc, HOLD_PHONE_MS);
	}

	private static void onData(@NonNull CarHardwareLocation data) {
		count++;
		CarValue<Location> v = data.getLocation();
		int status = v.getStatus();
		long now = SystemClock.elapsedRealtime();
		if (status == CarValue.STATUS_SUCCESS && v.getValue() != null) {
			try {
				feed(v.getValue());
			} catch (Throwable e) {
				ChizuDriveLog.log("car gps: could not use the car's position: " + e);
			}
		} else if (lastFedMs != 0 && active() && status != lastStatus) {
			ChizuDriveLog.log("car gps: the car stopped giving its position, the phone's own is used again");
		}
		if (status == lastStatus && now - lastLogMs < LOG_EVERY_MS) {
			return;
		}
		lastStatus = status;
		lastLogMs = now;
		Location l = v.getValue();
		if (status == CarValue.STATUS_SUCCESS && l != null) {
			ChizuDriveLog.log(String.format(Locale.US, "car gps: %.6f,%.6f acc=%s spd=%s brg=%s provider=%s age=%dms (answer %d)",
					l.getLatitude(), l.getLongitude(), l.hasAccuracy() ? String.format(Locale.US, "%.1f", l.getAccuracy()) : "-",
					l.hasSpeed() ? String.format(Locale.US, "%.1f", l.getSpeed()) : "-",
					l.hasBearing() ? String.format(Locale.US, "%.0f", l.getBearing()) : "-", l.getProvider(),
					System.currentTimeMillis() - l.getTime(), count));
		} else {
			ChizuDriveLog.log("car gps: no position, status " + (status == CarValue.STATUS_UNIMPLEMENTED ? "unimplemented by the car"
					: status == CarValue.STATUS_UNAVAILABLE ? "unavailable" : status == CarValue.STATUS_UNKNOWN ? "unknown"
					: String.valueOf(status)) + " (answer " + count + ")");
		}
	}
}
