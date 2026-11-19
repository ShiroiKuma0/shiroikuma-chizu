package net.osmand.plus.chizu;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;

import java.util.Locale;

/**
 * 白い熊 地図 +092: keeping the satellite receiver's orbits from going stale (白い熊, 2026-11-19: “we need to
 * make it so that it never starves again”).
 * <p>
 * The receiver reads each satellite's orbit off the air; that takes a strong signal — about 27 dB-Hz and
 * more — held for half a minute, and an orbit is good for a few hours. Its own assistance download has been
 * silent since the SIM went, so off the air is all there is. The log of 2026-11-18 shows what kept it
 * without a fix for a day: thirteen- and eighteen-minute drives at 19 dB-Hz (too weak, however long), and
 * sessions at 33–41 dB-Hz that 地図 ended after 27 and 46 seconds (strong, too short). The first session both
 * strong and long enough, 107 s at 31 dB-Hz, fixed in 28 s.
 * <p>
 * So this small foreground service keeps the receiver on a little longer, in two cases:
 * <ul>
 * <li><b>Finishing a fix</b> ({@link #REASON_FINISH}): 地図 leaves the screen with no fix yet while at least
 * {@value #STRONG_NEEDED} satellites are strong — it stays up to {@value #FINISH_MAX_MS} ms, until the fix
 * and {@value #AFTER_FIX_MS} ms more for further orbits. It would have fixed at 15:46 and at 22:30.</li>
 * <li><b>Keeping fresh</b> ({@link #REASON_FRESH}), when switched on: every {@value #FRESH_EVERY_MS} ms an
 * exact alarm wakes it. If the last satellite fix is younger than {@value #FRESH_SKIP_MS} ms it does
 * nothing; if after {@value #PROBE_MS} ms fewer than {@value #STRONG_NEEDED} satellites are strong it stops
 * (the phone lies where there is no signal, and waiting would only cost battery); else it stays until a fix
 * and a little longer. This needs the location permission “Allow all the time”: a service started while
 * 地図 is not on screen gets no satellite data without it.</li>
 * </ul>
 * It gives no guarantee: where the phone lies in a weak signal nothing an app does brings orbits in — an
 * app cannot hand them to the receiver.
 * <p>
 * <b>That was tried and is closed</b> (+093 – +099, removed in +100). 地図 fetched the current broadcast
 * ephemeris from Huawei's open HiGeo address (7.5 kB of RTCM 3, orbits for 115 satellites) and passed it
 * through the one door an app can reach — the extra command “download_aiding_data” with “ePoData”, which
 * Huawei's provider hands to {@code injectPsdsData}. On 2026-11-19 12:28:33 the system answered in its own
 * log: {@code inject_psds_data: IGnssPsdsAidl or IGnssXtra interface not available} — this receiver's HAL
 * has no such interface, so nothing passed that way ever reaches it. Nor can an app empty the receiver:
 * “delete_aiding_data”, with the ephemeris flag or with none, running or stopped, changed nothing four
 * times that day. What is left: the daemon's own assistance (silent since the SIM went), the air, and in
 * the car the car's own GPS.
 */
public class ChizuFixService extends Service {

	public static final String REASON_FINISH = "finish";
	public static final String REASON_FRESH = "fresh";
	private static final String EXTRA_REASON = "reason";
	private static final String KEY_FINISH = "gnss_finish_fix";
	private static final String KEY_FRESH = "gnss_keep_fresh";
	private static final String CHANNEL = "chizu_fix";
	private static final int NOTIFICATION_ID = 79101;
	private static final int STRONG_NEEDED = 4;
	private static final long FINISH_MAX_MS = 90_000;
	private static final long FRESH_MAX_MS = 120_000;
	private static final long AFTER_FIX_MS = 30_000;
	private static final long PROBE_MS = 15_000;
	private static final long WEAK_MS = 15_000;
	private static final long FRESH_EVERY_MS = 90 * 60_000L;
	private static final long FRESH_SKIP_MS = 60 * 60_000L;

	private static volatile boolean running;

	private final Handler handler = new Handler(Looper.getMainLooper());
	private String reason = REASON_FINISH;
	private long startMs;
	private long fixSinceMs;
	private long weakSinceMs;
	private LocationListener listener;

	// ---------- switches ----------

