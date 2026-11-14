package net.osmand.plus.chizu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.RouteDataObject;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.resources.BinaryMapReaderResource;
import net.osmand.plus.resources.ResourceManager.BinaryMapReaderResourceType;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.router.RoutePlannerFrontEnd;
import net.osmand.router.RoutingConfiguration;
import net.osmand.router.RoutingConfiguration.RoutingMemoryLimits;
import net.osmand.router.RoutingContext;
import net.osmand.util.MapUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 白い熊 地図 +073: the roads around the car, and which of them it is on (白い熊, 2026-11-12: “the location
 * not sticking to roads, which is a mandatory behaviour while driving”).
 * <p>
 * Until +072 the snap off the route knew one road only — the nearest one, looked up again on the lookup
 * thread shared with the addresses only after the car was 15 m past it, and none at all for a fix worse
 * than 50 m. On the drive of 2026-11-12 the position went free 26 times in ten minutes, at nearly every
 * change of road, for up to 26 s. Here every car road within about 200 m is loaded on a thread of its
 * own, with a map reader of its own, and the choice is made on every fix from all of them: near, running
 * the way the car moves, and the road the car is believed to be on — or one joined to it — before any
 * other.
 * <p>
 * +074, after the second drive of 2026-11-12 (Vrbova's wooded valley: fixes 40–100 m off with the reported
 * accuracy lagging behind, a forest track taken for 13 s): the believed road is <b>held by dead
 * reckoning</b> — while the car's bearing agrees with the road, the position advances along it by the
 * measured speed whatever the fixes say, for up to {@value #HOLD_MAX_MS} ms and onto the best-aligned
 * joined road at its end. A road not joined to the believed one must be plausible to take over: within
 * reach of the fix and the winner on {@value #STREAK} fixes running. Tracks and paths are never
 * candidates above {@value #FAST_MPS} m/s, service roads only when nothing else is within reach, and the
 * believed road's advantage grows with the fix's inaccuracy.
 * <p>
 * +075, after the weak-signal drive of 2026-11-13: EMUI's engine dead-reckons through every loss of the
 * satellites — one fix a second, the accuracy ramping to a 55 m cap, the speed decaying, the bearing
 * drifting — and the matcher followed those runs onto Jeremenkova, Branická, three streets 70–120 m off
 * in turn, and a forest track. So a fix worse than {@value #WEAK_ACC} m <b>chooses no road</b>: the
 * believed road holds, advancing at the fix's speed, released by nothing but its end or
 * {@value #HOLD_MAX_MS} ms; neither the drifting bearing nor the drifting position lets go of it. A good
 * fix (the truth coming back) matches as always, and the gate lets it through now. Unpaved roads are no
 * candidates for a weak fix at any speed.
 */
public final class ChizuRoadMatcher {

	private static final double RELOAD_M = 60;
	private static final long RELOAD_MS = 30_000;
	private static final double KEEP_M = 350;
	private static final int REBUILD_AFTER_LOADS = 400;
	static final long HOLD_MAX_MS = 60_000;
	private static final long HOLD_BLIND_MS = 6000; // held without a bearing to check it against
	private static final float HOLD_ALIGN_DEG = 60f;
	private static final double HOLD_STEP_MAX_S = 3;
	private static final float FAST_MPS = 8.3f; // 30 km/h
	/** +075: a fix worse than this chooses no road. */
	static final float WEAK_ACC = 30f;
	private static final int STREAK = 3;
	private static final double MINOR_PENALTY_M = 200;
	private static final double AGAINST_ONEWAY_PENALTY_M = 15;
	private static final double COST_PER_DEG = 0.3;
	private static final float JUNCTION_LEAD_DEG = 10f;

	/** A road the car may be on. Immutable; built on the matcher thread. */
	static final class Road {
		final long id;
		final String name;
		final String highway;
		final int oneway; // 1 along the points, -1 against them, 0 both ways
		final double[] lat;
		final double[] lon;
		final long[] keys;
		final double minLat, maxLat, minLon, maxLon;

		Road(@NonNull RouteDataObject o, int oneway) {
			this.id = o.getId();
			String n = o.getName();
			if (n == null || n.isEmpty()) {
				n = o.getRef("", false, true);
			}
			this.name = n == null || n.isEmpty() ? "#" + (id >> 6) : n;
			String h = o.getHighway();
			this.highway = h == null ? "" : h;
			this.oneway = oneway;
			int len = o.getPointsLength();
			lat = new double[len];
			lon = new double[len];
			keys = new long[len];
			double a = 90, b = -90, c = 180, d = -180;
			for (int i = 0; i < len; i++) {
				int x = o.getPoint31XTile(i);
				int y = o.getPoint31YTile(i);
				lat[i] = MapUtils.get31LatitudeY(y);
				lon[i] = MapUtils.get31LongitudeX(x);
				keys[i] = ((long) x << 32) | (y & 0xffffffffL);
				a = Math.min(a, lat[i]);
				b = Math.max(b, lat[i]);
				c = Math.min(c, lon[i]);
				d = Math.max(d, lon[i]);
			}
			minLat = a;
			maxLat = b;
			minLon = c;
			maxLon = d;
		}

		boolean near(double la, double lo, double m) {
			double dLat = m / 111_000.0;
			double dLon = m / (111_000.0 * Math.max(0.1, Math.cos(Math.toRadians(la))));
			return la >= minLat - dLat && la <= maxLat + dLat && lo >= minLon - dLon && lo <= maxLon + dLon;
		}

		boolean joins(@NonNull Set<Long> other) {
			for (long k : keys) {
				if (other.contains(k)) {
					return true;
				}
			}
			return false;
		}

		/** Where this road touches the node {@code key}, or -1. */
		int indexOf(long key) {
			for (int i = 0; i < keys.length; i++) {
				if (keys[i] == key) {
					return i;
				}
			}
			return -1;
		}

		boolean minor() {
			return "track".equals(highway) || "path".equals(highway) || "service".equals(highway)
					|| "living_street".equals(highway) || "pedestrian".equals(highway);
		}

		boolean unpaved() {
			return "track".equals(highway) || "path".equals(highway);
		}

		/** The bearing of segment {@code i} (from point i-1 to point i), along the points. */
		float segBearing(int i) {
			return bearing(lat[i - 1], lon[i - 1], lat[i], lon[i]);
		}
	}

	/** Where the fix lands on a road, and which way along it the car goes. */
	public static final class Match {
		public final double lat;
		public final double lon;
		public final float dir;
		public final double dist;
		final Road road;
		final int seg;
		final float ang;
		final double cost;
		public final String how;

		Match(double lat, double lon, float dir, double dist, @NonNull Road road, int seg, float ang, double cost,
		      @NonNull String how) {
			this.lat = lat;
			this.lon = lon;
			this.dir = dir;
			this.dist = dist;
			this.road = road;
			this.seg = seg;
			this.ang = ang;
			this.cost = cost;
			this.how = how;
		}

		@NonNull
		public String describe() {
			return String.format(Locale.US, "%s %s (%s) d=%.0fm dir=%.0f", how, road.name, road.highway, dist, dir);
		}

		@NonNull
		String brief() {
			return String.format(Locale.US, "%s %s (%s) d=%.0f ang=%.0f cost=%.0f", how, road.name, road.highway, dist, ang, cost);
		}
	}

	private final OsmandApplication app;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "chizu-road-match");
		t.setDaemon(true);
		return t;
	});

	// written on the worker, read on the main thread
	private volatile List<Road> roads = Collections.emptyList();
	private volatile double loadedLat = Double.NaN;
	private volatile double loadedLon = Double.NaN;
	private volatile long loadedMs;
	private boolean busy; // main thread only
	private double[] pending; // main thread only

	// main thread only
	private Road believed;
	private Set<Long> believedKeys = Collections.emptySet();
	// where the car was last shown on the believed road, for the dead reckoning
	private int shownSeg;
	private double shownLat;
	private double shownLon;
	private boolean shownForward;
	private long shownMs;
	private long holdSinceMs;
	private Road streakRoad;
	private int streak;
	private long lastReportedId = Long.MIN_VALUE;

	// worker only
	private RoutingContext ctx;
	private List<BinaryMapReaderResource> used = Collections.emptyList();
	private ApplicationMode ctxMode;
	private int loads;

	public ChizuRoadMatcher(@NonNull OsmandApplication app) {
		this.app = app;
	}

	/** Loads the roads around the point when the car has moved away from the last load. Main thread. */
	public void request(double lat, double lon) {
		long now = android.os.SystemClock.elapsedRealtime();
		if (!Double.isNaN(loadedLat) && now - loadedMs < RELOAD_MS
				&& MapUtils.getDistance(loadedLat, loadedLon, lat, lon) < RELOAD_M) {
			return;
		}
		if (busy) {
			pending = new double[] {lat, lon};
			return;
		}
		busy = true;
		ApplicationMode mode = app.getSettings().getApplicationMode();
		worker.execute(() -> {
			load(lat, lon, mode);
			app.runInUIThread(() -> {
				busy = false;
				double[] p = pending;
				pending = null;
				if (p != null) {
					request(p[0], p[1]);
				}
			});
		});
	}

	/**
	 * The road the fix is on. {@code ref} is the way the car moves (NaN when not known), {@code reach} how
	 * far from the fix a road may be, {@code believedReach} the same for the believed road and those
	 * joined to it. Main thread.
	 */
	@Nullable
	public Match match(double lat, double lon, float ref, float speed, float acc, double reach, double believedReach,
	                   float alignDeg) {
		long now = android.os.SystemClock.elapsedRealtime();
		boolean fast = speed >= FAST_MPS;
		boolean weak = acc > WEAK_ACC;
		// +075: a weak fix chooses no road — the believed one holds, whatever the fix drifts to
		if (weak && believed != null && shownMs != 0) {
			Match held = holdWeak(lat, lon, speed, now);
			if (held != null) {
				return held;
			}
			// the hold is over (the road ended, or it lasted too long): the weak fix is matched like any other
		}
		double believedBonus = Math.max(8, acc);
		double joinedBonus = Math.max(4, acc / 2);
		// a road not joined to the believed one: within the fix's own error, not merely within reach
		double nearReach = believed == null ? reach : Math.max(15, Math.min(60, acc));

		// 1. the believed road itself
		Match mine = null;
		if (believed != null) {
			mine = bestOn(believed, lat, lon, ref, alignDeg, believedReach);
			if (mine != null) {
				mine = with(mine, mine.cost - believedBonus, "on");
			}
		}
		// 2. every other road: joined ones with the believed reach, the rest with the near reach
		List<Match> others = new ArrayList<>();
		for (Road r : roads) {
			if (believed != null && r.id == believed.id) {
				continue;
			}
			boolean joined = believed != null && r.joins(believedKeys);
			if (r.unpaved() && (weak || (fast && !joined))) {
				continue; // a car at 30 km/h is not on a forest track, and a weak fix does not put it on one (+075)
			}
			Match m = bestOn(r, lat, lon, ref, alignDeg, joined ? believedReach : nearReach);
			if (m == null) {
				continue;
			}
			double cost = m.cost;
			if (joined) {
				cost -= joinedBonus;
				// at a junction the car is on the road its bearing has swung to, whatever the distances say
				if (mine != null && m.ang + JUNCTION_LEAD_DEG < mine.ang) {
					cost -= believedBonus;
				}
			}
			if (r.minor()) {
				cost += MINOR_PENALTY_M; // only when nothing else is within reach
			}
			others.add(with(m, cost, joined ? "joined" : "nearest"));
		}
		Collections.sort(others, (a, b) -> Double.compare(a.cost, b.cost));
		Match best = mine;
		for (Match m : others) {
			if (best == null || m.cost < best.cost) {
				best = m;
			}
		}
		// 3. a road not joined to the believed one must win three fixes running
		if (best != null && "nearest".equals(best.how) && believed != null) {
			if (streakRoad != null && streakRoad.id == best.road.id) {
				streak++;
			} else {
				streakRoad = best.road;
				streak = 1;
			}
			if (streak < STREAK) {
				Match own = mine;
				for (Match m : others) {
					if ("joined".equals(m.how) && (own == null || m.cost < own.cost)) {
						own = m;
					}
				}
				if (own != null) {
					best = own;
				} else {
					Match held = hold(lat, lon, ref, speed, acc, now);
					if (held != null) {
						return held;
					}
					// nothing to hold on to: the newcomer it is
				}
			}
		} else {
			streakRoad = null;
			streak = 0;
		}
		if (best != null) {
			take(best, now);
			if (best.road.id != lastReportedId) {
				lastReportedId = best.road.id;
				if (ChizuDriveLog.on(ChizuDiag.Group.LOCATION)) {
					StringBuilder sb = new StringBuilder("loc road → ").append(best.brief());
					int n = 0;
					for (Match m : others) {
						if (m != best && n++ < 2) {
							sb.append(" | ").append(m.brief());
						}
					}
					if (mine != null && mine != best) {
						sb.append(" | ").append(mine.brief());
					}
					ChizuDriveLog.log(sb.toString());
				}
			}
			return best;
		}
		return hold(lat, lon, ref, speed, acc, now);
	}

	/** The car has stopped being on any road (another profile, a reset): forget the believed one. */
	public void forget() {
		believed = null;
		believedKeys = Collections.emptySet();
		holdSinceMs = 0;
		shownMs = 0;
		streakRoad = null;
		streak = 0;
		lastReportedId = Long.MIN_VALUE;
	}

	private void take(@NonNull Match m, long now) {
		if (believed == null || m.road.id != believed.id) {
			believe(m.road);
		}
		shownSeg = m.seg;
		shownLat = m.lat;
		shownLon = m.lon;
		shownForward = diff(m.dir, m.road.segBearing(m.seg)) <= 90;
		shownMs = now;
		holdSinceMs = 0;
	}

	private void believe(@NonNull Road r) {
		believed = r;
		Set<Long> keys = new HashSet<>();
		for (long k : r.keys) {
			keys.add(k);
		}
		believedKeys = keys;
	}

	/**
	 * No road qualifies: the believed road holds, the position advancing along it by the measured speed —
	 * while the car's bearing still agrees with the road, the fix is not absurdly far, and not for longer
	 * than {@value #HOLD_MAX_MS} ms. At the road's end the best-aligned joined road takes over.
	 */
	@Nullable
	private Match hold(double lat, double lon, float ref, float speed, float acc, long now) {
		if (believed == null || shownMs == 0) {
			return null;
		}
		long since = holdSinceMs == 0 ? now : holdSinceMs;
		String why = null;
		float roadDir = believed.segBearing(shownSeg);
		if (!shownForward) {
			roadDir = norm(roadDir + 180);
		}
		if (now - since > HOLD_MAX_MS) {
			why = "held for " + (now - since) / 1000 + " s";
		} else if (Float.isNaN(ref) && now - since > HOLD_BLIND_MS) {
			why = "no bearing to hold by";
		} else if (!Float.isNaN(ref) && diff(ref, roadDir) > HOLD_ALIGN_DEG) {
			why = String.format(Locale.US, "bearing %.0f against the road's %.0f", ref, roadDir);
		} else if (MapUtils.getDistance(lat, lon, shownLat, shownLon) > 3 * acc + 60) {
			why = String.format(Locale.US, "fix %.0f m from the held position", MapUtils.getDistance(lat, lon, shownLat, shownLon));
		}
		if (why != null) {
			ChizuDriveLog.log("loc hold on " + believed.name + " released: " + why);
			holdSinceMs = 0;
			shownMs = 0;
			return null;
		}
		double dt = Math.min(HOLD_STEP_MAX_S, Math.max(0, (now - shownMs) / 1000.0));
		double metres = speed * dt;
		holdSinceMs = since;
		shownMs = now;
		if (!advance(metres, ref)) {
			ChizuDriveLog.log("loc hold on " + believed.name + " released: road ends, nothing joined ahead");
			holdSinceMs = 0;
			shownMs = 0;
			return null;
		}
		float dir = believed.segBearing(shownSeg);
		if (!shownForward) {
			dir = norm(dir + 180);
		}
		double d = MapUtils.getDistance(lat, lon, shownLat, shownLon);
		return new Match(shownLat, shownLon, dir, d, believed, shownSeg, Float.isNaN(ref) ? 0 : diff(dir, ref), d, "held");
	}

	/**
	 * +075: a weak fix came — the believed road holds, the position advancing along it by the fix's speed
	 * (the engine's own reckoning: it decays towards a stop, which is the lesser error), onto the
	 * best-aligned joined road at its end. Unlike {@link #hold}, neither the fix's bearing nor its distance
	 * releases it: both are the drift. Only the road's end or {@value #HOLD_MAX_MS} ms do.
	 */
	@Nullable
	private Match holdWeak(double lat, double lon, float speed, long now) {
		long since = holdSinceMs == 0 ? now : holdSinceMs;
		if (now - since > HOLD_MAX_MS) {
			ChizuDriveLog.log("loc hold on " + believed.name + " released: held through weak fixes for " + (now - since) / 1000 + " s");
			holdSinceMs = 0;
			shownMs = 0;
			return null;
		}
		double dt = Math.min(HOLD_STEP_MAX_S, Math.max(0, (now - shownMs) / 1000.0));
		holdSinceMs = since;
		shownMs = now;
		if (!advance(speed * dt, Float.NaN)) {
			ChizuDriveLog.log("loc hold on " + believed.name + " released: road ends, nothing joined ahead (weak fixes)");
			holdSinceMs = 0;
			shownMs = 0;
			return null;
		}
		float dir = believed.segBearing(shownSeg);
		if (!shownForward) {
			dir = norm(dir + 180);
		}
		double d = MapUtils.getDistance(lat, lon, shownLat, shownLon);
		return new Match(shownLat, shownLon, dir, d, believed, shownSeg, 0, d, "held/weak");
	}

	/** Moves the shown point {@code metres} along the believed road, onto a joined road at its end. */
	private boolean advance(double metres, float ref) {
		double left = metres;
		int guard = 0;
		while (left > 0 && guard++ < 50) {
			Road r = believed;
			int endIdx = shownForward ? shownSeg : shownSeg - 1;
			double segLeft = MapUtils.getDistance(shownLat, shownLon, r.lat[endIdx], r.lon[endIdx]);
			if (segLeft >= left) {
				double f = segLeft == 0 ? 1 : left / segLeft;
				shownLat += (r.lat[endIdx] - shownLat) * f;
				shownLon += (r.lon[endIdx] - shownLon) * f;
				return true;
			}
			left -= segLeft;
			shownLat = r.lat[endIdx];
			shownLon = r.lon[endIdx];
			int nextSeg = shownForward ? shownSeg + 1 : shownSeg - 1;
			if (nextSeg >= 1 && nextSeg < r.lat.length) {
				shownSeg = nextSeg;
				continue;
			}
			// the road's end: the joined road that runs on the way the car goes
			long key = r.keys[endIdx];
			float out = r.segBearing(shownSeg);
			if (!shownForward) {
				out = norm(out + 180);
			}
			float want = Float.isNaN(ref) ? out : ref;
			Road bestRoad = null;
			int bestSeg = 0;
			boolean bestForward = true;
			float bestDiff = HOLD_ALIGN_DEG;
			for (Road o : roads) {
				if (o.id == r.id || o.lat.length < 2) {
					continue;
				}
				int at = o.indexOf(key);
				if (at < 0) {
					continue;
				}
				if (at + 1 < o.lat.length) {
					float dir = o.segBearing(at + 1);
					if (diff(dir, want) < bestDiff) {
						bestDiff = diff(dir, want);
						bestRoad = o;
						bestSeg = at + 1;
						bestForward = true;
					}
				}
				if (at >= 1) {
					float dir = norm(o.segBearing(at) + 180);
					if (diff(dir, want) < bestDiff) {
						bestDiff = diff(dir, want);
						bestRoad = o;
						bestSeg = at;
						bestForward = false;
					}
				}
			}
			if (bestRoad == null) {
				return false;
			}
			ChizuDriveLog.log("loc hold goes on from " + r.name + " to " + bestRoad.name);
			believe(bestRoad);
			shownSeg = bestSeg;
			shownForward = bestForward;
		}
		return true;
	}

	/** The nearest point of {@code r} to the fix within {@code reach}, running within {@code alignDeg} of {@code ref}. */
	@Nullable
	private static Match bestOn(@NonNull Road r, double lat, double lon, float ref, float alignDeg, double reach) {
		if (!r.near(lat, lon, reach)) {
			return null;
		}
		Match best = null;
		for (int i = 1; i < r.lat.length; i++) {
			net.osmand.data.LatLon p = MapUtils.getProjection(lat, lon, r.lat[i - 1], r.lon[i - 1], r.lat[i], r.lon[i]);
			double d = MapUtils.getDistance(p.getLatitude(), p.getLongitude(), lat, lon);
			if (d > reach) {
				continue;
			}
			float seg = r.segBearing(i);
			float dir = Float.isNaN(ref) ? seg : align(seg, ref);
			float ang = Float.isNaN(ref) ? 0 : diff(dir, ref);
			if (ang > alignDeg) {
				continue;
			}
			double cost = d + COST_PER_DEG * ang;
			if (r.oneway != 0) {
				float along = r.oneway > 0 ? seg : norm(seg + 180);
				if (diff(dir, along) > 90) {
					cost += AGAINST_ONEWAY_PENALTY_M;
				}
			}
			if (best == null || cost < best.cost) {
				best = new Match(p.getLatitude(), p.getLongitude(), dir, d, r, i, ang, cost, "");
			}
		}
		return best;
	}

	@NonNull
	private static Match with(@NonNull Match m, double cost, @NonNull String how) {
		return new Match(m.lat, m.lon, m.dir, m.dist, m.road, m.seg, m.ang, cost, how);
	}

	private void load(double lat, double lon, @NonNull ApplicationMode mode) {
		long t0 = System.currentTimeMillis();
		try {
			int x31 = MapUtils.get31TileNumberX(lon);
			int y31 = MapUtils.get31TileNumberY(lat);
			ensureCtx(x31, y31, mode);
			if (ctx == null) {
				roads = Collections.emptyList();
				return;
			}
			List<RouteDataObject> found = new ArrayList<>();
			ctx.loadTileData(x31, y31, 17, found, false);
			List<Road> list = new ArrayList<>();
			Set<Long> seen = new HashSet<>();
			for (RouteDataObject o : found) {
				if (o.getPointsLength() < 2 || !seen.add(o.getId())) {
					continue;
				}
				Road r = new Road(o, ctx.getRouter().isOneWay(o));
				if (r.near(lat, lon, KEEP_M)) {
					list.add(r);
				}
			}
			roads = list;
			loadedLat = lat;
			loadedLon = lon;
			loadedMs = android.os.SystemClock.elapsedRealtime();
			loads++;
			if (ChizuDriveLog.on(ChizuDiag.Group.LOCATION)) {
				ChizuDriveLog.log(String.format(Locale.US, "loc roads %d around %.6f,%.6f in %d ms",
						list.size(), lat, lon, System.currentTimeMillis() - t0));
			}
		} catch (Exception e) {
			ctx = null; // a map closed under us: built again next time
			ChizuDriveLog.log("loc roads failed: " + e);
		}
	}

	private void ensureCtx(int x31, int y31, @NonNull ApplicationMode mode) {
		List<BinaryMapReaderResource> need = new ArrayList<>();
		for (BinaryMapReaderResource r : app.getResourceManager().getFileReaders()) {
			BinaryMapIndexReader shallow = r.isClosed() ? null : r.getShallowReader();
			if (shallow != null && shallow.containsRouteData(x31, y31, x31, y31, 15)) {
				need.add(r);
			}
		}
		boolean closed = false;
		for (BinaryMapReaderResource r : used) {
			closed |= r.isClosed();
		}
		if (ctx != null && !closed && mode == ctxMode && used.containsAll(need) && loads < REBUILD_AFTER_LOADS) {
			return;
		}
		ctx = null;
		loads = 0;
		used = need;
		ctxMode = mode;
		List<BinaryMapIndexReader> rs = new ArrayList<>();
		for (BinaryMapReaderResource r : need) {
			BinaryMapIndexReader reader = r.getReader(BinaryMapReaderResourceType.CHIZU_ROAD_MATCH);
			if (reader != null) {
				rs.add(reader);
			}
		}
		if (rs.isEmpty()) {
			return;
		}
		RoutingConfiguration cfg = app.getRoutingConfigForMode(mode).build("car",
				new RoutingMemoryLimits(20, 20), new HashMap<>());
		cfg.routeCalculationTime = System.currentTimeMillis();
		ctx = new RoutePlannerFrontEnd().buildRoutingContext(cfg, null, rs.toArray(new BinaryMapIndexReader[0]));
	}

	/** The road's direction or its opposite, whichever is closer to {@code ref}. */
	static float align(float roadDir, float ref) {
		return diff(roadDir, ref) <= 90 ? norm(roadDir) : norm(roadDir + 180);
	}

	static float diff(float a, float b) {
		float d = Math.abs(norm(a) - norm(b));
		return d > 180 ? 360 - d : d;
	}

	static float norm(float a) {
		a %= 360;
		return a < 0 ? a + 360 : a;
	}

	static float bearing(double lat1, double lon1, double lat2, double lon2) {
		net.osmand.Location a = new net.osmand.Location("", lat1, lon1);
		return norm(a.bearingTo(new net.osmand.Location("", lat2, lon2)));
	}
}
