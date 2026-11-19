package net.osmand.plus;

import static android.content.Context.LOCATION_SERVICE;
import static android.location.LocationManager.GPS_PROVIDER;
import static android.location.LocationManager.NETWORK_PROVIDER;
import static net.osmand.plus.simulation.SimulationProvider.isTunnelLocationSimulated;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;

import net.osmand.GeoidAltitudeCorrection;
import net.osmand.PlatformUtil;
import net.osmand.ResultMatcher;
import net.osmand.StateChangedListener;
import net.osmand.binary.RouteDataObject;
import net.osmand.data.LatLon;
import net.osmand.plus.auto.NavigationSession;
import net.osmand.plus.helpers.CurrentPositionHelper;
import net.osmand.plus.helpers.LocationCallback;
import net.osmand.plus.helpers.LocationServiceHelper;
import net.osmand.plus.helpers.TargetPoint;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.plugins.accessibility.NavigationInfo;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.plus.settings.enums.LocationSource;
import net.osmand.plus.simulation.OsmAndLocationSimulation;
import net.osmand.plus.simulation.SimulationProvider;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.router.RouteSegmentResult;
import net.osmand.util.Algorithms;
import net.osmand.util.CollectionUtils;
import net.osmand.util.MapUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class OsmAndLocationProvider implements SensorEventListener {

	public static final org.apache.commons.logging.Log LOG = PlatformUtil.getLog(OsmAndLocationProvider.class);

	public static final int REQUEST_LOCATION_PERMISSION = 100;

	public interface OsmAndLocationListener {
		void updateLocation(net.osmand.Location location);
	}

	public interface OsmAndCompassListener {
		void updateCompassValue(float value);
	}

	private static final int LOST_LOCATION_MSG_ID = OsmAndConstants.UI_HANDLER_LOCATION_SERVICE + 1;
	private static final int START_SIMULATE_LOCATION_MSG_ID = OsmAndConstants.UI_HANDLER_LOCATION_SERVICE + 2;
	private static final int RUN_SIMULATE_LOCATION_MSG_ID = OsmAndConstants.UI_HANDLER_LOCATION_SERVICE + 3;
	private static final long LOST_LOCATION_CHECK_DELAY = 18000;
	private static final long START_LOCATION_SIMULATION_DELAY = 2000;
	// 白い熊 地図 +079: the estimate along the believed road, off the route and without navigation
	private static final int CHIZU_ROAD_ESTIMATE_START_MSG_ID = OsmAndConstants.UI_HANDLER_LOCATION_SERVICE + 31;
	private static final int CHIZU_ROAD_ESTIMATE_RUN_MSG_ID = OsmAndConstants.UI_HANDLER_LOCATION_SERVICE + 32;
	private static final float CHIZU_ROAD_ESTIMATE_MAX_S = 180f;
	private static final long CHIZU_ROAD_ESTIMATE_MATCH_MS = 5000; // the last match may be this old when it starts
	private static final int UPCOMING_TUNNEL_DISTANCE = 250;

	public static final float ACCURACY_FOR_GPX_AND_ROUTING = 50;

	public static final int NOT_SWITCH_TO_NETWORK_WHEN_GPS_LOST_MS = 12000;

	private static final long LOCATION_TIMEOUT_TO_BE_STALE = 1000 * 60 * 2; // 2 minutes
	private static final long STALE_LOCATION_TIMEOUT_TO_BE_GONE = 1000 * 60 * 20; // 20 minutes

	private static final long AGPS_TO_REDOWNLOAD = 16 * 60 * 60 * 1000; // 16 hours

	private static final int REQUESTS_BEFORE_CHECK_LOCATION = 100;
	private final AtomicInteger locationRequestsCounter = new AtomicInteger();
	private final AtomicInteger staleLocationRequestsCounter = new AtomicInteger();
	
	private static final float MSL_CALIBRATION_MAX_ACCURACY = 10f;
	private static final int DYNAMIC_GEOID_OFFSET_MAX_DISTANCE = 100_000;
	
	private static Location cachedMLSGeoidLocation = null;


	private long lastTimeGPSLocationFixed;
	private long lastTimeLocationFixed;
	private boolean gpsSignalLost;
	private SimulationProvider simulatePosition;
	// 白い熊 地図 +041: the one gate every real fix passes, whichever path it comes by (see chizuGate)
	private net.osmand.Location chizuGateLast;
	private long chizuGateLastMs;
	private long chizuGateGoodMs; // +073: the last fix within CHIZU_GATE_MAX_ACC
	private net.osmand.Location chizuGateGood; // +075: the last fix within CHIZU_GATE_GOOD_ACC — judges a correction
	private long chizuGateGoodAtMs;
	private net.osmand.Location chizuExitLast; // fixes after a tunnel, before the estimate hands over
	private long chizuExitLastMs;
	private int chizuExitGood;
	// 白い熊 地図 +059: 100 m (was 50). On 2026-11-06 GPS weakened and the fused feed gave smooth 51–55 m
	// fixes for minutes; dropping them left 地図 without a position for up to 111 s. The stray jumps the gate
	// is for are caught by speed and jump, not by accuracy.
	private static final float CHIZU_GATE_MAX_ACC = 100f;
	/** 白い熊 地図 +059: fixes up to this accuracy move the position along the route (OsmAnd: 50 m). */
	public static final float CHIZU_ROUTING_ACC = 100f;
	private static final float CHIZU_GATE_MAX_SPEED = 70f; // m/s, 252 km/h
	private static final long CHIZU_GATE_ANCHOR_MS = 15_000; // an older anchor no longer judges a jump
	// 白い熊 地図 +075, after the weak-signal drive of 2026-11-13 (see chizuGate)
	private static final float CHIZU_GATE_GOOD_ACC = 30f; // a fix this good anchors the physics and corrects a weak run
	private static final float CHIZU_GATE_WEAK_ACC = 40f; // an anchor worse than this is weak: a good fix far away corrects it
	private static final long CHIZU_GATE_GOOD_MS = 60_000; // a good anchor this old still judges a correction
	private static final float CHIZU_GATE_ACCEL = 5f; // m/s² a car gains at most…
	private static final float CHIZU_GATE_ACCEL_FREE = 4f; // …plus this much speed noise, m/s
	private static final float CHIZU_GATE_LATERAL = 8f; // m/s² sideways: more is not a turn, it is a stray
	private static final float CHIZU_EXIT_ACC = 30f;
	private static final int CHIZU_EXIT_FIXES = 3;
	private static final float CHIZU_TUNNEL_GAP_M = 1000f;
	private static final float CHIZU_ESTIMATE_MIN_SPEED = 3f; // m/s: a car standing when the fixes stop stays where it is
	private static final float CHIZU_ESTIMATE_MAX_S = 360f; // the estimate runs at most this long at the last speed
	private boolean chizuEstimateOpen; // the running estimate was started outside a tunnel
	private long chizuEstimateStartMs; // +079: when the fixes stopped, whichever estimate runs
	private float chizuEstimateSpeed; // …and the car's speed then
	private net.osmand.Location chizuEstimateFrom; // the fix it started from
	private boolean chizuRoadEstimate; // the estimate runs along the believed road, not the route
	// +081: the speeds of the last accepted fixes, for the speed an estimate runs at
	private static final int CHIZU_SPEEDS = 3;
	private static final long CHIZU_SPEEDS_MS = 4000;
	private final float[] chizuSpeeds = new float[CHIZU_SPEEDS];
	private final long[] chizuSpeedsMs = new long[CHIZU_SPEEDS];
	private int chizuSpeedsAt;
	private static final float CHIZU_TUNNEL_CHAIN_M = 10_000f;
	private final net.osmand.plus.chizu.ChizuDisplayFix chizuDisplay = new net.osmand.plus.chizu.ChizuDisplayFix();
	private net.osmand.plus.chizu.ChizuRoadMatcher chizuMatcher; // 白い熊 地図 +073, made on first use

	private long cachedLocationTimeFix;
	private long timeToNotUseOtherGPS;
	private net.osmand.Location cachedLocation;
	private boolean chizuLastSeeded;
	private net.osmand.Location customLocation;
	private net.osmand.Location prevLocation;

	private boolean sensorRegistered;
	private final float[] mGravs = new float[3];
	private final float[] mGeoMags = new float[3];
	private float previousCorrectionValue = 360;

	private static final boolean USE_KALMAN_FILTER = true;
	private static final float KALMAN_COEFFICIENT = 0.04f;

	float avgValSin;
	float avgValCos;
	float lastValSin;
	float lastValCos;
	private final float[] previousCompassValuesA = new float[50];
	private final float[] previousCompassValuesB = new float[50];
	private int previousCompassIndA;
	private int previousCompassIndB;
	private boolean inUpdateValue;
	private final ExecutorService sensorExecutor = Executors.newSingleThreadExecutor();
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private long lastSensorUpdateTime = 0;

	private Float heading;

	// Current screen orientation
	private int currentScreenOrientation;
	// A 180° rotation (e.g. portrait -> reverse portrait) is not a configuration change
	private final DisplayListener displayListener = new DisplayListener() {
		@Override
		public void onDisplayAdded(int displayId) {
		}

		@Override
		public void onDisplayRemoved(int displayId) {
		}

		@Override
		public void onDisplayChanged(int displayId) {
			updateScreenOrientation();
		}
	};

	private final OsmandApplication app;

	private final NavigationInfo navigationInfo;
	private final CurrentPositionHelper currentPositionHelper;
	private final OsmAndLocationSimulation locationSimulation;
	private LocationServiceHelper locationServiceHelper;

	private net.osmand.Location location;

	private final GPSInfo gpsInfo = new GPSInfo();

	private List<OsmAndLocationListener> locationListeners = new ArrayList<>();
	private List<OsmAndCompassListener> compassListeners = new ArrayList<>();
	private GnssStatus.Callback gpsStatusListener;
	private final float[] mRotationM = new float[9];

	private StateChangedListener<LocationSource> locationSourceListener;

	public OsmAndLocationProvider(@NonNull OsmandApplication app) {
		this.app = app;
		navigationInfo = new NavigationInfo(app);
		currentPositionHelper = new CurrentPositionHelper(app);
		locationSimulation = new OsmAndLocationSimulation(app);
		locationServiceHelper = app.createLocationServiceHelper();
		addLocationSourceListener();
		addLocationListener(navigationInfo);
		addCompassListener(navigationInfo);
	}

	public void resumeAllUpdates() {
		LOG.info(">>>> resumeAllUpdates");
		net.osmand.plus.chizu.ChizuWifiLocator.start(app, "phone"); // 白い熊 地図 +088
		net.osmand.plus.chizu.ChizuFixService.stopNow(app); // +092: 地図 runs the receiver itself again

		registerOrUnregisterCompassListener(true);

		if (app.getSettings().isInternetConnectionAvailable()) {
			if (System.currentTimeMillis() - app.getSettings().AGPS_DATA_LAST_TIME_DOWNLOADED.get() > AGPS_TO_REDOWNLOAD) {
				// force an updated check for internet connectivity here before destroying A-GPS-data
				if (app.getSettings().isInternetConnectionAvailable(true)) {
					redownloadAGPS();
				}
			}
		}

		if (isLocationPermissionAvailable(app)) {
			LocationManager locationService = (LocationManager) app.getSystemService(LOCATION_SERVICE);
			registerGpsStatusListener(locationService);
			try {
				locationServiceHelper.requestLocationUpdates(new LocationCallback() {
					@Override
					public void onLocationResult(@NonNull List<net.osmand.Location> locations) {
						net.osmand.Location location = null;
						if (!locations.isEmpty()) {
							location = locations.get(locations.size() - 1);
							if (useOnlyGPS() && location.hasAccuracy() &&
									location.getAccuracy() > CHIZU_ROUTING_ACC) { // 白い熊 地図 +059: 100 m, as the gate
								// fused provider could return network locations
								net.osmand.plus.chizu.ChizuGnssLog.onFix(app, location, "dropped"); // 白い熊 地図
								return;
							}
							net.osmand.plus.chizu.ChizuGnssLog.onFix(app, location, "kept"); // 白い熊 地図
							lastTimeGPSLocationFixed = System.currentTimeMillis();
						}
						if (!locationSimulation.isRouteAnimating()) {
							setLocation(location);
						}
					}
				});
			} catch (SecurityException e) {
				LOG.error("Location service permission not granted", e);
			} catch (IllegalArgumentException e) {
				LOG.error("GPS location provider not available", e);
			}
			// try to always ask for network provide : it is faster way to find location
			if (locationServiceHelper.isNetworkLocationUpdatesSupported()) {
				locationServiceHelper.requestNetworkLocationUpdates(new LocationCallback() {
					@Override
					public void onLocationResult(@NonNull List<net.osmand.Location> locations) {
						if (!locations.isEmpty() && !useOnlyGPS() && !locationSimulation.isRouteAnimating()) {
							setLocation(locations.get(locations.size() - 1));
						} else if (!locations.isEmpty() && app.getRoutingHelper().isFollowingMode()) {
							net.osmand.plus.chizu.ChizuGnssLog.onFix(app, locations.get(locations.size() - 1), "net-ignored"); // 白い熊 地図
						}
					}
				});
			}
		}
	}

	public void redownloadAGPS() {
		LOG.info(">>>> redownloadAGPS");
		try {
			LocationManager service = (LocationManager) app.getSystemService(LOCATION_SERVICE);
			// Issue 6410: Test not forcing cold start here
			//service.sendExtraCommand(LocationManager.GPS_PROVIDER,"delete_aiding_data", null);
			Bundle bundle = new Bundle();
			service.sendExtraCommand(GPS_PROVIDER, "force_psds_injection", bundle); // 白い熊 地図 +083: its name since Android 10
			service.sendExtraCommand(GPS_PROVIDER, "force_xtra_injection", bundle);
			service.sendExtraCommand(GPS_PROVIDER, "force_time_injection", bundle);
			app.getSettings().AGPS_DATA_LAST_TIME_DOWNLOADED.set(System.currentTimeMillis());
		} catch (Exception e) {
			LOG.debug(e);
			app.getSettings().AGPS_DATA_LAST_TIME_DOWNLOADED.set(0L);
		}
	}

	private void registerGpsStatusListener(@NonNull LocationManager service) {
		chizuGnssByPhone = true; // 白い熊 地図 +075
		chizuApplyGnssStatus();
	}

	// 白い熊 地図 +075: the satellite callback came with the phone map alone, so a drive on the car screen wrote
	// no sky at all (2026-11-13: a weak-signal drive with nothing to say whether it was the sky or the
	// engine). Wanted by the phone map and by the location service alike; registered while either wants it.
	private boolean chizuGnssByPhone;
	private boolean chizuGnssByService;

	/** 白い熊 地図 +092: the orbit refresh (ChizuFixService) wants the satellites' status too. */
	public void chizuGnssStatusForFix(boolean wanted) {
		chizuGnssByFix = wanted;
		chizuApplyGnssStatus();
	}

	private boolean chizuGnssByFix;

	/** 白い熊 地図 +099: the receiver is let go of (the orbit test empties it while it is off)… */
	public void chizuStopReceiver() {
		stopLocationRequests();
	}

	/** …and asked for again. */
	public void chizuStartReceiver() {
		resumeAllUpdates();
	}

	/** 白い熊 地図 +092: driving — navigating, in the driving profile, or connected to the car. */
	public boolean chizuIsOnTheRoad() {
		return chizuOnTheRoad();
	}

	/** The location service (the car, a recording, a navigation with the screen off) wants the satellites too. */
	public void chizuGnssStatusForService(boolean wanted) {
		chizuGnssByService = wanted;
		chizuApplyGnssStatus();
	}

	@SuppressLint("MissingPermission")
	private void chizuApplyGnssStatus() {
		boolean want = chizuGnssByPhone || chizuGnssByService || chizuGnssByFix;
		LocationManager service = (LocationManager) app.getSystemService(LOCATION_SERVICE);
		if (service == null) {
			return;
		}
		if (want && gpsStatusListener == null && hasFineLocationPermission(app)) {
			gpsStatusListener = new GpsStatusListener(gpsInfo);
			service.registerGnssStatusCallback(gpsStatusListener, null);
		} else if (!want && gpsStatusListener != null) {
			service.unregisterGnssStatusCallback(gpsStatusListener);
			gpsStatusListener = null;
		}
	}

	@NonNull
	public GPSInfo getGPSInfo() {
		return gpsInfo;
	}

	public void updateScreenOrientation(int orientation) {
		currentScreenOrientation = orientation;
	}

	private void updateScreenOrientation() {
		Display display = AndroidUtils.getDisplay(app);
		if (display != null) {
			updateScreenOrientation(display.getRotation());
		}
	}

	public void addLocationListener(@NonNull OsmAndLocationListener listener) {
		if (!locationListeners.contains(listener)) {
			locationListeners = CollectionUtils.addToList(locationListeners, listener);
		}
	}

	public void removeLocationListener(@NonNull OsmAndLocationListener listener) {
		locationListeners = CollectionUtils.removeFromList(locationListeners, listener);
	}

	public void addCompassListener(@NonNull OsmAndCompassListener listener) {
		if (!compassListeners.contains(listener)) {
			compassListeners = CollectionUtils.addToList(compassListeners, listener);
		}
	}

	public void removeCompassListener(@NonNull OsmAndCompassListener listener) {
		compassListeners = CollectionUtils.removeFromList(compassListeners, listener);
	}

	private void addLocationSourceListener() {
		locationSourceListener = change -> {
			pauseAllUpdates();
			locationServiceHelper = app.createLocationServiceHelper();
			resumeAllUpdates();
		};
		app.getSettings().LOCATION_SOURCE.addListener(locationSourceListener);
	}

	@Nullable
	public net.osmand.Location getFirstTimeRunDefaultLocation(@Nullable OsmAndLocationListener locationListener) {
		if (isLocationPermissionAvailable(app)) {
			LocationCallback callback = locationListener == null ? null : new LocationCallback() {
				@Override
				public void onLocationResult(@NonNull List<net.osmand.Location> locations) {
					locationListener.updateLocation(locations.isEmpty() ? null : locations.get(0));
				}
			};
			return locationServiceHelper.getFirstTimeRunDefaultLocation(callback);
		}
		return null;
	}

	public boolean hasOrientationSensor() {
		SensorManager sensorMgr = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
		return hasOrientationSensor(sensorMgr);
	}

	public boolean hasOrientationSensor(@NonNull SensorManager sensorMgr) {
		return sensorMgr.getDefaultSensor(Sensor.TYPE_ORIENTATION) != null
				|| sensorMgr.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null;
	}

	public synchronized void registerOrUnregisterCompassListener(boolean register) {
		if (sensorRegistered && !register) {
			Log.d(PlatformUtil.TAG, "Disable sensor");
			((SensorManager) app.getSystemService(Context.SENSOR_SERVICE)).unregisterListener(this);
			((DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE)).unregisterDisplayListener(displayListener);
			sensorRegistered = false;
			heading = null;
		} else if (!sensorRegistered && register) {
			Log.d(PlatformUtil.TAG, "Enable sensor");
			SensorManager sensorMgr = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
			if (app.getSettings().USE_MAGNETIC_FIELD_SENSOR_COMPASS.get() || !hasOrientationSensor(sensorMgr)) {
				Sensor s = sensorMgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
				if (s == null || !sensorMgr.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)) {
					Log.e(PlatformUtil.TAG, "Sensor accelerometer could not be enabled");
				}
				s = sensorMgr.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
				if (s == null || !sensorMgr.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)) {
					Log.e(PlatformUtil.TAG, "Sensor magnetic field could not be enabled");
				}
			} else {
				Sensor s = sensorMgr.getDefaultSensor(Sensor.TYPE_ORIENTATION);
				if (s == null || !sensorMgr.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)) {
					Log.e(PlatformUtil.TAG, "Sensor orientation could not be enabled.");
					s = sensorMgr.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
					if (s == null || !sensorMgr.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)) {
						Log.e(PlatformUtil.TAG, "Sensor rotation could not be enabled.");
					}
				}
			}
			updateScreenOrientation();
			((DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE)).registerDisplayListener(displayListener, mainHandler);
			sensorRegistered = true;
		}
	}

	// location not null!
	private void updateSpeedEmulator(net.osmand.Location location) {
		// For network/gps it's bad way (not accurate). It's widely used for testing purposes
		// possibly keep using only for emulator case
		if (location != null) {
			if (location.distanceTo(location) > 3) {
				float d = location.distanceTo(location);
				long time = location.getTime() - location.getTime();
				float speed;
				if (time == 0) {
					speed = 0;
				} else {
					speed = (d * 1000) / time;
				}
				// Be aware only for emulator ! code is incorrect in case of airplane
				if (speed > 100) {
					speed = 100;
				}
				location.setSpeed(speed);
			}
		}
	}

	public static boolean isPointAccurateForRouting(net.osmand.Location loc) {
		return loc != null && (!loc.hasAccuracy() || loc.getAccuracy() < CHIZU_ROUTING_ACC);
	}

	public static boolean isRunningOnEmulator() {
		return Build.DEVICE.equals("generic");
	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {
	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		// Throttle updates to avoid overloading
		long now = SystemClock.elapsedRealtime();
		if (now - lastSensorUpdateTime < 50) {
			return;
		}
		lastSensorUpdateTime = now;

		// Quickly copy data off the main thread
		final int type = event.sensor.getType();
		final float[] values = event.values.clone();

		// Offload processing to a background thread
		sensorExecutor.execute(() -> processSensorEvent(type, values));
	}

	private synchronized void processSensorEvent(int sensorType, float[] values) {

		// Attention : sensor produces a lot of events & can hang the system
		if (inUpdateValue || !sensorRegistered) {
			return;
		}
		inUpdateValue = true;
		try {
			float val = 0;
			switch (sensorType) {
				case Sensor.TYPE_ACCELEROMETER:
					System.arraycopy(values, 0, mGravs, 0, 3);
					break;
				case Sensor.TYPE_MAGNETIC_FIELD:
					System.arraycopy(values, 0, mGeoMags, 0, 3);
					break;
				case Sensor.TYPE_ORIENTATION:
				case Sensor.TYPE_ROTATION_VECTOR:
					val = values[0];
					break;
				default:
					return;
			}
			OsmandSettings settings = app.getSettings();
			if (sensorType == Sensor.TYPE_ACCELEROMETER || sensorType == Sensor.TYPE_MAGNETIC_FIELD) {
				boolean success = SensorManager.getRotationMatrix(mRotationM, null, mGravs, mGeoMags);
				if (!success) {
					return;
				}
				float[] orientation = SensorManager.getOrientation(mRotationM, new float[3]);
				val = (float) Math.toDegrees(orientation[0]);
			} else if (sensorType == Sensor.TYPE_ROTATION_VECTOR) {
				SensorManager.getRotationMatrixFromVector(mRotationM, values);
				float[] orientation = SensorManager.getOrientation(mRotationM, new float[3]);
				val = (float) Math.toDegrees(orientation[0]);
			}
			val = calcScreenOrientationCorrection(val);
			val = calcGeoMagneticCorrection(val);

			float valRad = (float) (val / 180f * Math.PI);
			lastValSin = (float) Math.sin(valRad);
			lastValCos = (float) Math.cos(valRad);
			boolean filter = settings.USE_KALMAN_FILTER_FOR_COMPASS.get();
			if (filter) {
				filterCompassValue();
			} else {
				avgValSin = lastValSin;
				avgValCos = lastValCos;
			}

			heading = getAngle(avgValSin, avgValCos);
			// Post UI updates safely
			mainHandler.post(this::updateCompassVal);
		} finally {
			inUpdateValue = false;
		}
	}

	private float calcGeoMagneticCorrection(float val) {
		net.osmand.Location l = getLastKnownLocation();
		if (previousCorrectionValue == 360 && l != null) {
			GeomagneticField gf = new GeomagneticField((float) l.getLatitude(), (float) l.getLongitude(), (float) l.getAltitude(),
					System.currentTimeMillis());
			previousCorrectionValue = gf.getDeclination();
		}
		if (previousCorrectionValue != 360) {
			val += previousCorrectionValue;
		}
		return val;
	}

	private float calcScreenOrientationCorrection(float val) {
		if (currentScreenOrientation == 1) {
			val += 90;
		} else if (currentScreenOrientation == 2) {
			val += 180;
		} else if (currentScreenOrientation == 3) {
			val -= 90;
		}
		return val;
	}

	private void filterCompassValue() {
		if (heading == null && previousCompassIndA == 0) {
			Arrays.fill(previousCompassValuesA, lastValSin);
			Arrays.fill(previousCompassValuesB, lastValCos);
			avgValSin = lastValSin;
			avgValCos = lastValCos;
		} else {
			if (USE_KALMAN_FILTER) {
				avgValSin = KALMAN_COEFFICIENT * lastValSin + avgValSin * (1 - KALMAN_COEFFICIENT);
				avgValCos = KALMAN_COEFFICIENT * lastValCos + avgValCos * (1 - KALMAN_COEFFICIENT);
			} else {
				int l = previousCompassValuesA.length;
				previousCompassIndA = (previousCompassIndA + 1) % l;
				previousCompassIndB = (previousCompassIndB + 1) % l;
				// update average
				avgValSin = avgValSin + (-previousCompassValuesA[previousCompassIndA] + lastValSin) / l;
				previousCompassValuesA[previousCompassIndA] = lastValSin;
				avgValCos = avgValCos + (-previousCompassValuesB[previousCompassIndB] + lastValCos) / l;
				previousCompassValuesB[previousCompassIndB] = lastValCos;
			}
		}
	}

	private void updateCompassVal() {
		Float heading = getHeading();
		if (heading != null) {
			List<OsmAndCompassListener> listeners = compassListeners;
			for (int i = 0, size = listeners.size(); i < size; i++) {
				listeners.get(i).updateCompassValue(heading);
			}
		}
	}

	@Nullable
	public Float getHeading() {
		return heading;
	}

	private float getAngle(float sinA, float cosA) {
		return MapUtils.unifyRotationTo360((float) (Math.atan2(sinA, cosA) * 180 / Math.PI));
	}

	private void updateLocation(net.osmand.Location location) {
		List<OsmAndLocationListener> listeners = locationListeners;
		for (int i = 0, size = listeners.size(); i < size; i++) {
			listeners.get(i).updateLocation(location);
		}
	}

	/** 白い熊 地図: the position is an estimate (a tunnel) — the arrow is drawn dimmed meanwhile. */
	public boolean isChizuEstimating() {
		return simulatePosition != null || chizuRoadEstimate;
	}

	/**
	 * 白い熊 地図 +041: whether a real fix may be used while navigating. On 2026-11-05 the location
	 * service handed over, every 20 s, a stray fix 100–600 m off at 180–860 m/s; the car path dropped
	 * it, yet 186 of 206 still reached 地図 by a second path (named in the log line below) and threw
	 * the arrow about. So every path passes here: worse than 50 m, faster than 70 m/s, or farther from
	 * the last good fix than speed and accuracy allow — dropped. And after a tunnel, the estimate hands
	 * over only to three consistent fixes of 30 m or better.
	 * <p>
	 * +075, after the weak-signal drive of 2026-11-13 (median accuracy 18 m, a quarter of the fixes worse
	 * than 40 m). EMUI's “fused” engine does not fall silent when the satellites go: it dead-reckons, one
	 * fix a second, the accuracy ramping to a 55 m cap, the speed decaying, the bearing drifting. The gate
	 * anchored on that run and then threw the true fixes away as “jumps” — nine of them, for up to 16 s
	 * each time. And strays at a plausible speed (35–66 m/s, under the cap) passed and turned the arrow
	 * round at 60 km/h. So now: a fix may not be faster than the last by more than a car accelerates; the
	 * jump allowance comes from the last accepted speed, not from the stray's own claim; a turn needing
	 * more than {@value #CHIZU_GATE_LATERAL} m/s² sideways is a stray; and a good fix after a weak run is
	 * a correction, judged only against the last good fix. Replayed over both drives of that day, these
	 * drop the five strays and keep every true fix.
	 */
	private boolean chizuGate(@Nullable net.osmand.Location l, @NonNull String path) {
		boolean following = app.getRoutingHelper().isFollowingMode();
		// 白い熊 地図 +073: also when driving without navigation — stray jumps threw the arrow off the road there too
		if (l == null || !chizuOnTheRoad() || isTunnelLocationSimulated(l)
				|| !SimulationProvider.isNotSimulatedLocation(l)) {
			return true;
		}
		long now = android.os.SystemClock.elapsedRealtime();
		float acc = l.hasAccuracy() ? l.getAccuracy() : Float.MAX_VALUE;
		float spd = l.hasSpeed() ? l.getSpeed() : 0f;
		String why = null;
		double jump = -1;
		// off navigation a rough fix is dropped only while good ones are coming: parked indoors with nothing
		// but network fixes, the position must still be shown
		boolean goodLately = chizuGateGoodMs != 0 && now - chizuGateGoodMs < CHIZU_GATE_ANCHOR_MS;
		net.osmand.Location last = chizuGateLast;
		float lastAcc = last == null ? 0f : last.hasAccuracy() ? last.getAccuracy() : CHIZU_GATE_MAX_ACC;
		float lastSpd = last == null ? 0f : last.hasSpeed() ? last.getSpeed() : 0f;
		if (acc > CHIZU_GATE_MAX_ACC && (following || goodLately)) {
			why = "accuracy";
		} else if (spd > CHIZU_GATE_MAX_SPEED) {
			why = "speed";
		} else if (acc <= CHIZU_GATE_GOOD_ACC && last != null && lastAcc > CHIZU_GATE_WEAK_ACC && acc <= 0.6f * lastAcc) {
			// +075: a good fix after weak ones is the truth coming back, wherever the weak run drifted to;
			// only the last good fix may call it a jump, and only within what the car could have driven
			net.osmand.Location good = chizuGateGood;
			if (good != null && now - chizuGateGoodAtMs <= CHIZU_GATE_GOOD_MS) {
				double age = Math.max(0.5, (now - chizuGateGoodAtMs) / 1000.0);
				jump = MapUtils.getDistance(good.getLatitude(), good.getLongitude(), l.getLatitude(), l.getLongitude());
				float goodSpd = good.hasSpeed() ? good.getSpeed() : 0f;
				float goodAcc = good.hasAccuracy() ? good.getAccuracy() : CHIZU_GATE_GOOD_ACC;
				if (jump > (Math.max(goodSpd, spd) + 10) * age + 2 * (acc + goodAcc)) {
					why = String.format(java.util.Locale.US, "jump-from-good (%.0f s ago)", age);
				}
			}
		} else if (last != null && now - chizuGateLastMs < CHIZU_GATE_ANCHOR_MS) {
			double dt = Math.max(0.5, (now - chizuGateLastMs) / 1000.0);
			jump = MapUtils.getDistance(last.getLatitude(), last.getLongitude(), l.getLatitude(), l.getLongitude());
			if (spd - lastSpd > CHIZU_GATE_ACCEL * dt + CHIZU_GATE_ACCEL_FREE) {
				why = String.format(java.util.Locale.US, "acceleration (+%.1f m/s in %.1f s)", spd - lastSpd, dt);
			} else if (jump > (lastSpd + 10) * dt + 2 * (acc + lastAcc)) {
				why = "jump";
			} else if (l.hasBearing() && last.hasBearing() && spd >= 5 && lastSpd >= 5) {
				float turn = Math.abs(l.getBearing() - last.getBearing()) % 360;
				turn = turn > 180 ? 360 - turn : turn;
				double lateral = (spd + lastSpd) / 2 * Math.toRadians(turn) / dt;
				if (lateral > CHIZU_GATE_LATERAL) {
					why = String.format(java.util.Locale.US, "turn (%.0f° in %.1f s at %.0f m/s)", turn, dt, (spd + lastSpd) / 2);
				}
			}
		}
		if (why == null && (simulatePosition != null || chizuRoadEstimate)) {
			// coming out of a tunnel: the estimate goes on until three good fixes agree
			boolean consistent = chizuExitLast != null && now - chizuExitLastMs < 3000
					&& MapUtils.getDistance(chizuExitLast.getLatitude(), chizuExitLast.getLongitude(),
					l.getLatitude(), l.getLongitude()) <= (spd + 10) * Math.max(0.5, (now - chizuExitLastMs) / 1000.0) + 2 * acc;
			// +078: an estimate started outside a tunnel hands over to any fixes the gate accepts, three agreeing
			chizuExitGood = acc <= (simulatePosition == null || chizuEstimateOpen ? CHIZU_GATE_MAX_ACC : CHIZU_EXIT_ACC)
					? (consistent ? chizuExitGood + 1 : 1) : 0;
			chizuExitLast = l;
			chizuExitLastMs = now;
			if (chizuExitGood < CHIZU_EXIT_FIXES) {
				why = "tunnel exit " + chizuExitGood + "/" + CHIZU_EXIT_FIXES;
			}
		}
		if (why != null) {
			net.osmand.plus.chizu.ChizuDriveLog.log(String.format(java.util.Locale.US,
					"loc gate %s dropped: %s %s %.6f,%.6f acc=%.0f spd=%.1f%s by %s", path, why, l.getProvider(),
					l.getLatitude(), l.getLongitude(), acc, spd,
					jump >= 0 ? String.format(java.util.Locale.US, " jump=%.0fm", jump) : "",
					net.osmand.plus.chizu.ChizuDriveLog.caller())); // 白い熊 地図 drive log
			return false;
		}
		chizuGateLast = l;
		chizuGateLastMs = now;
		if (acc <= CHIZU_GATE_MAX_ACC) {
			chizuGateGoodMs = now;
		}
		if (acc <= CHIZU_GATE_GOOD_ACC) {
			chizuGateGood = l;
			chizuGateGoodAtMs = now;
		}
		chizuExitGood = 0;
		chizuExitLast = null;
		return true;
	}

	private boolean useOnlyGPS() {
		if (app.getRoutingHelper().isFollowingMode()) {
			return true;
		}
		if (lastTimeGPSLocationFixed > 0 && (System.currentTimeMillis() - lastTimeGPSLocationFixed) < NOT_SWITCH_TO_NETWORK_WHEN_GPS_LOST_MS) {
			return true;
		}
		return isRunningOnEmulator();
	}

	private void stopLocationRequests() {
		LOG.info(">>>> stopLocationRequests");

		gpsInfo.reset();
		chizuGnssByPhone = false; // 白い熊 地図 +075: stays registered while the location service wants it
		chizuApplyGnssStatus();
		try {
			locationServiceHelper.removeLocationUpdates();
		} catch (SecurityException e) {
			// Location service permission not granted
		}
	}

	public void pauseAllUpdates() {
		net.osmand.plus.chizu.ChizuWifiLocator.stop(app, "phone"); // 白い熊 地図 +088
		net.osmand.plus.chizu.ChizuFixService.finishIfWorthIt(app); // +092: a fix within reach is finished first
		stopLocationRequests();
		registerOrUnregisterCompassListener(false);
	}

	public static net.osmand.Location convertLocation(Location l, OsmandApplication app) {
		if (l == null) {
			return null;
		}
		net.osmand.Location r = new net.osmand.Location(l.getProvider());
		r.setLatitude(l.getLatitude());
		r.setLongitude(l.getLongitude());
		r.setTime(l.getTime());
		r.chizuElapsedMs = l.getElapsedRealtimeNanos() / 1_000_000L;
		net.osmand.plus.chizu.ChizuGnssLog.onExtras(l); // 白い熊 地図 +075: what the engine says about a weak fix
		if (l.hasAccuracy()) {
			r.setAccuracy(l.getAccuracy());
		}
		if (VERSION.SDK_INT >= VERSION_CODES.O) {
			if (l.hasVerticalAccuracy()) {
				r.setVerticalAccuracy(l.getVerticalAccuracyMeters());
			}
		}
		if (l.hasSpeed()) {
			r.setSpeed(l.getSpeed());
		}
		if (VERSION.SDK_INT >= VERSION_CODES.UPSIDE_DOWN_CAKE && l.hasMslAltitude() && l.hasAltitude()) {
			r.setAltitude(l.getMslAltitudeMeters());
			boolean qualityAcceptable = true;
			if (l.hasMslAltitudeAccuracy()) {
				r.setVerticalAccuracy(l.getMslAltitudeAccuracyMeters());
				qualityAcceptable = l.getMslAltitudeAccuracyMeters() <= MSL_CALIBRATION_MAX_ACCURACY;
			}
			if (qualityAcceptable) {
				double newOffset = l.getAltitude() - l.getMslAltitudeMeters();
				if (cachedMLSGeoidLocation == null) {
					LOG.info("Dynamic geoid offset calibrated: " + newOffset + "m (provider=" + l.getProvider() +
							(l.hasMslAltitudeAccuracy() ? ", MSL accuracy=" + l.getMslAltitudeAccuracyMeters() + "m" : "") + ")");
				}
				cachedMLSGeoidLocation = l;
			}
		} else if (l.hasAltitude()) {
			double alt = l.getAltitude();
			GeoidAltitudeCorrection geo = app == null ? null : app.getResourceManager().getGeoidAltitudeCorrection();
			if (geo != null && geo.isGeoidInformationAvailable()) {
				alt -= geo.getGeoidHeight(l.getLatitude(), l.getLongitude());
			} else if (cachedMLSGeoidLocation != null) {
				double dist = MapUtils.getDistance(cachedMLSGeoidLocation.getLatitude(), cachedMLSGeoidLocation.getLongitude(), l.getLatitude(), l.getLongitude());
				if (dist < DYNAMIC_GEOID_OFFSET_MAX_DISTANCE) {
					alt -= cachedMLSGeoidLocation.getAltitude() - cachedMLSGeoidLocation.getMslAltitudeMeters();
				} else {
					cachedMLSGeoidLocation = null;
				}
			}
			r.setAltitude(alt);
		}
		
		if (l.hasBearing()) {
			r.setBearing(l.getBearing());
		}
		return r;
	}

	private void scheduleCheckIfGpsLost(@NonNull net.osmand.Location location) {
		RoutingHelper routingHelper = app.getRoutingHelper();
		if (routingHelper.isFollowingMode() && routingHelper.getLeftDistance() > 0 && simulatePosition == null) {
			long fixTime = location.getTime();
			app.runInUIThreadAndCancelPrevious(LOST_LOCATION_MSG_ID, () -> {
				net.osmand.Location lastKnown = getLastKnownLocation();
				if (lastKnown != null && lastKnown.getTime() > fixTime) {
					// false positive case, still strange how we got here with removeMessages
					return;
				}
				gpsSignalLost = true;
				if (routingHelper.isFollowingMode() && routingHelper.getLeftDistance() > 0
						&& simulatePosition == null) {
					routingHelper.getVoiceRouter().gpsLocationLost();
					setLocation(null);
				}
			}, LOST_LOCATION_CHECK_DELAY);
			app.runInUIThreadAndCancelPrevious(START_SIMULATE_LOCATION_MSG_ID, () -> {
				net.osmand.Location lastKnown = getLastKnownLocation();
				if (lastKnown != null && lastKnown.getTime() > fixTime) {
					// false positive case, still strange how we got here with removeMessages
					return;
				}
				// Speed 120kmh, 2 seconds -> 60 m
				// 白い熊 地図: the whole chain of tunnels ahead, not the first one only
				List<RouteSegmentResult> tunnel = routingHelper.getChizuTunnelChain(UPCOMING_TUNNEL_DISTANCE,
						CHIZU_TUNNEL_GAP_M, CHIZU_TUNNEL_CHAIN_M);
				// 白い熊 地図 +078: no tunnel the route knows of within reach, yet the receiver is silent and the car
				// was moving — the position goes on along the route all the same. On 2026-11-16 the receiver fell
				// silent five times for 38–269 s (covered stretches of the city ring, one fix let through inside a
				// tunnel) with no tunnel 250 m ahead on the route at that moment, and the arrow stood still each
				// time, once for 4.5 minutes and 2.4 km.
				chizuEstimateOpen = false;
				float steady = chizuSteadySpeed(location); // +081
				if (tunnel == null && steady >= CHIZU_ESTIMATE_MIN_SPEED) {
					tunnel = routingHelper.getChizuAheadChain(Math.max(500f,
							Math.min(CHIZU_TUNNEL_CHAIN_M, steady * CHIZU_ESTIMATE_MAX_S)));
					chizuEstimateOpen = tunnel != null;
				}
				if (tunnel != null) {
					net.osmand.Location from = new net.osmand.Location(location);
					from.setSpeed(steady);
					simulatePosition = new SimulationProvider(from, tunnel, chizuEstimateOpen);
					chizuEstimateStartMs = android.os.SystemClock.elapsedRealtime();
					chizuEstimateSpeed = steady;
					chizuExitGood = 0;
					chizuExitLast = null;
					net.osmand.plus.chizu.ChizuDriveLog.log((chizuEstimateOpen ? "position estimate along the route starts (no fixes), "
							: "tunnel simulation starts, ") + tunnel.size() + " segments, speed " + steady + " (last fix " + location.getSpeed() + ")"); // 白い熊 地図 drive log
					scheduleSimulatedPositionRun();
				}
			}, START_LOCATION_SIMULATION_DELAY);
		}
	}

	private void scheduleSimulatedPositionRun() {
		if (simulatePosition != null) {
			net.osmand.Location loc = simulatePosition.getSimulatedLocationForTunnel();
			if (loc != null) {
				// 白い熊 地図 +079: the tunnel is behind and still no fix — on along the route, see chizuEstimateGoesOn
				if (!chizuEstimateOpen && simulatePosition.isChizuPastEnd() && chizuEstimateGoesOn(loc)) {
					loc = simulatePosition.getSimulatedLocationForTunnel();
					if (loc == null) {
						net.osmand.plus.chizu.ChizuDriveLog.log("position estimate ends: nothing of the route to go on");
						simulatePosition = null;
						return;
					}
				}
				setLocation(loc);
				app.runInUIThreadAndCancelPrevious(RUN_SIMULATE_LOCATION_MSG_ID, this::scheduleSimulatedPositionRun, 600);
			} else {
				net.osmand.plus.chizu.ChizuDriveLog.log(chizuEstimateOpen ? "position estimate ends: its time or the route is over"
						: "tunnel simulation ends"); // 白い熊 地図 drive log
				simulatePosition = null;
			}
		}
	}

	/**
	 * 白い熊 地図 +079: the tunnel estimate has reached the end of its tunnels and no fix has come. Stock OsmAnd
	 * runs it 100 m further in a straight line and stops, leaving the arrow there. On 2026-11-16 at 16:38:01
	 * the route knew one tunnel of some 150 m ahead (Zlíchov) with the next more than
	 * {@value #CHIZU_TUNNEL_GAP_M} m on: the estimate ended after 13 s, the receiver stayed silent for
	 * another 20 s in the open, gave one fix, and was gone for 269 s through the two tunnels after it — the
	 * arrow stood still throughout, and the route was then judged left by 2.4 km. So past the tunnel the
	 * estimate goes on along the route at the speed the car had, for what is left of
	 * {@value #CHIZU_ESTIMATE_MAX_S} s, and hands back to any three agreeing fixes.
	 */
	private boolean chizuEstimateGoesOn(@NonNull net.osmand.Location from) {
		float left = CHIZU_ESTIMATE_MAX_S - (android.os.SystemClock.elapsedRealtime() - chizuEstimateStartMs) / 1000f;
		if (left <= 0 || chizuEstimateSpeed < CHIZU_ESTIMATE_MIN_SPEED) {
			return false;
		}
		List<RouteSegmentResult> ahead = app.getRoutingHelper().getChizuAheadChain(
				Math.max(500f, Math.min(CHIZU_TUNNEL_CHAIN_M, chizuEstimateSpeed * left)));
		if (ahead == null) {
			return false;
		}
		net.osmand.Location start = new net.osmand.Location(from);
		start.setSpeed(chizuEstimateSpeed);
		start.setTime(System.currentTimeMillis());
		SimulationProvider next = new SimulationProvider(start, ahead, true);
		if (!next.isSimulatedDataAvailable()) {
			return false;
		}
		simulatePosition = next;
		chizuEstimateOpen = true;
		net.osmand.plus.chizu.ChizuDriveLog.log(String.format(java.util.Locale.US,
				"position estimate goes on along the route past the tunnel (no fixes), %d segments, speed %.1f, %.0f s left",
				ahead.size(), chizuEstimateSpeed, left));
		return true;
	}

	/**
	 * 白い熊 地図 +079: the receiver is silent and there is no route to estimate along — the car has left it, or
	 * drives without navigation. The position goes on along the road the car is matched to, at the speed it
	 * had, straight on where the road ends, drawn dimmed like any estimate. It is display only: the route
	 * is not told (a new one is not searched from a guess), and nothing of it is recorded. It ends with
	 * three agreeing fixes, with the road, or after {@value #CHIZU_ROAD_ESTIMATE_MAX_S} s — half the time of
	 * the estimate along a route, for without one every junction is a guess, and a car that drove into a
	 * garage is not to be shown wandering off for long.
	 */
	private void chizuScheduleRoadEstimate(@NonNull net.osmand.Location fix) {
		app.runInUIThreadAndCancelPrevious(CHIZU_ROAD_ESTIMATE_START_MSG_ID, () -> {
			if (prevLocation != fix || simulatePosition != null || chizuRoadEstimate || chizuMatcher == null
					|| !chizuOnTheRoad() || chizuSteadySpeed(fix) < CHIZU_ESTIMATE_MIN_SPEED) {
				return; // a newer fix came, the route estimate runs, or the car stood
			}
			chizuEstimateStartMs = android.os.SystemClock.elapsedRealtime();
			chizuEstimateSpeed = chizuSteadySpeed(fix);
			chizuEstimateFrom = fix;
			chizuRoadEstimate = true;
			chizuExitGood = 0;
			chizuExitLast = null;
			if (chizuRoadEstimateStep()) {
				net.osmand.plus.chizu.ChizuDriveLog.log(String.format(java.util.Locale.US,
						"position estimate along the road starts (no fixes), speed %.1f", chizuEstimateSpeed));
			}
		}, START_LOCATION_SIMULATION_DELAY + 200); // after the route estimate had its chance
	}

	private boolean chizuRoadEstimateStep() {
		if (!chizuRoadEstimate) {
			return false;
		}
		long now = android.os.SystemClock.elapsedRealtime();
		boolean first = now - chizuEstimateStartMs < 300;
		String why = null;
		net.osmand.plus.chizu.ChizuRoadMatcher.Match m = null;
		if (!chizuOnTheRoad() || chizuMatcher == null) {
			why = "no longer driving";
		} else if (now - chizuEstimateStartMs > CHIZU_ROAD_ESTIMATE_MAX_S * 1000) {
			why = "its time is over";
		} else {
			m = chizuMatcher.estimate(chizuEstimateSpeed, CHIZU_ROAD_ESTIMATE_MATCH_MS);
			if (m == null) {
				why = first ? null : "the road ends";
			}
		}
		if (m == null) {
			chizuRoadEstimate = false;
			if (why != null) {
				net.osmand.plus.chizu.ChizuDriveLog.log("position estimate along the road ends: " + why);
			}
			return false; // at the start: the car was matched to no road, nothing to go along
		}
		net.osmand.Location loc = new net.osmand.Location(SimulationProvider.SIMULATED_PROVIDER_TUNNEL);
		loc.setLatitude(m.lat);
		loc.setLongitude(m.lon);
		loc.setBearing(m.dir);
		loc.setSpeed(chizuEstimateSpeed);
		loc.setTime(System.currentTimeMillis());
		if (chizuEstimateFrom != null && chizuEstimateFrom.hasAltitude()) {
			loc.setAltitude(chizuEstimateFrom.getAltitude());
		}
		net.osmand.plus.chizu.ChizuDriveLog.location("ph", loc);
		this.location = loc;
		updateLocation(loc);
		app.runInUIThreadAndCancelPrevious(CHIZU_ROAD_ESTIMATE_RUN_MSG_ID, this::chizuRoadEstimateStep, 600);
		return true;
	}

	/**
	 * 白い熊 地図 +081: the speed an estimate runs at — the median of the last {@value #CHIZU_SPEEDS} accepted
	 * fixes within {@value #CHIZU_SPEEDS_MS} ms, not the last fix's own claim. The engine's last fix before it
	 * goes silent is often its worst (2026-11-16 16:38:34: 22 km/h claimed at about 70).
	 */
	private float chizuSteadySpeed(@NonNull net.osmand.Location fix) {
		long now = android.os.SystemClock.elapsedRealtime();
		float[] v = new float[CHIZU_SPEEDS];
		int n = 0;
		for (int i = 0; i < CHIZU_SPEEDS; i++) {
			if (chizuSpeedsMs[i] != 0 && now - chizuSpeedsMs[i] <= CHIZU_SPEEDS_MS + START_LOCATION_SIMULATION_DELAY) {
				v[n++] = chizuSpeeds[i];
			}
		}
		float own = fix.hasSpeed() ? fix.getSpeed() : 0f;
		if (n < 2) {
			return own;
		}
		java.util.Arrays.sort(v, 0, n);
		return n == 2 ? (v[0] + v[1]) / 2 : v[n / 2];
	}

	private void chizuNoteSpeed(@NonNull net.osmand.Location fix) {
		if (fix.hasSpeed()) {
			chizuSpeeds[chizuSpeedsAt] = fix.getSpeed();
			chizuSpeedsMs[chizuSpeedsAt] = android.os.SystemClock.elapsedRealtime();
			chizuSpeedsAt = (chizuSpeedsAt + 1) % CHIZU_SPEEDS;
		}
	}

	/** 白い熊 地図 +079: a fix the gate accepted is here — both estimates are over. */
	private void chizuEstimatesEnd() {
		if (simulatePosition != null) {
			net.osmand.plus.chizu.ChizuDriveLog.log("tunnel simulation ends: fixes back"); // 白い熊 地図 drive log
		}
		simulatePosition = null;
		if (chizuRoadEstimate) {
			chizuRoadEstimate = false;
			net.osmand.plus.chizu.ChizuDriveLog.log("position estimate along the road ends: fixes back");
		}
	}

	public void setCustomLocation(net.osmand.Location location, long ignoreLocationsTime) {
		timeToNotUseOtherGPS = System.currentTimeMillis() + ignoreLocationsTime;
		customLocation = location;
		setLocation(location);
	}

	private boolean shouldIgnoreLocation(net.osmand.Location location) {
		if (customLocation != null && timeToNotUseOtherGPS >= System.currentTimeMillis()) {
			return location == null || !Algorithms.stringsEqual(customLocation.getProvider(), location.getProvider());
		}
		return prevLocation != null && location != null && prevLocation.getTime() == location.getTime();
	}

	private net.osmand.Location setLocationForRouting(net.osmand.Location location, RoutingHelper routingHelper) {
		net.osmand.Location updatedLocation = location;
		if (routingHelper.isFollowingMode()) {
			if (location == null || isPointAccurateForRouting(location)) {
				// Update routing position and get location for sticking mode
				// 白い熊 地図 +061: in the driving profile the position is always the point on the route
				updatedLocation = routingHelper.setCurrentLocation(location, app.getSettings().SNAP_TO_ROAD.get() || chizuDriving());
			}
		} else if (routingHelper.isRoutePlanningMode() && app.getSettings().getPointToStart() == null) {
			routingHelper.setCurrentLocation(location, false);
		} else if (getLocationSimulation().isRouteAnimating()) {
			routingHelper.setCurrentLocation(location, false);
		}
		return updatedLocation;
	}

	public void setLocationFromService(net.osmand.Location location) {
		if (locationSimulation.isRouteAnimating() || shouldIgnoreLocation(location)
				|| net.osmand.plus.chizu.ChizuWifiLocator.suppresses(location)) {
			return;
		}
		if (!chizuGate(location, "aa")) {
			return; // 白い熊 地図
		}
		if (chizuOnTheRoad()) { // +073: driving without navigation too
			net.osmand.plus.chizu.ChizuDriveLog.location("aa", location); // 白い熊 地図 drive log
		}
		if (!isTunnelLocationSimulated(location) && !locationSimulation.isRouteAnimating()) {
			net.osmand.plus.chizu.ChizuRoadLog.onFix(location); // 白い熊 地図 road-section log, navigating or not
		}
		prevLocation = location;
		if (location != null) {
			lastTimeLocationFixed = System.currentTimeMillis();
			if (isPointAccurateForRouting(location) && !isTunnelLocationSimulated(location)) {
				chizuEstimatesEnd(); // 白い熊 地図
				chizuNoteSpeed(location);
				notifyGpsLocationRecovered();
				scheduleCheckIfGpsLost(location);
				chizuScheduleRoadEstimate(location);
			}
		}
		RoutingHelper routingHelper = app.getRoutingHelper();
		app.getSavingTrackHelper().updateLocation(location, heading);
		app.getAverageSpeedComputer().updateLocation(location);
		app.getAverageGlideComputer().updateLocation(location);
		PluginsHelper.updateLocationPlugins(location);

		net.osmand.Location updatedLocation = setLocationForRouting(location, routingHelper);
		app.getWaypointHelper().locationChanged(location);
		NavigationSession carNavigationSession = app.getCarNavigationSession();
		// 白い熊 地図 +074: also while the session is merely stopped (the camera has the car screen) — the road
		// and the heading go on being followed, so the map comes back showing the car where it is
		if (carNavigationSession != null) {
			carNavigationSession.updateLocation(location);
			this.location = chizuShown(location, updatedLocation); // 白い熊 地図
			net.osmand.plus.chizu.ChizuLastPosition.note(app, this.location); // +088
			updateLocation(this.location);
		}
	}

	public void setLocationFromSimulation(net.osmand.Location location) {
		setLocation(location);
	}

	/** 白い熊 地図 +088: a position from the Wi-Fi networks in range (ChizuWifiLocator) — never while a route is followed. */
	public void chizuSetWifiLocation(@NonNull net.osmand.Location location) {
		if (!app.getRoutingHelper().isFollowingMode()) {
			setLocation(location);
		}
	}

	private void setLocation(@Nullable net.osmand.Location location) {
		if (shouldIgnoreLocation(location) || net.osmand.plus.chizu.ChizuWifiLocator.suppresses(location)) {
			return; // 白い熊 地図 +088: the system's network position gives way while 地図's own Wi-Fi one is sure
		}
		if (!chizuGate(location, "ph")) {
			return; // 白い熊 地図
		}
		if (chizuOnTheRoad()) { // +073: driving without navigation too
			net.osmand.plus.chizu.ChizuDriveLog.location("ph", location); // 白い熊 地図 drive log
		}
		if (!isTunnelLocationSimulated(location) && !locationSimulation.isRouteAnimating()) {
			net.osmand.plus.chizu.ChizuRoadLog.onFix(location); // 白い熊 地図 road-section log, navigating or not
		}
		prevLocation = location;
		if (location == null) {
			gpsInfo.reset();
		}
		enhanceLocation(location);

		if (location != null) {
			// use because there is a bug on some devices with location.getTime()
			lastTimeLocationFixed = System.currentTimeMillis();
			if (isPointAccurateForRouting(location) && !isTunnelLocationSimulated(location)) {
				chizuEstimatesEnd(); // 白い熊 地図
				chizuNoteSpeed(location);
				notifyGpsLocationRecovered();
				scheduleCheckIfGpsLost(location);
				chizuScheduleRoadEstimate(location);
			}
		}

		RoutingHelper routingHelper = app.getRoutingHelper();
		// 1. Logging services
		if (location != null) {
			app.getSavingTrackHelper().updateLocation(location, heading);
			app.getAverageSpeedComputer().updateLocation(location);
			app.getAverageGlideComputer().updateLocation(location);
			PluginsHelper.updateLocationPlugins(location);
		}

		// 2. routing
		net.osmand.Location updatedLocation = setLocationForRouting(location, routingHelper);
		app.getWaypointHelper().locationChanged(location);
		this.location = chizuShown(location, updatedLocation); // 白い熊 地図
		net.osmand.plus.chizu.ChizuLastPosition.note(app, this.location); // +088

		// Update information
		updateLocation(this.location);
	}

	/**
	 * 白い熊 地図 +041: while navigating, what the map shows — on the route (OsmAnd's own snap), else on
	 * the nearest road in the direction of travel, and facing only the way the car moves.
	 */
	@Nullable
	/** 白い熊 地図 +061: the driving profile is in use — the position is snapped to the believed road. */
	private boolean chizuDriving() {
		return app.getSettings().getApplicationMode().isDerivedRoutingFrom(net.osmand.plus.settings.backend.ApplicationMode.CAR);
	}

	/** 白い熊 地図 +074: navigating, in the driving profile, or connected to the car at all. */
	private boolean chizuOnTheRoad() {
		return app.getRoutingHelper().isFollowingMode() || chizuDriving() || app.getCarNavigationSession() != null;
	}

	/** 白い熊 地図 +078: the start of a new route — on the road the car is matched to, see ChizuDisplayFix.routeStart. */
	@NonNull
	public net.osmand.Location chizuRouteStart(@NonNull net.osmand.Location raw) {
		return isTunnelLocationSimulated(raw) ? raw : chizuDisplay.routeStart(raw);
	}

	private net.osmand.Location chizuShown(@Nullable net.osmand.Location raw, @Nullable net.osmand.Location routed) {
		// 白い熊 地図 +061: snapped whenever driving, navigating or not (白い熊, 2026-11-06)
		boolean active = chizuOnTheRoad();
		if (raw == null || routed == null || !active) {
			if (!active) {
				chizuDisplay.reset(); // the next drive starts from its own movement, not this one's heading
				if (chizuMatcher != null) {
					chizuMatcher.forget();
				}
			}
			return routed;
		}
		boolean onRoute = routed != raw;
		if (!onRoute && isTunnelLocationSimulated(raw)) {
			return routed; // the tunnel estimate runs along the route already
		}
		if (!onRoute && net.osmand.plus.chizu.ChizuWifiLocator.PROVIDER.equals(raw.getProvider())) {
			return routed; // +088: a taught spot is where 白い熊 stands, not the nearest road
		}
		if (chizuMatcher == null) {
			chizuMatcher = new net.osmand.plus.chizu.ChizuRoadMatcher(app);
		}
		return chizuDisplay.shown(raw, routed, onRoute, chizuMatcher);
	}

	public void ensureLatestLocation() {
		if (prevLocation != null && (location == null || prevLocation.getTime() > location.getTime())) {
			cachedLocation = location = prevLocation;
			cachedLocationTimeFix = lastTimeLocationFixed;
			updateLocation(location);
		}
	}

	private void notifyGpsLocationRecovered() {
		if (gpsSignalLost) {
			gpsSignalLost = false;
			RoutingHelper routingHelper = app.getRoutingHelper();
			if (routingHelper.isFollowingMode() && routingHelper.getLeftDistance() > 0) {
				routingHelper.getVoiceRouter().gpsLocationRecover();
			}
		}
	}

	private void enhanceLocation(net.osmand.Location location) {
		if (location != null && isRunningOnEmulator()) {
			// only for emulator
			updateSpeedEmulator(location);
		}
	}

	public NavigationInfo getNavigationInfo() {
		return navigationInfo;
	}

	public String getNavigationHint(TargetPoint point) {
		String hint = navigationInfo.getDirectionString(point == null ? null : point.getLatLon(), getHeading());
		if (hint == null)
			hint = app.getString(R.string.no_info);
		return hint;
	}

	public String getNavigationHint(LatLon point) {
		String hint = navigationInfo.getDirectionString(point, getHeading());
		if (hint == null)
			hint = app.getString(R.string.no_info);
		return hint;
	}

	public void emitNavigationHint() {
		TargetPoint point = app.getTargetPointsHelper().getPointToNavigate();
		if (point != null) {
			if (app.getRoutingHelper().isRouteCalculated()) {
				app.getRoutingHelper().getVoiceRouter().announceCurrentDirection(getLastKnownLocation());
			} else {
				app.showToastMessage(getNavigationHint(point));
			}
		} else {
			app.showShortToastMessage(R.string.access_no_destination);
		}
	}

	public RouteDataObject getLastKnownRouteSegment() {
		return currentPositionHelper.getLastKnownRouteSegment(getLastKnownLocation());
	}

	public boolean getRouteSegment(net.osmand.Location loc,
	                               @Nullable ApplicationMode appMode,
	                               boolean cancelPreviousSearch,
	                               ResultMatcher<RouteDataObject> result) {
		return currentPositionHelper.getRouteSegment(loc, appMode, cancelPreviousSearch, result);
	}

	@Nullable
	public net.osmand.Location getLastKnownLocation() {
		net.osmand.Location loc = this.location;
		if (loc != null) {
			int counter = locationRequestsCounter.incrementAndGet();
			if (counter >= REQUESTS_BEFORE_CHECK_LOCATION && locationRequestsCounter.compareAndSet(counter, 0)) {
				if (System.currentTimeMillis() - lastTimeLocationFixed > LOCATION_TIMEOUT_TO_BE_STALE) {
					location = null;
				}
			}
		}
		return location;
	}

	@Nullable
	public net.osmand.Location getLastStaleKnownLocation() {
		net.osmand.Location newLoc = getLastKnownLocation();
		// 白い熊 地図 +088: the last position is kept across starts and shown (grey) until a current one comes; it
		// is no longer dropped after twenty minutes either
		if (newLoc == null && cachedLocation == null && !chizuLastSeeded) {
			chizuLastSeeded = true;
			cachedLocation = net.osmand.plus.chizu.ChizuLastPosition.load(app);
			cachedLocationTimeFix = System.currentTimeMillis();
		}
		if (newLoc == null && cachedLocation != null) {
			// kept
		} else {
			cachedLocation = newLoc;
			cachedLocationTimeFix = lastTimeLocationFixed;
		}
		return cachedLocation;
	}

	public void showNavigationInfo(TargetPoint pointToNavigate, Context uiActivity) {
		getNavigationInfo().show(pointToNavigate, getHeading(), uiActivity);

	}

	public OsmAndLocationSimulation getLocationSimulation() {
		return locationSimulation;
	}

	public static class GPSInfo {
		public int foundSatellites;
		public int usedSatellites;
		public boolean fixed;

		public void reset() {
			fixed = false;
			foundSatellites = 0;
			usedSatellites = 0;
		}
	}

	public boolean checkGPSEnabled(Context context) {
		if (!isGPSEnabled() && !isNetworkEnabled()) {
			// notify user
			AlertDialog.Builder dialog = new AlertDialog.Builder(context);
			dialog.setMessage(context.getResources().getString(R.string.gps_network_not_enabled));
			dialog.setPositiveButton(context.getResources().getString(R.string.shared_string_settings), (paramDialogInterface, paramInt) -> {
				Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
				AndroidUtils.startActivityIfSafe(context, intent);
			});
			dialog.setNegativeButton(context.getString(R.string.shared_string_cancel), null);
			dialog.show();
			return false;
		}
		return true;
	}

	public boolean isGPSEnabled() {
		try {
			LocationManager manager = (LocationManager) app.getSystemService(LOCATION_SERVICE);
			return manager.isProviderEnabled(GPS_PROVIDER);
		} catch (Exception e) {
			LOG.debug(e);
		}
		return false;
	}

	public boolean isNetworkEnabled() {
		try {
			LocationManager manager = (LocationManager) app.getSystemService(LOCATION_SERVICE);
			return manager.isProviderEnabled(NETWORK_PROVIDER);
		} catch (Exception e) {
			LOG.debug(e);
		}
		return false;
	}

	public static boolean isLocationPermissionAvailable(@NonNull Context context) {
		boolean accessFineLocation = hasFineLocationPermission(context);
		boolean accessCoarseLocation = hasCoarseLocationPermission(context);
		return accessFineLocation || accessCoarseLocation;
	}

	public static boolean hasFineLocationPermission(@NonNull Context context) {
		return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
				== PackageManager.PERMISSION_GRANTED;
	}

	public static boolean hasCoarseLocationPermission(@NonNull Context context) {
		return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
				== PackageManager.PERMISSION_GRANTED;
	}

	public static void requestFineLocationPermissionIfNeeded(Activity activity) {
		if (!isLocationPermissionAvailable(activity)) {
			ActivityCompat.requestPermissions(activity, new String[] {
							Manifest.permission.ACCESS_FINE_LOCATION,
							Manifest.permission.ACCESS_COARSE_LOCATION},
					REQUEST_LOCATION_PERMISSION);
		}
	}
}