	private static SharedPreferences prefs(@NonNull Context c) {
		return c.getApplicationContext().getSharedPreferences(ChizuTheme.PREFS_NAME, Context.MODE_PRIVATE);
	}

	public static boolean isFinishOn(@NonNull Context c) {
		return prefs(c).getBoolean(KEY_FINISH, true);
	}

	public static void setFinishOn(@NonNull Context c, boolean on) {
		prefs(c).edit().putBoolean(KEY_FINISH, on).apply();
	}

	public static boolean isFreshOn(@NonNull Context c) {
		return prefs(c).getBoolean(KEY_FRESH, false);
	}

	public static void setFreshOn(@NonNull Context c, boolean on) {
		prefs(c).edit().putBoolean(KEY_FRESH, on).apply();
		ChizuDriveLog.log("orbit refresh every 90 min switched " + (on ? "on" : "off"));
		arm(c);
	}

	/** “Allow all the time” is granted — without it the refresh gets no satellite data in the background. */
	public static boolean hasBackgroundLocation(@NonNull Context c) {
		return Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(c,
				android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
	}

	// ---------- starting ----------

	/** 地図 leaves the screen: stay with the receiver if a fix is within reach. Called while still in the foreground. */
	public static void finishIfWorthIt(@NonNull OsmandApplication app) {
		if (running || !isFinishOn(app) || app.getCarNavigationSession() != null || app.getRoutingHelper().isFollowingMode()
				|| ChizuGnssLog.fixWithin(5000) || ChizuGnssLog.strongCount() < STRONG_NEEDED) {
			return;
		}
		start(app, REASON_FINISH);
	}

	private static void start(@NonNull Context c, @NonNull String reason) {
		try {
			Intent intent = new Intent(c, ChizuFixService.class).putExtra(EXTRA_REASON, reason);
			ContextCompat.startForegroundService(c, intent);
		} catch (Exception e) {
			ChizuDriveLog.log("orbit refresh (" + reason + ") could not start: " + e);
		}
	}

	/** 地図 is on the screen again and runs the receiver itself. */
	public static void stopNow(@NonNull Context c) {
		if (running) {
			c.stopService(new Intent(c, ChizuFixService.class));
		}
	}

	/** Sets, or clears, the alarm for the next refresh. Called at every app start and when the switch changes. */
	public static void arm(@NonNull Context c) {
		try {
			AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
			PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(c, Alarm.class),
					PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
			alarms.cancel(pi);
			if (!isFreshOn(c)) {
				return;
			}
			long at = SystemClock.elapsedRealtime() + FRESH_EVERY_MS;
			if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
				// an exact alarm is what lets the service start while 地図 is not on the screen
				alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
			} else {
				alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
				ChizuDriveLog.log("orbit refresh: exact alarms are not allowed, the refresh may not start in the background");
			}
		} catch (Exception e) {
			ChizuDriveLog.log("orbit refresh: the alarm could not be set: " + e);
		}
	}

	/** The alarm of the 90-minute refresh. */
	public static class Alarm extends BroadcastReceiver {
		@Override
		public void onReceive(Context c, Intent intent) {
			arm(c);
			OsmandApplication app = (OsmandApplication) c.getApplicationContext();
			long age = System.currentTimeMillis() - ChizuFix.lastSatMs(c);
			if (!isFreshOn(c)) {
				return;
			}
			if (running || app.getCarNavigationSession() != null || app.isAppInForegroundOnRootDevice()) {
				ChizuDriveLog.log("orbit refresh skipped: 地図 is in use");
			} else if (age >= 0 && age < FRESH_SKIP_MS) {
				ChizuDriveLog.log("orbit refresh skipped: the last satellite fix is " + age / 60_000 + " min old");
			} else if (!hasBackgroundLocation(c)) {
				ChizuDriveLog.log("orbit refresh skipped: the location permission is not “Allow all the time”");
			} else {
				start(c, REASON_FRESH);
			}
		}
	}

