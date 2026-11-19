package net.osmand.plus.chizu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import net.osmand.Location;
import net.osmand.core.android.MapRendererView;
import net.osmand.data.RotatedTileBox;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.simulation.SimulationProvider;
import net.osmand.plus.utils.NativeUtilities;
import net.osmand.util.MapUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 白い熊 地図 +088: a position from the Wi-Fi networks in range, learned and kept on the phone alone (白い熊,
 * 2026-11-18).
 * <p>
 * The phone drives in aeroplane mode without a SIM. Its satellite receiver gets no fix indoors or in the
 * car, and the system's network locator — Huawei's, resolving the networks around online — answered at
 * 22:00 and returned nothing at 22:30 from the same chair. This asks nobody: 地図 scans the networks itself
 * and looks them up in what it has learned.
 * <ul>
 * <li><b>Learned while driving and walking.</b> Whenever the position is a good one — the car's own GPS, or a
 * satellite fix of {@value #SAT_ACC_M} m or better, at no more than {@value #LEARN_MAX_MPS} m/s — each network
 * in range is noted with where it was heard and how strongly. A better kind of position replaces a worse
 * one for the same network (taught over the car's over a satellite fix), never the other way round.</li>
 * <li><b>Taught by hand.</b> With no position, 白い熊 puts the crosshair where they stand
 * ({@link ChizuWifiTeach}); the networks heard there and their strengths are kept as a <i>place</i>. Standing
 * there again, the scan matches the place's picture and the position is the taught point itself. Teaching
 * the same spot again averages into it.</li>
 * <li><b>Networks that move are ignored</b> — a phone's hotspot, a bus, the car's own Wi-Fi and modem, which
 * are in range on every drive: a network heard {@value #MOVED_M} m from where it was is reset once and
 * dropped for good the second time.</li>
 * <li><b>Sure or silent.</b> An estimate is given only from a matching taught place, or from two or more
 * known networks that agree; otherwise there is none, and the pill offers teaching.</li>
 * </ul>
 * Everything is kept in the {@link ChizuTheme#PREFS_NAME} file, so it travels with every export. It is a list
 * of network addresses with coordinates — where 白い熊 lives and goes — and never leaves the phone otherwise.
 * <p>
 * It cannot help the satellite receiver start: Android refuses a position handed to the chip by an app.
 * <p>
 * +090: <b>an estimate must be possible</b> — one among known networks that lies farther from the last
 * position than {@value #JUMP_FREE_M} m plus {@value #JUMP_MPS} m/s since then is dropped (a taught place is
 * believed regardless). <b>A favourite within {@value #NAMED_M} m names the place</b> in the pill. And
 * <b>what is contradicted or old goes</b>: teaching a point where the scan still matches a taught place
 * more than {@value #CONTRADICTED_M} m away forgets that place; a learned network not heard for
 * {@value #FADE_DAYS} days is dropped, and when the store is full the one longest unheard makes room.
 */
public final class ChizuWifiLocator {

	public static final String PROVIDER = "wifi";

	private static final String KEY_ON = "wifi_locator_on";
	private static final String KEY_COVERAGE = "wifi_locator_coverage";
	private static final String KEY_IN_CAR = "wifi_locator_in_car";
	private static final String KEY_DB = "wifi_locator_db";

	static final int TIER_SAT = 1;
	static final int TIER_CAR = 2;
	static final int TIER_TAUGHT = 3;

	private static final float SAT_ACC_M = 12f;
	private static final float CAR_ACC_M = 10f;
	private static final float LEARN_MAX_MPS = 14f;
	private static final long FIX_FRESH_MS = 3000;
	private static final double MOVED_M = 400;
	private static final int HEARD_DBM = -88; // weaker than this is not noted
	private static final int STRONG_DBM = -85; // a network this strong counts for a place's picture
	private static final int MISSING_DBM = -95;
	private static final float MATCH_DB = 12f; // mean difference at which a scan is a taught place
	private static final double MERGE_M = 15; // teaching this near a place is teaching that place
	private static final float WEIGHT_CAP = 3000f;
	private static final int MAX_NETS = 4000;
	private static final long SCAN_SLOW_MS = 30_000; // the system allows four scans in two minutes
	private static final long SCAN_FAST_MS = 6000; // with the scan limit switched off
	private static final long SCAN_FRESH_MS = 60_000;
	private static final long RESULT_FRESH_US = 12_000_000L;
	private static final long SURE_MS = 100_000;
	private static final long SAVE_EVERY_MS = 60_000;
	private static final long TEACH_SAMPLES_MS = 25_000;
	private static final double JUMP_FREE_M = 100;
	private static final double JUMP_MPS = 40;
	private static final double NAMED_M = 30;
	private static final double CONTRADICTED_M = 30;
	private static final int FADE_DAYS = 180;
	private static volatile String placeName;
	private static final int MSG_ID = net.osmand.plus.OsmAndConstants.UI_HANDLER_LOCATION_SERVICE + 34;

	/** Where a network was heard, by the best kind of position it was ever heard with. */
	private static final class Net {
		double lat, lon;
		float sumW;
		int n;
		int tier;
		int day;
		int moves;
		boolean mobile;
	}

	/** A taught place: the point, and the picture of the networks there. */
	private static final class Place {
		double lat, lon;
		int n;
		long time;
		final HashMap<String, Float> rssi = new HashMap<>();
		final HashMap<String, Integer> seen = new HashMap<>();
	}

	private static final class Seen {
		final String bssid;
		final int rssi;

		Seen(String bssid, int rssi) {
			this.bssid = bssid;
			this.rssi = rssi;
		}
	}

	private static final Object LOCK = new Object();
	private static final HashMap<String, Net> nets = new HashMap<>();
	private static final ArrayList<Place> places = new ArrayList<>();
	private static boolean loaded;
	private static boolean dirty;
	private static long savedMs;
	private static int version; // counts changes, for the coverage snapshot

	private static final Set<String> wanted = new HashSet<>();
	private static BroadcastReceiver receiver;
	private static List<Seen> lastScan = Collections.emptyList();
	private static long lastScanMs;
	private static volatile long estimateMs;
	private static volatile float estimateAcc;
	private static String lastState = "";
	private static Place teachPlace;
	private static long teachUntilMs;

	// the coverage overlay's snapshot
	private static double[] covLat = new double[0];
	private static double[] covLon = new double[0];
	private static int[] covTier = new int[0];
	private static double[] covPlaceLat = new double[0];
	private static double[] covPlaceLon = new double[0];
	private static int covVersion = -1;

	private ChizuWifiLocator() {
	}

	// ---------- switches ----------

	private static SharedPreferences prefs(@NonNull Context c) {
		return c.getApplicationContext().getSharedPreferences(ChizuTheme.PREFS_NAME, Context.MODE_PRIVATE);
	}

	public static boolean isOn(@NonNull Context c) {
		return prefs(c).getBoolean(KEY_ON, true);
	}

	public static void setOn(@NonNull OsmandApplication app, boolean on) {
		prefs(app).edit().putBoolean(KEY_ON, on).apply();
		ChizuDriveLog.log("wifi locator switched " + (on ? "on" : "off"));
		if (on) {
			apply(app);
		} else {
			estimateMs = 0;
			apply(app);
		}
		app.getOsmandMap().refreshMap();
	}

	public static boolean isCoverageShown(@NonNull Context c) {
		return prefs(c).getBoolean(KEY_COVERAGE, false);
	}

	public static void setCoverageShown(@NonNull OsmandApplication app, boolean shown) {
		prefs(app).edit().putBoolean(KEY_COVERAGE, shown).apply();
		app.getOsmandMap().refreshMap();
	}

	/** Scanning while the car is connected: it teaches the locator the roads, and may disturb the wireless link. */
	public static boolean isLearningInCar(@NonNull Context c) {
		return prefs(c).getBoolean(KEY_IN_CAR, true);
	}

	public static void setLearningInCar(@NonNull OsmandApplication app, boolean on) {
		prefs(app).edit().putBoolean(KEY_IN_CAR, on).apply();
		apply(app);
	}

	/** The system's limit of four scans in two minutes is in force (it can be lifted in the developer options). */
	public static boolean isScanLimited(@NonNull Context c) {
		try {
			WifiManager wifi = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
			if (android.os.Build.VERSION.SDK_INT >= 30) {
				return wifi.isScanThrottleEnabled();
			}
			return android.provider.Settings.Global.getInt(c.getContentResolver(), "wifi_scan_throttle_enabled", 1) != 0;
		} catch (Exception e) {
			return true;
		}
	}

	// ---------- running ----------

	/** {@code who} (“phone”, “car”) has the map up and wants positions. Main thread. */
	public static void start(@NonNull OsmandApplication app, @NonNull String who) {
		wanted.add(who);
		apply(app);
	}

	public static void stop(@NonNull OsmandApplication app, @NonNull String who) {
		wanted.remove(who);
		apply(app);
	}

	private static boolean running(@NonNull OsmandApplication app) {
		if (!isOn(app) || wanted.isEmpty()) {
			return false;
		}
		// the car alone (the phone's map is not up): only when learning in the car is wanted
		return wanted.contains("phone") || isLearningInCar(app);
	}

	private static void apply(@NonNull OsmandApplication app) {
		boolean run = running(app);
		if (run && receiver == null) {
			load(app);
			receiver = new BroadcastReceiver() {
				@Override
				public void onReceive(Context context, Intent intent) {
					try {
						onResults(app);
					} catch (Throwable e) {
						ChizuDriveLog.log("wifi locator: results failed: " + e);
					}
				}
			};
			try {
				ContextCompat.registerReceiver(app, receiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
						ContextCompat.RECEIVER_NOT_EXPORTED);
			} catch (Exception e) {
				ChizuDriveLog.log("wifi locator: cannot listen for scans: " + e);
				receiver = null;
				return;
			}
			int[] counts = counts();
			ChizuDriveLog.log(String.format(Locale.US, "wifi locator runs for %s: %d networks known (%d moving), %d taught places, scan limit %s",
					wanted, counts[0], counts[1], counts[2], isScanLimited(app) ? "on" : "off"));
			app.runInUIThreadAndCancelPrevious(MSG_ID, () -> tick(app), 500);
		} else if (!run && receiver != null) {
			try {
				app.unregisterReceiver(receiver);
			} catch (Exception e) {
				// was not registered
			}
			receiver = null;
			save(app);
			ChizuDriveLog.log("wifi locator stops");
		}
	}

	private static void tick(@NonNull OsmandApplication app) {
		if (receiver == null) {
			return;
		}
		long now = SystemClock.elapsedRealtime();
		boolean teaching = teachPlace != null && now < teachUntilMs;
		if (teaching || source(app) != null || needsPosition(app)) {
			scan(app);
		}
		if (dirty && now - savedMs > SAVE_EVERY_MS) {
			save(app);
		}
		long every = teaching ? 8000 : isScanLimited(app) || app.getCarNavigationSession() != null ? SCAN_SLOW_MS : SCAN_FAST_MS;
		app.runInUIThreadAndCancelPrevious(MSG_ID, () -> tick(app), every);
	}

	private static void scan(@NonNull OsmandApplication app) {
		try {
			WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
			if (!wifi.startScan()) {
				// refused (the scan limit): the last results are read all the same
				onResults(app);
			}
		} catch (Exception e) {
			ChizuDriveLog.log("wifi locator: scan failed: " + e);
		}
	}

	/** No satellite, car or estimated position: the locator's own is wanted — and beside a satellite one when not driving (+092). */
	private static boolean needsPosition(@NonNull OsmandApplication app) {
		ChizuFix.Kind kind = ChizuFix.kind(app);
		return kind == ChizuFix.Kind.NONE || kind == ChizuFix.Kind.NET || kind == ChizuFix.Kind.WIFI
				|| (kind == ChizuFix.Kind.SAT && !places.isEmpty() && !app.getLocationProvider().chizuIsOnTheRoad());
	}

	// +092: a taught place holds against a satellite fix beside it, see locate()
	private static final float MATCH_KEEP_DB = 16f;
	private static String lastNoHold = "";

	/** The taught place is shown although the receiver has a fix of its own right beside it. */
	public static boolean holdsAgainstSat() {
		long at = taughtWinsMs;
		return at != 0 && SystemClock.elapsedRealtime() - at <= SURE_MS && ChizuGnssLog.fixWithin(5000);
	}

	/**
	 * +094: the kind of position has changed — a satellite fix has come. The last scan, if fresh, is judged
	 * against it at once instead of with the next scan up to 30 s later (2026-11-19: the taught place took
	 * over 31 s after the fix).
	 */
	public static void onKindChanged(@NonNull OsmandApplication app) {
		app.runInUIThread(() -> {
			if (receiver != null && SystemClock.elapsedRealtime() - lastScanMs <= SCAN_FRESH_MS) {
				locate(app);
			} else if (receiver != null) {
				scan(app);
			}
		});
	}

	private static final double TAUGHT_WINS_M = 30;
	private static final float TAUGHT_WINS_MPS = 1.5f;
	private static volatile long taughtWinsMs;
	private static volatile double taughtLat, taughtLon;
	private static volatile boolean notOnTheRoad;

	@SuppressWarnings("MissingPermission")
	private static void onResults(@NonNull OsmandApplication app) {
		WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
		List<ScanResult> results = wifi.getScanResults();
		long nowUs = SystemClock.elapsedRealtime() * 1000;
		List<Seen> seen = new ArrayList<>();
		if (results != null) {
			for (ScanResult r : results) {
				if (r.BSSID == null || nowUs - r.timestamp > RESULT_FRESH_US) {
					continue; // an entry the system kept from an earlier scan
				}
				seen.add(new Seen(r.BSSID.replace(":", "").toLowerCase(Locale.US), r.level));
			}
		}
		long now = SystemClock.elapsedRealtime();
		if (seen.isEmpty() && now - lastScanMs < SCAN_FRESH_MS) {
			return; // nothing new in this answer; the last picture stands until it is old
		}
		lastScan = seen;
		lastScanMs = now;
		synchronized (LOCK) {
			Location from = source(app);
			if (teachPlace != null && now < teachUntilMs) {
				sample(teachPlace, seen);
			} else if (from != null) {
				int tier = ChizuCarGps.PROVIDER.equals(from.getProvider()) ? TIER_CAR : TIER_SAT;
				for (Seen s : seen) {
					if (s.rssi >= HEARD_DBM) {
						observe(s.bssid, from.getLatitude(), from.getLongitude(), s.rssi, tier);
					}
				}
			}
		}
		locate(app);
	}

	/** The good position a scan may be learned from, or null. */
	@Nullable
	private static Location source(@NonNull OsmandApplication app) {
		Location car = ChizuCarGps.lastGood();
		if (car != null) {
			return car.hasAccuracy() && car.getAccuracy() <= CAR_ACC_M && (!car.hasSpeed() || car.getSpeed() <= LEARN_MAX_MPS) ? car : null;
		}
		Location l = app.getLocationProvider().getLastKnownLocation();
		if (l == null || !l.hasSpeed() || !ChizuFix.satellite(l) || !l.hasAccuracy() || l.getAccuracy() > SAT_ACC_M || l.getSpeed() > LEARN_MAX_MPS
				|| !SimulationProvider.isNotSimulatedLocation(l) || PROVIDER.equals(l.getProvider())
				|| "network".equals(l.getProvider()) || app.getLocationProvider().isChizuEstimating()
				|| Math.abs(System.currentTimeMillis() - l.getTime()) > FIX_FRESH_MS) {
			return null;
		}
		return l;
	}

	// ---------- learning ----------

	private static float weight(int rssi) {
		return (float) Math.max(1, Math.min(300, Math.pow(10, (rssi + 95) / 20.0)));
	}

	private static int today() {
		return (int) (System.currentTimeMillis() / 86_400_000L);
	}

	private static void observe(@NonNull String bssid, double lat, double lon, int rssi, int tier) {
		Net net = nets.get(bssid);
		float w = weight(rssi);
		if (net == null) {
			if (nets.size() >= MAX_NETS && !evictOldest()) {
				return;
			}
			net = new Net();
			net.lat = lat;
			net.lon = lon;
			net.sumW = w;
			net.n = 1;
			net.tier = tier;
			net.day = today();
			nets.put(bssid, net);
			changed();
			return;
		}
		if (net.mobile) {
			return;
		}
		double d = MapUtils.getDistance(net.lat, net.lon, lat, lon);
		if (d > MOVED_M) {
			// heard far from where it was: moved once (a router taken along), a moving network the second time
			net.moves++;
			if (net.moves >= 2) {
				net.mobile = true;
				ChizuDriveLog.log("wifi locator: a network heard " + Math.round(d) + " m from where it was is a moving one, ignored from now on");
			} else if (tier >= net.tier) {
				net.lat = lat;
				net.lon = lon;
				net.sumW = w;
				net.n = 1;
				net.tier = tier;
			}
			net.day = today();
			changed();
			return;
		}
		if (tier < net.tier) {
			return; // a better kind of position holds this one
		}
		if (tier > net.tier) {
			net.lat = lat;
			net.lon = lon;
			net.sumW = w;
			net.n = 1;
			net.tier = tier;
		} else {
			net.lat = (net.lat * net.sumW + lat * w) / (net.sumW + w);
			net.lon = (net.lon * net.sumW + lon * w) / (net.sumW + w);
			net.sumW = Math.min(WEIGHT_CAP, net.sumW + w);
			net.n++;
		}
		net.day = today();
		changed();
	}

	/** Room for a new network: the learned one longest unheard goes. Taught ones stay. */
	private static boolean evictOldest() {
		String oldest = null;
		int day = Integer.MAX_VALUE;
		for (Map.Entry<String, Net> e : nets.entrySet()) {
			Net n = e.getValue();
			if (n.tier != TIER_TAUGHT && n.day < day) {
				day = n.day;
				oldest = e.getKey();
			}
		}
		if (oldest == null) {
			return false;
		}
		nets.remove(oldest);
		return true;
	}

	private static void changed() {
		dirty = true;
		version++;
	}

	// ---------- teaching ----------

	/** True when a position can be taught now: a fresh scan with a network strong enough to go by. */
	public static boolean teachable(@NonNull Context c) {
		if (!isOn(c) || receiver == null || SystemClock.elapsedRealtime() - lastScanMs > SCAN_FRESH_MS) {
			return false;
		}
		for (Seen s : lastScan) {
			if (s.rssi >= STRONG_DBM) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 白い熊 stands at this point. The networks in range now, and those of the next scans for
	 * {@value #TEACH_SAMPLES_MS} ms, are this place's picture. Returns how many networks the first picture holds.
	 */
	public static int teach(@NonNull OsmandApplication app, double lat, double lon) {
		load(app);
		long now = SystemClock.elapsedRealtime();
		int count;
		synchronized (LOCK) {
			// a taught place the scan here still matches, yet lying elsewhere, is contradicted: forgotten
			if (now - lastScanMs <= SCAN_FRESH_MS) {
				for (Iterator<Place> it = places.iterator(); it.hasNext(); ) {
					Place p = it.next();
					double d = MapUtils.getDistance(p.lat, p.lon, lat, lon);
					if (d > CONTRADICTED_M && score(p, lastScan) <= MATCH_DB) {
						it.remove();
						for (String bssid : p.rssi.keySet()) {
							Net net = nets.get(bssid);
							if (net != null && net.tier == TIER_TAUGHT) {
								nets.remove(bssid);
							}
						}
						ChizuDriveLog.log("wifi locator: a taught place " + Math.round(d) + " m away matched this spot, forgotten as contradicted");
					}
				}
			}
			Place place = null;
			double best = MERGE_M;
			for (Place p : places) {
				double d = MapUtils.getDistance(p.lat, p.lon, lat, lon);
				if (d <= best) {
					best = d;
					place = p;
				}
			}
			if (place == null) {
				place = new Place();
				place.lat = lat;
				place.lon = lon;
				places.add(place);
			} else {
				// taught again: the point is the mean of the teachings
				place.lat = (place.lat * place.n + lat) / (place.n + 1);
				place.lon = (place.lon * place.n + lon) / (place.n + 1);
			}
			place.n++;
			place.time = System.currentTimeMillis();
			teachPlace = place;
			teachUntilMs = now + TEACH_SAMPLES_MS;
			count = now - lastScanMs <= SCAN_FRESH_MS ? sample(place, lastScan) : 0;
			changed();
		}
		ChizuDriveLog.log(String.format(Locale.US, "wifi locator: taught %.6f,%.6f from %d networks, place taught %d times, %d places",
				lat, lon, count, teachPlace.n, places.size()));
		save(app);
		scan(app); // more samples of the same spot
		app.runInUIThreadAndCancelPrevious(MSG_ID, () -> tick(app), 8000);
		locate(app);
		return count;
	}

	/** One scan at a taught place: into its picture, and each network noted at the place. */
	private static int sample(@NonNull Place place, @NonNull List<Seen> seen) {
		int count = 0;
		for (Seen s : seen) {
			if (s.rssi < HEARD_DBM) {
				continue;
			}
			Integer times = place.seen.get(s.bssid);
			Float mean = place.rssi.get(s.bssid);
			int k = times == null ? 0 : times;
			place.rssi.put(s.bssid, mean == null ? s.rssi : (mean * k + s.rssi) / (k + 1));
			place.seen.put(s.bssid, k + 1);
			observe(s.bssid, place.lat, place.lon, s.rssi, TIER_TAUGHT);
			count++;
		}
		changed();
		return count;
	}

	/** Forgets the taught place nearest to the point, within {@code withinM}. Returns whether there was one. */
	public static boolean forgetPlaceNear(@NonNull OsmandApplication app, double lat, double lon, double withinM) {
		load(app);
		synchronized (LOCK) {
			Place nearest = null;
			double best = withinM;
			for (Place p : places) {
				double d = MapUtils.getDistance(p.lat, p.lon, lat, lon);
				if (d <= best) {
					best = d;
					nearest = p;
				}
			}
			if (nearest == null) {
				return false;
			}
			places.remove(nearest);
			// its networks are no longer held by the taught point: learned again from the next good position
			for (String bssid : nearest.rssi.keySet()) {
				Net net = nets.get(bssid);
				if (net != null && net.tier == TIER_TAUGHT) {
					nets.remove(bssid);
				}
			}
			if (teachPlace == nearest) {
				teachPlace = null;
			}
			changed();
		}
		ChizuDriveLog.log("wifi locator: a taught place forgotten, " + places.size() + " left");
		save(app);
		estimateMs = 0;
		app.getOsmandMap().refreshMap();
		return true;
	}

	public static void forgetAll(@NonNull OsmandApplication app) {
		synchronized (LOCK) {
			nets.clear();
			places.clear();
			teachPlace = null;
			loaded = true;
			changed();
		}
		ChizuDriveLog.log("wifi locator: everything learned forgotten");
		save(app);
		estimateMs = 0;
		app.getOsmandMap().refreshMap();
	}

	// ---------- the estimate ----------

	/** The locator placed the phone within the last {@value #SURE_MS} ms. */
	public static boolean sure() {
		long at = estimateMs;
		return at != 0 && SystemClock.elapsedRealtime() - at <= SURE_MS;
	}

	/** A position from elsewhere that knows no speed — the system's network locator — gives way while ours is sure. */
	public static boolean suppresses(@Nullable Location l) {
		if (l == null || !sure() || PROVIDER.equals(l.getProvider()) || ChizuCarGps.PROVIDER.equals(l.getProvider())
				|| !SimulationProvider.isNotSimulatedLocation(l)) {
			return false;
		}
		if (!ChizuFix.satellite(l)) {
			return true; // +096: by what the receiver uses, not by whether the position carries a speed
		}
		// +092: standing at a taught place, not driving — the taught point holds against the receiver's own fix
		// within TAUGHT_WINS_M of it. 2026-11-18 23:55: taught by hand at the desk, the receiver put the phone
		// 9.8 m off in the garden for a minute on end, claiming 4.9 m.
		long at = taughtWinsMs;
		if (at != 0 && SystemClock.elapsedRealtime() - at <= SURE_MS && notOnTheRoad && l.getSpeed() < TAUGHT_WINS_MPS
				&& MapUtils.getDistance(l.getLatitude(), l.getLongitude(), taughtLat, taughtLon) <= TAUGHT_WINS_M) {
			ChizuFix.noteSatSeen();
			return true;
		}
		return false;
	}

	private static void locate(@NonNull OsmandApplication app) {
		if (!needsPosition(app) || app.getRoutingHelper().isFollowingMode()) {
			return;
		}
		// +092: with a satellite fix, the locator speaks only as a taught place right beside that fix
		notOnTheRoad = !app.getLocationProvider().chizuIsOnTheRoad();
		Location sat = ChizuFix.kind(app) == ChizuFix.Kind.SAT ? app.getLocationProvider().getLastKnownLocation() : null;
		// +094: a place that is holding, or has a satellite fix right beside it to vouch for it, is matched more
		// loosely — on 2026-11-19 it matched at 8–10 dB of 12 allowed, and one noisier scan would have let it go
		boolean holding = taughtWinsMs != 0 && SystemClock.elapsedRealtime() - taughtWinsMs <= SURE_MS;
		double[] est;
		synchronized (LOCK) {
			est = estimate(lastScan, holding || sat != null ? MATCH_KEEP_DB : MATCH_DB);
		}
		if (sat != null) {
			double beside = est == null ? -1 : MapUtils.getDistance(sat.getLatitude(), sat.getLongitude(), est[0], est[1]);
			String why = est == null ? "the scan gives no estimate (" + lastScan.size() + " networks)"
					: est[3] == 0 ? "the scan matches no taught place"
					: !notOnTheRoad ? "driving"
					: sat.hasSpeed() && sat.getSpeed() >= TAUGHT_WINS_MPS ? "moving"
					: beside > TAUGHT_WINS_M ? String.format(Locale.US, "the taught place is %.0f m from the fix", beside) : null;
			if (why != null) {
				if (!why.equals(lastNoHold)) {
					lastNoHold = why;
					ChizuDriveLog.log("wifi locator: the satellite fix stands, " + why);
				}
				taughtWinsMs = 0;
				return;
			}
			lastNoHold = "";
			ChizuDriveLog.log(String.format(Locale.US, "wifi locator: the taught place holds against a satellite fix %.0f m beside it", beside));
		}
		if (est != null && est[3] > 0) {
			taughtLat = est[0];
			taughtLon = est[1];
			taughtWinsMs = SystemClock.elapsedRealtime();
		} else {
			taughtWinsMs = 0;
		}
		String state;
		Location before = app.getLocationProvider().getLastStaleKnownLocation();
		if (est != null && est[3] == 0 && before != null && before.getTime() > 0) {
			double far = MapUtils.getDistance(before.getLatitude(), before.getLongitude(), est[0], est[1]);
			double allowed = JUMP_FREE_M + JUMP_MPS * Math.max(0, (System.currentTimeMillis() - before.getTime()) / 1000.0);
			if (far > allowed) {
				ChizuDriveLog.log(String.format(Locale.US, "wifi locator: an estimate %.0f m from the last position dropped, %.0f m is possible",
						far, allowed));
				est = null;
			}
		}
		if (est == null) {
			placeName = null;
			state = "no estimate from " + lastScan.size() + " networks";
		} else {
			placeName = nameAt(app, est[0], est[1]);
			state = est[3] > 0 ? "at a taught place" : "among known networks";
			Location loc = new Location(PROVIDER);
			loc.setLatitude(est[0]);
			loc.setLongitude(est[1]);
			loc.setAccuracy((float) est[2]);
			loc.setTime(System.currentTimeMillis());
			estimateMs = SystemClock.elapsedRealtime();
			estimateAcc = (float) est[2];
			app.getLocationProvider().chizuSetWifiLocation(loc);
		}
		if (!state.equals(lastState)) {
			lastState = state;
			ChizuDriveLog.log("wifi locator: " + state + (est == null ? ""
					: String.format(Locale.US, ", %.6f,%.6f ±%.0f m from %d networks", est[0], est[1], est[2], lastScan.size())));
		}
		app.getOsmandMap().refreshMap();
	}

	/** The favourite the estimate lands on, if one lies within {@value #NAMED_M} m. */
	@Nullable
	private static String nameAt(@NonNull OsmandApplication app, double lat, double lon) {
		try {
			String name = null;
			double best = NAMED_M;
			for (net.osmand.data.FavouritePoint f : app.getFavoritesHelper().getFavouritePoints()) {
				double d = MapUtils.getDistance(f.getLatitude(), f.getLongitude(), lat, lon);
				if (d <= best) {
					best = d;
					name = f.getDisplayName(app);
				}
			}
			return name == null || name.trim().isEmpty() ? null : name.trim();
		} catch (Exception e) {
			return null;
		}
	}

	/** The name of the place the locator has put the phone at, or null. */
	@Nullable
	public static String placeName() {
		return sure() ? placeName : null;
	}

	/** How far a scan is from a taught place's picture, in dB; a large number when they share too little. Under LOCK. */
	private static double score(@NonNull Place p, @NonNull List<Seen> scan) {
		HashMap<String, Integer> now = new HashMap<>();
		Set<String> union = new HashSet<>();
		for (Seen s : scan) {
			now.put(s.bssid, s.rssi);
			if (s.rssi >= STRONG_DBM) {
				union.add(s.bssid);
			}
		}
		int strongThere = 0;
		for (Map.Entry<String, Float> e : p.rssi.entrySet()) {
			if (e.getValue() >= STRONG_DBM) {
				union.add(e.getKey());
				strongThere++;
			}
		}
		if (union.isEmpty() || strongThere == 0) {
			return Double.MAX_VALUE;
		}
		int common = 0;
		double sum = 0;
		for (String bssid : union) {
			Float there = p.rssi.get(bssid);
			Integer here = now.get(bssid);
			if (there != null && here != null) {
				common++;
			}
			sum += Math.abs((there == null ? MISSING_DBM : there) - (here == null ? MISSING_DBM : here));
		}
		return common < Math.min(2, strongThere) ? Double.MAX_VALUE : sum / union.size();
	}

	/** {lat, lon, accuracy, 1 when from a taught place}, or null when not sure. Under LOCK. */
	@Nullable
	private static double[] estimate(@NonNull List<Seen> scan, float matchDb) {
		if (scan.isEmpty()) {
			return null;
		}
		HashMap<String, Integer> now = new HashMap<>();
		for (Seen s : scan) {
			now.put(s.bssid, s.rssi);
		}
		// 1. a taught place whose picture this scan is
		double bestScore = Double.MAX_VALUE;
		List<Object[]> matches = new ArrayList<>();
		for (Place p : places) {
			double score = score(p, scan);
			if (score <= matchDb) {
				matches.add(new Object[] {p, score});
				bestScore = Math.min(bestScore, score);
			}
		}
		if (!matches.isEmpty()) {
			double lat = 0, lon = 0, sw = 0;
			for (Object[] m : matches) {
				double score = (Double) m[1];
				if (score > bestScore + 6) {
					continue;
				}
				Place p = (Place) m[0];
				double w = 1 / ((score + 2) * (score + 2));
				lat += p.lat * w;
				lon += p.lon * w;
				sw += w;
			}
			return new double[] {lat / sw, lon / sw, Math.max(8, Math.min(25, 6 + bestScore)), 1};
		}
		// 2. among the networks known: where they were heard, the strong ones counting most
		List<double[]> anchors = new ArrayList<>();
		for (Seen s : scan) {
			Net net = s.rssi >= -90 ? nets.get(s.bssid) : null;
			if (net != null && !net.mobile) {
				anchors.add(new double[] {net.lat, net.lon, weight(s.rssi) * net.tier});
			}
		}
		if (anchors.size() < 2) {
			return null;
		}
		double[] c = centroid(anchors);
		List<Double> ds = new ArrayList<>();
		for (double[] a : anchors) {
			ds.add(MapUtils.getDistance(c[0], c[1], a[0], a[1]));
		}
		List<Double> sorted = new ArrayList<>(ds);
		Collections.sort(sorted);
		double limit = Math.max(150, 2 * sorted.get(sorted.size() / 2));
		List<double[]> kept = new ArrayList<>();
		for (int i = 0; i < anchors.size(); i++) {
			if (ds.get(i) <= limit) {
				kept.add(anchors.get(i));
			}
		}
		if (kept.size() < 2) {
			return null;
		}
		c = centroid(kept);
		double sw = 0, sd = 0;
		for (double[] a : kept) {
			double d = MapUtils.getDistance(c[0], c[1], a[0], a[1]);
			sd += a[2] * d * d;
			sw += a[2];
		}
		double acc = Math.max(15, Math.sqrt(sd / sw) + 10);
		return acc > 80 ? null : new double[] {c[0], c[1], acc, 0};
	}

	private static double[] centroid(@NonNull List<double[]> anchors) {
		double lat = 0, lon = 0, sw = 0;
		for (double[] a : anchors) {
			lat += a[0] * a[2];
			lon += a[1] * a[2];
			sw += a[2];
		}
		return new double[] {lat / sw, lon / sw};
	}

	// ---------- what it knows ----------

	/** {networks known, of them moving, taught places, networks in the last scan}. */
	@NonNull
	public static int[] counts() {
		synchronized (LOCK) {
			int mobile = 0;
			for (Net n : nets.values()) {
				if (n.mobile) {
					mobile++;
				}
			}
			return new int[] {nets.size(), mobile, places.size(),
					SystemClock.elapsedRealtime() - lastScanMs <= SCAN_FRESH_MS ? lastScan.size() : 0};
		}
	}

	@NonNull
	public static String describe(@NonNull OsmandApplication app) {
		load(app);
		int[] c = counts();
		int knownNow = 0;
		synchronized (LOCK) {
			for (Seen s : lastScan) {
				Net n = nets.get(s.bssid);
				if (n != null && !n.mobile) {
					knownNow++;
				}
			}
		}
		return app.getString(R.string.chizu_wifi_details, c[0] - c[1], c[1], c[2], c[3], knownNow,
				sure() ? app.getString(R.string.chizu_wifi_details_sure, Math.round(estimateAcc)) : app.getString(R.string.chizu_wifi_details_unsure));
	}

	// ---------- the coverage overlay ----------

	/** Draws what is known: a small circle where each network was heard, a crossed one at each taught place. */
	public static void drawCoverage(@NonNull OsmandApplication app, @NonNull Canvas canvas, @NonNull RotatedTileBox box,
	                                @Nullable MapRendererView renderer) {
		if (!isCoverageShown(app) || box.getZoom() < 12) {
			return;
		}
		load(app);
		synchronized (LOCK) {
			if (covVersion != version) {
				List<Net> list = new ArrayList<>();
				for (Net n : nets.values()) {
					if (!n.mobile) {
						list.add(n);
					}
				}
				covLat = new double[list.size()];
				covLon = new double[list.size()];
				covTier = new int[list.size()];
				for (int i = 0; i < list.size(); i++) {
					covLat[i] = list.get(i).lat;
					covLon[i] = list.get(i).lon;
					covTier[i] = list.get(i).tier;
				}
				covPlaceLat = new double[places.size()];
				covPlaceLon = new double[places.size()];
				for (int i = 0; i < places.size(); i++) {
					covPlaceLat[i] = places.get(i).lat;
					covPlaceLon[i] = places.get(i).lon;
				}
				covVersion = version;
			}
		}
		float d = app.getResources().getDisplayMetrics().density;
		Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
		edge.setStyle(Paint.Style.STROKE);
		edge.setColor(Color.BLACK);
		Paint line = new Paint(edge);
		line.setColor(0xFFFFFF00);
		double[] lat = covLat, lon = covLon;
		int[] tier = covTier;
		int w = box.getPixWidth(), h = box.getPixHeight();
		for (int i = 0; i < lat.length; i++) {
			if (!box.containsLatLon(lat[i], lon[i])) {
				continue;
			}
			PointF p = NativeUtilities.getPixelFromLatLon(renderer, box, lat[i], lon[i]);
			if (p.x < 0 || p.y < 0 || p.x > w || p.y > h) {
				continue;
			}
			float r = (tier[i] == TIER_TAUGHT ? 4.5f : 3f) * d;
			edge.setStrokeWidth(3 * d);
			line.setStrokeWidth(1.5f * d);
			canvas.drawCircle(p.x, p.y, r, edge);
			canvas.drawCircle(p.x, p.y, r, line);
		}
		double[] plat = covPlaceLat, plon = covPlaceLon;
		for (int i = 0; i < plat.length; i++) {
			if (!box.containsLatLon(plat[i], plon[i])) {
				continue;
			}
			PointF p = NativeUtilities.getPixelFromLatLon(renderer, box, plat[i], plon[i]);
			float r = 9 * d;
			edge.setStrokeWidth(5 * d);
			line.setStrokeWidth(2.5f * d);
			canvas.drawCircle(p.x, p.y, r, edge);
			canvas.drawLine(p.x - r, p.y, p.x + r, p.y, edge);
			canvas.drawLine(p.x, p.y - r, p.x, p.y + r, edge);
			canvas.drawCircle(p.x, p.y, r, line);
			canvas.drawLine(p.x - r, p.y, p.x + r, p.y, line);
			canvas.drawLine(p.x, p.y - r, p.x, p.y + r, line);
		}
	}

	// ---------- storage ----------

	private static void load(@NonNull Context c) {
		synchronized (LOCK) {
			if (loaded) {
				return;
			}
			loaded = true;
			String json = prefs(c).getString(KEY_DB, null);
			if (json == null) {
				return;
			}
			try {
				JSONObject root = new JSONObject(json);
				JSONObject n = root.optJSONObject("nets");
				if (n != null) {
					for (Iterator<String> it = n.keys(); it.hasNext(); ) {
						String bssid = it.next();
						JSONArray a = n.getJSONArray(bssid);
						Net net = new Net();
						net.lat = a.getDouble(0);
						net.lon = a.getDouble(1);
						net.sumW = (float) a.getDouble(2);
						net.n = a.getInt(3);
						net.tier = a.getInt(4);
						net.day = a.getInt(5);
						net.moves = a.getInt(6);
						net.mobile = a.getInt(7) != 0;
						if (net.tier != TIER_TAUGHT && today() - net.day > FADE_DAYS) {
							dirty = true;
							continue; // not heard for half a year: faded
						}
						nets.put(bssid, net);
					}
				}
				JSONArray p = root.optJSONArray("places");
				for (int i = 0; p != null && i < p.length(); i++) {
					JSONObject o = p.getJSONObject(i);
					Place place = new Place();
					place.lat = o.getDouble("lat");
					place.lon = o.getDouble("lon");
					place.n = o.optInt("n", 1);
					place.time = o.optLong("time");
					JSONObject r = o.getJSONObject("rssi");
					JSONObject s = o.optJSONObject("seen");
					for (Iterator<String> it = r.keys(); it.hasNext(); ) {
						String bssid = it.next();
						place.rssi.put(bssid, (float) r.getDouble(bssid));
						place.seen.put(bssid, s == null ? 1 : s.optInt(bssid, 1));
					}
					places.add(place);
				}
				version++;
			} catch (Exception e) {
				ChizuDriveLog.log("wifi locator: what was learned could not be read: " + e);
			}
		}
	}

	private static void save(@NonNull Context c) {
		String json;
		synchronized (LOCK) {
			if (!loaded || !dirty) {
				return;
			}
			try {
				JSONObject root = new JSONObject();
				root.put("v", 1);
				JSONObject n = new JSONObject();
				for (Map.Entry<String, Net> e : nets.entrySet()) {
					Net net = e.getValue();
					JSONArray a = new JSONArray();
					a.put(Math.round(net.lat * 1e6) / 1e6).put(Math.round(net.lon * 1e6) / 1e6).put(Math.round(net.sumW))
							.put(net.n).put(net.tier).put(net.day).put(net.moves).put(net.mobile ? 1 : 0);
					n.put(e.getKey(), a);
				}
				root.put("nets", n);
				JSONArray p = new JSONArray();
				for (Place place : places) {
					JSONObject o = new JSONObject();
					o.put("lat", place.lat).put("lon", place.lon).put("n", place.n).put("time", place.time);
					JSONObject r = new JSONObject();
					JSONObject s = new JSONObject();
					for (Map.Entry<String, Float> e : place.rssi.entrySet()) {
						r.put(e.getKey(), Math.round(e.getValue() * 10) / 10.0);
						Integer times = place.seen.get(e.getKey());
						s.put(e.getKey(), times == null ? 1 : times);
					}
					o.put("rssi", r).put("seen", s);
					p.put(o);
				}
				root.put("places", p);
				json = root.toString();
			} catch (Exception e) {
				ChizuDriveLog.log("wifi locator: what was learned could not be written: " + e);
				return;
			}
			dirty = false;
			savedMs = SystemClock.elapsedRealtime();
		}
		prefs(c).edit().putString(KEY_DB, json).apply();
	}
}
