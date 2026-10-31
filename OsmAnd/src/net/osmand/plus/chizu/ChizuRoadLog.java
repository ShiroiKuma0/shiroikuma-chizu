package net.osmand.plus.chizu;

import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.binary.RouteDataObject;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.routing.RouteCalculationResult;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.router.RouteSegmentResult;
import net.osmand.util.MapUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 白い熊 地図: one row per road section driven, for fitting 白い熊's own speeds per road type, limit and
 * traffic colour. A section ends wherever the road type, the posted limit, tunnel or bridge, the
 * street name or the traffic colour changes; the colour is read every {@link #STEP_M} m between the
 * fixes, and a change between two fixes is timed by interpolation. Logged whenever 地図 sees 白い熊
 * drive, navigating or not, to “/sdcard/tmp/chizu-roads_<day>.tsv”. A stretch with no fixes (a
 * tunnel) is one “gap” row, timed from the fix before to the fix after.
 */
public final class ChizuRoadLog {

	private static final String PREFIX = "chizu-roads_";
	private static final float STEP_M = 20f;
	private static final long GAP_MS = 5_000;
	private static final float DRIVE_START_KMH = 15f;
	private static final float STILL_KMH = 5f;
	private static final long DRIVE_END_STILL_MS = 5 * 60_000;
	private static final long DRIVE_END_NO_FIX_MS = 10 * 60_000;
	private static final float MAX_ACCURACY_M = 25f;

	private static final String HEADER = "day\tdow\tstart\tend\tkind\thighway\ttunnel\tbridge\tlimit\tname\tcolour"
			+ "\tlength_m\ttime_s\tavg_kmh\tmax_kmh\tosmand_kmh\tmine_kmh\tfixes\tnavigating\ton_route\tshort\troad_id\n";

	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "chizu-road-log");
		t.setDaemon(true);
		return t;
	});

	private static volatile OsmandApplication app;
	private static final ChizuGoogleTrafficSource source = new ChizuGoogleTrafficSource();
	private static long lastFixTime;

	// worker thread only
	private static Fix prev;
	private static Section section;
	private static boolean driving;
	private static long stillSince;
	private static Writer out;
	private static String outDay;
	private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
	private static final SimpleDateFormat DOW = new SimpleDateFormat("EEE", Locale.US);
	private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.US);

	private ChizuRoadLog() {
	}

	public static void init(@NonNull OsmandApplication application) {
		app = application;
	}

	/** The road under a fix, as one section key. */
	private static final class Road {
		final String highway;
		final boolean tunnel;
		final boolean bridge;
		final int limit;
		final String name;
		final long id;

		Road(@NonNull RouteDataObject o, boolean forward) {
			highway = o.getHighway();
			tunnel = o.tunnel();
			String b = o.getValue("bridge");
			bridge = b != null && !"no".equals(b);
			limit = Math.round(o.getMaximumSpeed(forward) * 3.6f);
			String n = o.getName();
			name = n == null ? "" : n;
			id = o.getId() >> 6;
		}

		boolean sameSection(@NonNull Road r) {
			return Objects.equals(highway, r.highway) && tunnel == r.tunnel && bridge == r.bridge
					&& limit == r.limit && name.equals(r.name);
		}
	}

	private static final class Fix {
		final long time;
		final double lat;
		final double lon;
		final float speedKmh;
		final float bearing;
		@Nullable
		final Road road;
		final boolean navigating;
		final boolean onRoute;
		final int routeLeft;
		final int routeKey;

		Fix(@NonNull Location l, @Nullable Road road, boolean navigating, boolean onRoute, int routeLeft, int routeKey) {
			time = l.getTime();
			lat = l.getLatitude();
			lon = l.getLongitude();
			speedKmh = l.hasSpeed() ? l.getSpeed() * 3.6f : 0f;
			bearing = l.hasBearing() ? l.getBearing() : Float.NaN;
			this.road = road;
			this.navigating = navigating;
			this.onRoute = onRoute;
			this.routeLeft = routeLeft;
			this.routeKey = routeKey;
		}
	}

	private static final class Section {
		final Road road;
		final String colour;
		final long start;
		long end;
		double length;
		float max;
		int fixes;
		boolean navigating;
		boolean onRoute;

		Section(@NonNull Road road, @NonNull String colour, long start) {
			this.road = road;
			this.colour = colour;
			this.start = start;
			this.end = start;
		}
	}

	/** A real fix (not simulated), main thread. */
	public static void onFix(@Nullable Location location) {
		OsmandApplication a = app;
		if (a == null || location == null || !location.hasAccuracy() || location.getAccuracy() > MAX_ACCURACY_M) {
			return;
		}
		if (location.getTime() <= lastFixTime) {
			return; // the same fix from the car and the phone
		}
		lastFixTime = location.getTime();
		RoutingHelper rh = a.getRoutingHelper();
		boolean navigating = rh.isFollowingMode();
		boolean onRoute = false;
		RouteDataObject obj = null;
		boolean forward = true;
		if (navigating && !rh.isDeviatedFromRoute()) {
			RouteSegmentResult seg = rh.getCurrentSegmentResult();
			if (seg != null) {
				obj = seg.getObject();
				forward = seg.isForwardDirection();
				onRoute = true;
			}
		}
		if (obj == null) {
			obj = a.getLocationProvider().getLastKnownRouteSegment();
			if (obj != null && location.hasBearing()) {
				forward = isForward(obj, location);
			}
		}
		Road road = obj == null || obj.getHighway() == null ? null : new Road(obj, forward);
		RouteCalculationResult route = rh.getRoute();
		int left = navigating ? rh.getLeftDistance() : -1;
		int key = route == null ? 0 : System.identityHashCode(route);
		Fix fix = new Fix(location, road, navigating, onRoute, left, key);
		WORKER.execute(() -> {
			try {
				process(fix);
			} catch (Exception e) {
				ChizuDriveLog.log("road log failed: " + e);
			}
		});
	}

	private static boolean isForward(@NonNull RouteDataObject o, @NonNull Location l) {
		int n = o.getPointsLength();
		if (n < 2) {
			return true;
		}
		double best = Double.MAX_VALUE;
		int seg = 1;
		for (int i = 1; i < n; i++) {
			double d = MapUtils.getOrthogonalDistance(l.getLatitude(), l.getLongitude(),
					MapUtils.get31LatitudeY(o.getPoint31YTile(i - 1)), MapUtils.get31LongitudeX(o.getPoint31XTile(i - 1)),
					MapUtils.get31LatitudeY(o.getPoint31YTile(i)), MapUtils.get31LongitudeX(o.getPoint31XTile(i)));
			if (d < best) {
				best = d;
				seg = i;
			}
		}
		double b = bearing(MapUtils.get31LatitudeY(o.getPoint31YTile(seg - 1)), MapUtils.get31LongitudeX(o.getPoint31XTile(seg - 1)),
				MapUtils.get31LatitudeY(o.getPoint31YTile(seg)), MapUtils.get31LongitudeX(o.getPoint31XTile(seg)));
		return Math.abs(MapUtils.degreesDiff(b, l.getBearing())) <= 90;
	}

	private static double bearing(double lat1, double lon1, double lat2, double lon2) {
		float[] r = new float[2];
		Location.distanceBetween(lat1, lon1, lat2, lon2, r);
		return r[1];
	}

	// ---------- worker ----------

	private static void process(@NonNull Fix fix) throws Exception {
		Fix p = prev;
		if (p != null && fix.time - p.time > DRIVE_END_NO_FIX_MS) {
			endDrive();
			p = null;
		}
		if (!driving) {
			if (fix.speedKmh >= DRIVE_START_KMH) {
				driving = true;
				stillSince = 0;
				p = null;
			} else {
				prev = fix;
				return;
			}
		}
		if (fix.speedKmh < STILL_KMH) {
			if (stillSince == 0) {
				stillSince = fix.time;
			} else if (fix.time - stillSince > DRIVE_END_STILL_MS) {
				endDrive();
				prev = fix;
				return;
			}
		} else {
			stillSince = 0;
		}
		if (p == null || p.road == null || fix.road == null) {
			closeSection();
			prev = fix;
			return;
		}
		if (fix.time - p.time > GAP_MS) {
			closeSection();
			writeGap(p, fix);
			prev = fix;
			return;
		}
		walk(p, fix);
		prev = fix;
	}

	/** The colour every STEP_M m from one fix to the next; a section is closed where anything changes. */
	private static void walk(@NonNull Fix a, @NonNull Fix b) throws InterruptedException {
		double dist = MapUtils.getDistance(a.lat, a.lon, b.lat, b.lon);
		int steps = Math.max(1, (int) Math.ceil(dist / STEP_M));
		float bearing = (float) bearing(a.lat, a.lon, b.lat, b.lon);
		if (dist < 2 && !Float.isNaN(b.bearing)) {
			bearing = b.bearing;
		}
		List<double[]> points = new ArrayList<>(steps);
		for (int i = 1; i <= steps; i++) {
			double t = i / (double) steps;
			points.add(new double[] {a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t, bearing});
		}
		List<ChizuTraffic.Level> levels = source.pointLevels(points);
		double ds = dist / steps;
		for (int i = 1; i <= steps; i++) {
			double t = i / (double) steps;
			Road road = t <= 0.5 ? a.road : b.road;
			String colour = colour(levels.get(i - 1));
			long stepEnd = a.time + Math.round((b.time - a.time) * t);
			if (section == null) {
				section = new Section(road, colour, a.time);
				section.length += ds;
			} else if (!section.road.sameSection(road) || !section.colour.equals(colour)) {
				// the change lies halfway between this sample and the one before
				long splitAt = a.time + Math.round((b.time - a.time) * (i - 0.5) / steps);
				section.length += ds / 2;
				section.end = splitAt;
				writeSection(section);
				section = new Section(road, colour, splitAt);
				section.length += ds / 2;
			} else {
				section.length += ds;
			}
			section.end = stepEnd;
			section.max = Math.max(section.max, Math.max(a.speedKmh, b.speedKmh));
			section.navigating |= b.navigating;
			section.onRoute |= b.onRoute;
		}
		section.fixes++;
	}

	@NonNull
	private static String colour(@NonNull ChizuTraffic.Level l) {
		switch (l) {
			case FREE:
				return "green";
			case SLOW:
				return "yellow";
			case JAM:
				return "red";
			case STANDSTILL:
				return "dark_red";
			default:
				return "grey";
		}
	}

	private static void closeSection() {
		if (section != null) {
			writeSection(section);
			section = null;
		}
	}

	private static void endDrive() {
		closeSection();
		driving = false;
		stillSince = 0;
	}

	private static void writeSection(@NonNull Section s) {
		double secs = (s.end - s.start) / 1000.0;
		Road r = s.road;
		boolean shortOne = secs < 2 || s.length < 30;
		row(s.start, s.end, "section", r, s.colour, s.length, secs, s.max, s.fixes, s.navigating, s.onRoute, shortOne);
	}

	/** No fixes for a while (a tunnel, mostly): the length along the route when navigating, else straight. */
	private static void writeGap(@NonNull Fix a, @NonNull Fix b) {
		double length = MapUtils.getDistance(a.lat, a.lon, b.lat, b.lon);
		boolean alongRoute = a.navigating && b.navigating && a.routeKey == b.routeKey && a.routeLeft > b.routeLeft;
		if (alongRoute) {
			length = a.routeLeft - b.routeLeft;
		}
		Road r = a.road != null && a.road.tunnel ? a.road : b.road != null && b.road.tunnel ? b.road : a.road;
		if (r == null) {
			r = b.road;
		}
		double secs = (b.time - a.time) / 1000.0;
		row(a.time, b.time, alongRoute ? "gap_route" : "gap_straight", r, "grey", length, secs,
				Math.max(a.speedKmh, b.speedKmh), 2, a.navigating, alongRoute, false);
	}

	private static void row(long start, long end, @NonNull String kind, @Nullable Road r, @NonNull String colour,
	                        double length, double secs, float max, int fixes, boolean navigating, boolean onRoute,
	                        boolean shortOne) {
		OsmandApplication a = app;
		if (a == null) {
			return;
		}
		String highway = r == null ? "" : r.highway;
		int limit = r == null ? 0 : r.limit;
		boolean tunnel = r != null && r.tunnel;
		int osmand = ChizuSpeeds.stockSpeed(highway, limit);
		int mine = ChizuSpeeds.speedFor(a, highway, tunnel, limit);
		Date d = new Date(start);
		String line = String.format(Locale.US, "%s\t%s\t%s\t%s\t%s\t%s\t%d\t%d\t%d\t%s\t%s\t%.0f\t%.1f\t%.1f\t%.0f\t%d\t%d\t%d\t%d\t%d\t%d\t%d\n",
				DAY.format(d), DOW.format(d), TIME.format(d), TIME.format(new Date(end)), kind, highway,
				tunnel ? 1 : 0, r != null && r.bridge ? 1 : 0, limit, r == null ? "" : r.name.replace('\t', ' '), colour,
				length, secs, secs > 0 ? length / secs * 3.6 : 0, max, osmand, mine, fixes,
				navigating ? 1 : 0, onRoute ? 1 : 0, shortOne ? 1 : 0, r == null ? 0 : r.id);
		write(DAY.format(d), line);
	}

	private static void write(@NonNull String day, @NonNull String line) {
		try {
			if (out == null || !day.equals(outDay)) {
				open(day);
			}
			if (out != null) {
				out.write(line);
				out.flush();
			}
		} catch (Exception e) {
			out = null;
		}
	}

	private static void open(@NonNull String day) throws Exception {
		if (out != null) {
			try {
				out.close();
			} catch (Exception ignored) {
			}
			out = null;
		}
		File dir = new File(Environment.getExternalStorageDirectory(), "tmp");
		if (!ChizuStorage.hasAllFilesAccess() || (!dir.isDirectory() && !dir.mkdirs())) {
			OsmandApplication a = app;
			dir = a == null ? null : a.getExternalFilesDir(null);
		}
		if (dir == null) {
			return;
		}
		File f = new File(dir, PREFIX + day + ".tsv");
		boolean fresh = !f.exists() || f.length() == 0;
		out = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8);
		outDay = day;
		if (fresh) {
			out.write(HEADER);
		}
	}
}