	// ---------- the service ----------

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@SuppressLint("MissingPermission")
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		String r = intent == null ? null : intent.getStringExtra(EXTRA_REASON);
		reason = REASON_FRESH.equals(r) ? REASON_FRESH : REASON_FINISH;
		try {
			Notification n = notification();
			if (Build.VERSION.SDK_INT >= 29) {
				startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
			} else {
				startForeground(NOTIFICATION_ID, n);
			}
		} catch (Exception e) {
			ChizuDriveLog.log("orbit refresh (" + reason + ") was refused the foreground: " + e);
			stopSelf();
			return START_NOT_STICKY;
		}
		if (running) {
			return START_NOT_STICKY;
		}
		running = true;
		startMs = SystemClock.elapsedRealtime();
		fixSinceMs = 0;
		weakSinceMs = 0;
		OsmandApplication app = (OsmandApplication) getApplicationContext();
		try {
			app.getLocationProvider().chizuGnssStatusForFix(true);
			listener = new LocationListener() {
				@Override
				public void onLocationChanged(@NonNull Location l) {
					ChizuFix.noteSat(ChizuFixService.this);
					ChizuLastPosition.note(ChizuFixService.this,
							net.osmand.plus.OsmAndLocationProvider.convertLocation(l, app));
				}

				@Override
				public void onStatusChanged(String provider, int status, Bundle extras) {
				}

				@Override
				public void onProviderEnabled(@NonNull String provider) {
				}

				@Override
				public void onProviderDisabled(@NonNull String provider) {
				}
			};
			LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
			lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, listener, Looper.getMainLooper());
		} catch (Exception e) {
			end("the receiver could not be asked: " + e);
			return START_NOT_STICKY;
		}
		ChizuDriveLog.log("orbit refresh (" + reason + ") starts, " + ChizuGnssLog.orbits());
		handler.postDelayed(this::tick, 2000);
		return START_NOT_STICKY;
	}

	private void tick() {
		if (!running) {
			return;
		}
		long now = SystemClock.elapsedRealtime();
		long ran = now - startMs;
		boolean fresh = REASON_FRESH.equals(reason);
		if (ChizuGnssLog.fixWithin(4000)) {
			if (fixSinceMs == 0) {
				fixSinceMs = now;
				ChizuFix.noteSat(this);
				ChizuDriveLog.log(String.format(Locale.US, "orbit refresh (%s): fix after %d s, %s", reason, ran / 1000, ChizuGnssLog.orbits()));
			}
			if (now - fixSinceMs >= AFTER_FIX_MS) {
				end("done, fix held for " + (now - fixSinceMs) / 1000 + " s");
				return;
			}
		} else if (ChizuGnssLog.strongCount() < STRONG_NEEDED) {
			if (weakSinceMs == 0) {
				weakSinceMs = now;
			}
			if (fixSinceMs == 0 && ran >= PROBE_MS && now - weakSinceMs >= WEAK_MS) {
				end("the signal is too weak here");
				return;
			}
		} else {
			weakSinceMs = 0;
		}
		if (ran >= (fresh ? FRESH_MAX_MS : FINISH_MAX_MS)) {
			end(fixSinceMs == 0 ? "no fix in " + ran / 1000 + " s" : "done");
			return;
		}
		handler.postDelayed(this::tick, 2000);
	}

	private void end(@NonNull String why) {
		ChizuDriveLog.log(String.format(Locale.US, "orbit refresh (%s) ends after %d s: %s; %s, %d strong", reason,
				(SystemClock.elapsedRealtime() - startMs) / 1000, why, ChizuGnssLog.orbits(), ChizuGnssLog.strongCount()));
		stopSelf();
	}

	@SuppressLint("MissingPermission")
	@Override
	public void onDestroy() {
		handler.removeCallbacksAndMessages(null);
		if (running) {
			running = false;
			try {
				if (listener != null) {
					((LocationManager) getSystemService(LOCATION_SERVICE)).removeUpdates(listener);
				}
				((OsmandApplication) getApplicationContext()).getLocationProvider().chizuGnssStatusForFix(false);
			} catch (Exception e) {
				// going down anyway
			}
		}
		super.onDestroy();
	}

	@NonNull
	private Notification notification() {
		NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
			nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.chizu_gnss_refresh_channel),
					NotificationManager.IMPORTANCE_LOW));
		}
		return new NotificationCompat.Builder(this, CHANNEL)
				.setSmallIcon(R.drawable.ic_action_location_color)
				.setContentTitle(getString(R.string.chizu_gnss_refresh_title))
				.setContentText(getString(REASON_FRESH.equals(reason) ? R.string.chizu_gnss_refresh_fresh : R.string.chizu_gnss_refresh_finish))
				.setOngoing(true)
				.setPriority(NotificationCompat.PRIORITY_LOW)
				.build();
	}
}
