package net.osmand.plus.routing;

import net.osmand.Location;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import net.osmand.PlatformUtil;
import net.osmand.data.LatLon;
import net.osmand.data.ValueHolder;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.chizu.ChizuGoogleTrafficSource;
import net.osmand.plus.chizu.ChizuTraffic;
import net.osmand.plus.chizu.ChizuTraffic.Level;
import net.osmand.plus.chizu.ChizuTrafficPrefs;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.router.RouteCalculationProgress;
import net.osmand.router.RouteSegmentResult;

import org.apache.commons.logging.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 白い熊 地図 渋滞回避: Waze-like route choice at planning, and reroute offers while driving.
 * <p>
 * <b>Planning.</b> When a car route is calculated in route-planning mode, the route ahead is read
 * from the traffic source and up to four more routes are computed in the background, each with its
 * own bans (never the shared avoid-roads list): avoid red; avoid red + long yellow; avoid red + all
 * yellow; and plain shortest (no bans). Each is graded by its own traffic, and all of them — the
 * plain route and the shortest included — are listed by traffic-adjusted time, the fastest first and
 * preselected. A traffic variant slower than the plain route is left out. Near-duplicates are merged;
 * when nothing differs from the plain route, no chooser is shown.
 * <p>
 * <b>Driving.</b> Every minute, and right after every off-route reroute, the rest of the route is
 * set against a fresh plain fastest route and a fresh route around the jams of now; when one of them
 * saves the minimum saving, it is taken without asking (nobody could answer an offer in the car) and
 * announced with the time it saves.
 * <p>
 * {@link ChizuTrafficBans} holds the jams of the latest check, so an off-route recalculation avoids
 * what is jammed now — never what was jammed when the route was planned. Nothing is written to
 * settings.
 */
public class ChizuRouteCandidates implements IRouteInformationListener {

	private static final Log LOG = PlatformUtil.getLog(ChizuRouteCandidates.class);

	private static final float SAMPLE_AHEAD_M = 40000f;
	private static final float PROTECT_START_M = 400f;
	private static final float PROTECT_END_M = 200f;
	private static final float DUPLICATE_SHARE = 0.85f;
	private static final long CALC_TIMEOUT_S = 90;
	// planning shows what it has after this long; a slower alternative is not worth the wait
	private static final long PLAN_BUDGET_MS = 45_000;
	// after an off-route recalculation, read the traffic on the new route this soon
	private static final long REROUTE_CHECK_DELAY_MS = 3_000;

	public enum Kind {
		AVOID_RED(R.string.chizu_traffic_avoid_red),
		AVOID_LONG_YELLOW(R.string.chizu_traffic_avoid_long_yellow),
		AVOID_YELLOW(R.string.chizu_traffic_avoid_yellow),
		SHORTEST(R.string.chizu_traffic_shortest),
		FASTEST(R.string.chizu_traffic_ignores);

		@StringRes
		public final int label;

		Kind(@StringRes int label) {
			this.label = label;
		}

		boolean isTrafficVariant() {
			return this == AVOID_RED || this == AVOID_LONG_YELLOW || this == AVOID_YELLOW;
		}
	}

	public static final class Candidate {
		public final Kind kind;
		public final RouteCalculationResult route;
		public final List<RouteSegmentResult> segments;
		public final List<Level> levels;
		public final Set<Long> bans;
		public final float adjustedTime;
		public final float jamDelay;
		public final int distance;

		Candidate(@NonNull Kind kind, @NonNull RouteCalculationResult route, @NonNull List<RouteSegmentResult> segments,
		          @NonNull List<Level> levels, @NonNull Set<Long> bans) {
			this.kind = kind;
			this.route = route;
			this.segments = segments;
			this.levels = levels;
			this.bans = bans;
			this.adjustedTime = ChizuTraffic.adjustedTime(segments, levels);
			this.jamDelay = ChizuTraffic.delay(segments, levels);
			this.distance = route.getWholeDistance();
		}
	}

	public interface Listener {
		/** UI thread: the candidate list, the selection, or the working state changed. */
		void onCandidatesChanged();
	}

	private final OsmandApplication app;
	private final RoutingHelper routingHelper;
	private final ChizuGoogleTrafficSource source = new ChizuGoogleTrafficSource();
	private final ExecutorService worker = Executors.newSingleThreadExecutor();

	private final List<WeakReference<Listener>> listeners = new CopyOnWriteArrayList<>();

	private volatile int generation;
	private volatile boolean working;
	private volatile boolean selecting;
	private volatile List<Candidate> candidates = Collections.emptyList();
	private volatile int selected = -1;

	private volatile Candidate active;
	private volatile boolean chosenByUser;
	private Runnable rerouteCheck;
	private volatile boolean checkQueued;
	private Runnable driveCheck;

	public ChizuRouteCandidates(@NonNull OsmandApplication app, @NonNull RoutingHelper routingHelper) {
		this.app = app;
		this.routingHelper = routingHelper;
		routingHelper.addListener(this);
	}

	// ---------- public state ----------

	@NonNull
	public List<Candidate> getCandidates() {
		return candidates;
	}

	public int getSelectedIndex() {
		return selected;
	}

	public boolean isWorking() {
		return working;
	}

	public boolean hasChoice() {
		return candidates.size() > 1;
	}

	public void addListener(@NonNull Listener listener) {
		listeners.add(new WeakReference<>(listener));
	}

	/** Makes candidate {@code index} the active route: 白い熊's pick in the chooser. UI thread. */
	public void select(int index) {
		chosenByUser = true;
		selectCandidate(index);
	}

	private void selectCandidate(int index) {
		List<Candidate> list = candidates;
		if (index < 0 || index >= list.size() || index == selected) {
			return;
		}
		Candidate c = list.get(index);
		selected = index;
		active = c;
		ChizuTrafficBans.setActive(c.bans);
		selecting = true;
		routingHelper.chizuSetActiveRoute(c.route);
		notifyChanged();
	}

	// ---------- route events ----------

	@Override
	public void newRouteIsCalculated(boolean newRoute, ValueHolder<Boolean> showToast) {
		if (selecting) {
			// the route we set ourselves, from the chooser or the traffic check
			selecting = false;
			return;
		}
		RouteCalculationResult route = routingHelper.getRoute();
		if (!isApplicable(route)) {
			discard();
			return;
		}
		if (routingHelper.isRoutePlanningMode()) {
			if (ChizuTrafficPrefs.isChooserEnabled(app)) {
				planCandidates(route);
			} else {
				discard();
			}
		} else if (routingHelper.isFollowingMode()) {
			scheduleDriveCheck();
			if (!newRoute) {
				scheduleRerouteCheck();
			}
		}
	}

	/** An off-route recalculation knows nothing of the traffic, so the new route is checked at once. */
	private void scheduleRerouteCheck() {
		Runnable r = new Runnable() {
			@Override
			public void run() {
				if (rerouteCheck == this) {
					rerouteCheck = null;
					checkAhead(true);
				}
			}
		};
		rerouteCheck = r;
		app.runInUIThread(r, REROUTE_CHECK_DELAY_MS);
	}

	/** Navigation started (Go) or stopped. Any thread. */
	void onFollowingModeChanged(boolean follow) {
		app.runInUIThread(() -> {
			if (follow) {
				// the chooser is done with; a candidate job still running must not switch the route now
				if (!candidates.isEmpty() || working) {
					generation++;
					working = false;
					candidates = Collections.emptyList();
					selected = -1;
					notifyChanged();
				}
				if (isApplicable(routingHelper.getRoute())) {
					scheduleDriveCheck();
				}
			} else {
				cancelDriveCheck();
			}
		});
	}

	@Override
	public void routeWasCancelled() {
		stopAll();
	}

	@Override
	public void routeWasFinished() {
		net.osmand.plus.chizu.ChizuDriveLog.log("arrived"); // 白い熊 地図: predictions against arrivals
		stopAll();
	}

	private void stopAll() {
		generation++;
		ChizuTrafficBans.clear();
		cancelDriveCheck();
		rerouteCheck = null;
		discard();
	}

	private void discard() {
		generation++;
		boolean changed = !candidates.isEmpty() || working;
		candidates = Collections.emptyList();
		selected = -1;
		working = false;
		if (changed) {
			notifyChanged();
		}
	}

	private boolean isApplicable(@Nullable RouteCalculationResult route) {
		if (!ChizuTrafficPrefs.isEnabled(app) || route == null || !route.isCalculated()) {
			return false;
		}
		ApplicationMode mode = routingHelper.getAppMode();
		return mode != null && mode.getRouteService() == RouteService.OSMAND
				&& mode.isDerivedRoutingFrom(ApplicationMode.CAR)
				&& routingHelper.getCurrentGPXRoute() == null
				&& !routingHelper.isPublicTransportMode();
	}

	// ---------- planning ----------

	private void planCandidates(@NonNull RouteCalculationResult baseline) {
		// a route planned anew: the previous choice's bans no longer apply
		ChizuTrafficBans.clear();
		int gen = ++generation;
		candidates = Collections.emptyList();
		selected = -1;
		active = null;
		chosenByUser = false;
		working = true;
		notifyChanged();
		LatLon end = routingHelper.getFinalLocation();
		List<LatLon> intermediates = routingHelper.getIntermediatePoints();
		ApplicationMode mode = routingHelper.getAppMode();
		List<Location> locations = baseline.getImmutableAllLocations();
		if (end == null || locations.isEmpty()) {
			working = false;
			notifyChanged();
			return;
		}
		Location start = new Location(locations.get(0));
		worker.execute(() -> {
			try {
				computeCandidates(gen, baseline, start, end, intermediates, mode);
			} catch (InterruptedException e) {
				// superseded
			} catch (Exception e) {
				LOG.error("Traffic candidates failed", e);
				app.runInUIThread(() -> {
					if (gen == generation) {
						working = false;
						notifyChanged();
					}
				});
			}
		});
	}

	/**
	 * Fills the chooser as the routes come in: the plain route with its traffic first, then each
	 * alternative the moment it is calculated, within {@link #PLAN_BUDGET_MS}. Alternatives are only
	 * calculated around traffic that is really there, “avoid red” first, without refinement passes.
	 */
	private void computeCandidates(int gen, @NonNull RouteCalculationResult baseline, @NonNull Location start,
	                               @NonNull LatLon end, @Nullable List<LatLon> intermediates,
	                               @NonNull ApplicationMode mode) throws InterruptedException {
		TrafficMap seen = new TrafficMap();
		long t0 = System.currentTimeMillis();
		long deadline = t0 + PLAN_BUDGET_MS;
		Candidate fastest = grade(Kind.FASTEST, baseline, Collections.emptySet(), seen);
		net.osmand.plus.chizu.ChizuDriveLog.log("candidate FASTEST traffic " + (System.currentTimeMillis() - t0) + " ms"); // 白い熊 地図 drive log
		checkGeneration(gen);

		List<Candidate> variants = new ArrayList<>();
		Candidate shortest = null;
		List<Set<Long>> tried = new ArrayList<>();
		for (Kind kind : new Kind[] {Kind.AVOID_RED, Kind.AVOID_LONG_YELLOW, Kind.AVOID_YELLOW}) {
			Set<Long> bans = seen.bansFor(kind, app);
			if (bans.isEmpty() || tried.contains(bans)) {
				continue; // no such traffic, or the same bans as a route already calculated
			}
			long left = deadline - System.currentTimeMillis();
			if (left <= 0) {
				break;
			}
			tried.add(bans);
			net.osmand.plus.chizu.ChizuDriveLog.log("candidate " + kind + " banned: " + describeBans(bans, seen)); // 白い熊 地図 drive log
			Candidate c = calculate(gen, kind, start, end, intermediates, mode, bans, false, seen, left);
			if (c != null) {
				variants.add(c);
				publish(gen, variants, null, fastest, false);
			}
		}
		long left = deadline - System.currentTimeMillis();
		if (left > 0) {
			shortest = calculate(gen, Kind.SHORTEST, start, end, intermediates, mode,
					Collections.emptySet(), true, seen, left);
		}
		publish(gen, variants, shortest, fastest, true);
		net.osmand.plus.chizu.ChizuDriveLog.log("candidates done in " + (System.currentTimeMillis() - t0) + " ms, " + candidates.size() + " shown"); // 白い熊 地図 drive log
	}

	/** Orders and merges what has been calculated so far and shows it. Worker thread. */
	private void publish(int gen, @NonNull List<Candidate> variantsSoFar, @Nullable Candidate shortest,
	                     @NonNull Candidate fastest, boolean done) {
		// every route, the plain one and the shortest included, ranked by its time with traffic;
		// a traffic variant slower than the plain route is a pointless detour and is left out
		List<Candidate> all = new ArrayList<>();
		for (Candidate c : variantsSoFar) {
			if (c.adjustedTime < fastest.adjustedTime) {
				all.add(c);
			}
		}
		all.add(fastest);
		if (shortest != null) {
			all.add(shortest);
		}
		Collections.sort(all, (a, b) -> Float.compare(a.adjustedTime, b.adjustedTime));
		// near-duplicates go into the faster of the two
		List<Candidate> shown = new ArrayList<>();
		for (Candidate c : all) {
			if (!isDuplicate(c, shown)) {
				shown.add(c);
			}
		}
		List<Candidate> result = shown.size() > 1 ? shown : Collections.emptyList();
		app.runInUIThread(() -> {
			if (gen != generation) {
				return;
			}
			working = !done;
			candidates = result;
			Candidate current = active;
			selected = current != null ? result.indexOf(current) : -1;
			if (!result.isEmpty() && !chosenByUser && result.get(0) != current) {
				// the best traffic-aware route so far is pre-selected, as Waze does
				selectCandidate(0);
			}
			notifyChanged();
		});
	}

	private static boolean isDuplicate(@NonNull Candidate c, @NonNull List<Candidate> others) {
		for (Candidate o : others) {
			if (ChizuTraffic.sharedFraction(c.segments, o.segments) > DUPLICATE_SHARE
					&& ChizuTraffic.sharedFraction(o.segments, c.segments) > DUPLICATE_SHARE) {
				return true;
			}
		}
		return false;
	}

	@Nullable
	private Candidate calculate(int gen, @NonNull Kind kind, @NonNull Location start, @NonNull LatLon end,
	                            @Nullable List<LatLon> intermediates, @NonNull ApplicationMode mode,
	                            @NonNull Set<Long> bans, boolean shortest, @NonNull TrafficMap seen,
	                            long timeoutMs) throws InterruptedException {
		checkGeneration(gen);
		long t0 = System.currentTimeMillis();
		RouteCalculationResult res = calculateRoute(start, end, intermediates, mode, bans, shortest, timeoutMs);
		long t1 = System.currentTimeMillis();
		checkGeneration(gen);
		if (res == null || !res.isCalculated()) {
			LOG.info("Traffic candidate " + kind + ": no route");
			net.osmand.plus.chizu.ChizuDriveLog.log("candidate " + kind + " bans=" + bans.size() + " route " + (t1 - t0) + " ms: no route");
			return null;
		}
		Candidate c = grade(kind, res, bans, seen);
		net.osmand.plus.chizu.ChizuDriveLog.log("candidate " + kind + " bans=" + bans.size() + " route " + (t1 - t0) + " ms, traffic "
				+ (System.currentTimeMillis() - t1) + " ms"); // 白い熊 地図 drive log
		return c;
	}

	@Nullable
	private RouteCalculationResult calculateRoute(@NonNull Location start, @NonNull LatLon end,
	                                              @Nullable List<LatLon> intermediates, @NonNull ApplicationMode mode,
	                                              @NonNull Set<Long> bans, boolean shortest, long timeoutMs)
			throws InterruptedException {
		RouteCalculationParams params = new RouteCalculationParams();
		params.start = start;
		params.end = end;
		params.intermediates = intermediates;
		params.mode = mode;
		params.ctx = app;
		RoutingHelper.applyApplicationSettings(params, app.getSettings(), mode);
		params.calculationProgress = new RouteCalculationProgress();
		params.calculationProgressListener = QUIET_PROGRESS;
		params.chizuCandidate = true;
		params.chizuExtraImpassable = bans;
		params.chizuShortestWay = shortest;
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<RouteCalculationResult> result = new AtomicReference<>();
		params.alternateResultListener = res -> {
			result.set(res);
			done.countDown();
		};
		routingHelper.startRouteCalculationThread(params);
		if (!done.await(Math.min(timeoutMs, TimeUnit.SECONDS.toMillis(CALC_TIMEOUT_S)), TimeUnit.MILLISECONDS)) {
			net.osmand.plus.chizu.ChizuDriveLog.log("candidate calc timed out after " + timeoutMs + " ms");
			params.calculationProgress.isCancelled = true;
			return null;
		}
		return result.get();
	}

	@NonNull
	private Candidate grade(@NonNull Kind kind, @NonNull RouteCalculationResult route, @NonNull Set<Long> bans,
	                        @NonNull TrafficMap seen) throws InterruptedException {
		List<RouteSegmentResult> segments = route.getOriginalRoute();
		if (segments == null) {
			segments = Collections.emptyList();
		}
		List<RouteSegmentResult> sampled = headOf(segments, SAMPLE_AHEAD_M);
		List<Level> levels = new ArrayList<>(source.sample(sampled));
		while (levels.size() < segments.size()) {
			levels.add(Level.UNKNOWN);
		}
		seen.add(segments, levels, ChizuTrafficPrefs.getLongYellowMeters(app));
		return new Candidate(kind, route, segments, levels, bans);
	}

	@NonNull
	private static List<RouteSegmentResult> headOf(@NonNull List<RouteSegmentResult> segments, float meters) {
		float d = 0;
		int i = 0;
		while (i < segments.size() && d < meters) {
			d += segments.get(i).getDistance();
			i++;
		}
		return segments.subList(0, i);
	}

	private void checkGeneration(int gen) throws InterruptedException {
		if (gen != generation) {
			throw new InterruptedException("superseded");
		}
	}

	/** Every graded road seen this round, plus the roads near start and end that must never be banned. */
	private static final class TrafficMap {
		final Map<Long, Level> levels = new HashMap<>();
		final Map<Long, String> names = new HashMap<>();
		final Map<Long, Float> lengths = new HashMap<>();
		final Set<Long> longSlow = new HashSet<>();
		final Set<Long> protectedIds = new HashSet<>();

		void add(@NonNull List<RouteSegmentResult> segments, @NonNull List<Level> grades, float longYellowMeters) {
			for (int i = 0; i < segments.size(); i++) {
				Level l = grades.get(i);
				RouteSegmentResult seg = segments.get(i);
				long id = ChizuTraffic.roadId(seg);
				if (l != Level.UNKNOWN) {
					levels.put(id, l);
				}
				if (!names.containsKey(id)) {
					String n = seg.getObject().getName();
					String h = seg.getObject().getHighway();
					names.put(id, (n == null || n.isEmpty() ? "unnamed" : n) + " (" + h + (seg.getObject().tunnel() ? ", tunnel" : "") + ")");
				}
				Float len = lengths.get(id);
				lengths.put(id, (len == null ? 0f : len) + seg.getDistance());
			}
			longSlow.addAll(ChizuTraffic.longSlowIds(segments, grades, longYellowMeters));
			protectEnds(segments, protectedIds);
		}

		@NonNull
		Set<Long> bansFor(@NonNull Kind kind, @NonNull OsmandApplication app) {
			Set<Long> bans = new HashSet<>();
			for (Map.Entry<Long, Level> e : levels.entrySet()) {
				Level l = e.getValue();
				boolean ban = l.isJam()
						|| (kind == Kind.AVOID_YELLOW && l == Level.SLOW)
						|| (kind == Kind.AVOID_LONG_YELLOW && l == Level.SLOW && longSlow.contains(e.getKey()));
				if (ban) {
					bans.add(e.getKey());
				}
			}
			bans.removeAll(protectedIds);
			return bans;
		}
	}

	/** 白い熊 地図 drive log: the banned roads by name, longest first, with their colour. */
	@NonNull
	private static String describeBans(@NonNull Set<Long> bans, @NonNull TrafficMap map) {
		Map<String, float[]> byName = new HashMap<>(); // {metres, worst level ordinal}
		for (Long id : bans) {
			String name = map.names.get(id);
			if (name == null) {
				name = "road " + id;
			}
			float[] v = byName.get(name);
			if (v == null) {
				v = new float[2];
				byName.put(name, v);
			}
			Float len = map.lengths.get(id);
			v[0] += len == null ? 0 : len;
			Level l = map.levels.get(id);
			v[1] = Math.max(v[1], l == null ? 0 : l.ordinal());
		}
		List<Map.Entry<String, float[]>> list = new ArrayList<>(byName.entrySet());
		Collections.sort(list, (a, b) -> Float.compare(b.getValue()[0], a.getValue()[0]));
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < list.size() && i < 20; i++) {
			Map.Entry<String, float[]> e = list.get(i);
			if (sb.length() > 0) {
				sb.append(", ");
			}
			sb.append(e.getKey()).append(' ').append(Math.round(e.getValue()[0])).append(" m ")
					.append(Level.values()[(int) e.getValue()[1]].name().toLowerCase(java.util.Locale.US));
		}
		if (list.size() > 20) {
			sb.append(", … ").append(list.size() - 20).append(" more");
		}
		return sb.toString();
	}

	private static void protectEnds(@NonNull List<RouteSegmentResult> segments, @NonNull Set<Long> out) {
		float d = 0;
		for (RouteSegmentResult s : segments) {
			out.add(ChizuTraffic.roadId(s));
			d += s.getDistance();
			if (d >= PROTECT_START_M) {
				break;
			}
		}
		d = 0;
		for (int i = segments.size() - 1; i >= 0; i--) {
			out.add(ChizuTraffic.roadId(segments.get(i)));
			d += segments.get(i).getDistance();
			if (d >= PROTECT_END_M) {
				break;
			}
		}
	}

	// ---------- driving ----------

	private void scheduleDriveCheck() {
		if (driveCheck != null) {
			return; // already ticking; a recalculation does not restart the clock
		}
		driveCheck = new Runnable() {
			@Override
			public void run() {
				if (driveCheck != this) {
					return;
				}
				checkAhead(false);
				app.runInUIThread(this, ChizuTrafficPrefs.getIntervalMinutes(app) * 60_000L);
			}
		};
		app.runInUIThread(driveCheck, ChizuTrafficPrefs.getIntervalMinutes(app) * 60_000L);
	}

	private void cancelDriveCheck() {
		driveCheck = null;
	}

	/**
	 * Every minute while driving, and {@link #REROUTE_CHECK_DELAY_MS} after every off-route reroute:
	 * is there a faster way now? The rest of the current route is graded by the traffic of now and
	 * set against two fresh routes from here — the plain fastest, with no bans at all, so a road that
	 * was jammed when the route was planned is back in play once it is free; and one around the jams
	 * there are now. When the faster of the two saves at least the minimum saving, 地図 switches to it
	 * and says so. Either way, the bans the next off-route reroute keeps to become the jams of now.
	 */
	private void checkAhead(boolean afterReroute) {
		RouteCalculationResult route = routingHelper.getRoute();
		if (!routingHelper.isFollowingMode() || !isApplicable(route) || (checkQueued && !afterReroute)) {
			return;
		}
		List<RouteSegmentResult> ahead = route.getOriginalRoute(route.getCurrentRoute());
		Location location = routingHelper.getLastFixedLocation();
		LatLon end = routingHelper.getFinalLocation();
		if (ahead == null || ahead.isEmpty() || location == null || end == null) {
			return;
		}
		Location from = new Location(location);
		List<LatLon> intermediates = routingHelper.getIntermediatePoints();
		ApplicationMode mode = routingHelper.getAppMode();
		int gen = generation;
		checkQueued = true;
		worker.execute(() -> {
			checkQueued = false;
			try {
				long t0 = System.currentTimeMillis();
				List<RouteSegmentResult> head = headOf(ahead, SAMPLE_AHEAD_M);
				List<Level> levels = new ArrayList<>(source.sample(head));
				while (levels.size() < ahead.size()) {
					levels.add(Level.UNKNOWN); // beyond the sampled head: the router's own time
				}
				float currentTime = ChizuTraffic.adjustedTime(ahead, levels);
				Set<Long> jams = ChizuTraffic.jamIds(ahead, levels);
				Set<Long> protect = new HashSet<>();
				protectEnds(ahead, protect);
				TrafficMap seen = new TrafficMap();
				seen.add(ahead, levels, ChizuTrafficPrefs.getLongYellowMeters(app));
				long timeout = TimeUnit.SECONDS.toMillis(CALC_TIMEOUT_S);
				Candidate plain = calculate(gen, Kind.FASTEST, from, end, intermediates, mode,
						Collections.emptySet(), false, seen, timeout);
				if (plain != null) {
					jams.addAll(ChizuTraffic.jamIds(plain.segments, plain.levels));
					protectEnds(plain.segments, protect);
				}
				jams.removeAll(protect);
				if (!jams.isEmpty()) {
					net.osmand.plus.chizu.ChizuDriveLog.log("traffic check banned: " + describeBans(jams, seen)); // 白い熊 地図 drive log
				}
				Candidate around = jams.isEmpty() ? null : calculate(gen, Kind.AVOID_RED, from, end, intermediates,
						mode, jams, false, seen, timeout);
				Candidate best = plain;
				if (around != null && (best == null || around.adjustedTime < best.adjustedTime)) {
					best = around;
				}
				int saving = best == null ? 0 : Math.round(currentTime - best.adjustedTime);
				net.osmand.plus.chizu.ChizuDriveLog.log(String.format(java.util.Locale.US,
						"traffic check%s: current %.0f s, plain %s, around %d jams %s, saves %d s, took %d ms",
						afterReroute ? " after reroute" : "", currentTime,
						plain == null ? "-" : Math.round(plain.adjustedTime) + " s",
						jams.size(), around == null ? "-" : Math.round(around.adjustedTime) + " s",
						saving, System.currentTimeMillis() - t0)); // 白い熊 地図 drive log
				Candidate chosen = best;
				Set<Long> jamsNow = new HashSet<>(jams);
				app.runInUIThread(() -> {
					if (gen != generation || !routingHelper.isFollowingMode()) {
						return;
					}
					if (chosen != null && saving >= ChizuTrafficPrefs.getMinSavingMinutes(app) * 60) {
						net.osmand.plus.chizu.ChizuDriveLog.log("traffic reroute taken: " + chosen.kind + ", saves " + saving + " s");
						ChizuTrafficBans.setActive(chosen.bans);
						selecting = true;
						routingHelper.chizuSetActiveRoute(chosen.route);
						routingHelper.getVoiceRouter().chizuAnnounceTrafficReroute(saving);
					} else {
						ChizuTrafficBans.setActive(jamsNow);
					}
				});
			} catch (InterruptedException e) {
				// superseded
			} catch (Exception e) {
				LOG.error("Traffic check failed", e);
			}
		});
	}

	// ---------- plumbing ----------

	private void notifyChanged() {
		app.runInUIThread(() -> {
			app.getOsmandMap().refreshMap();
			for (WeakReference<Listener> ref : listeners) {
				Listener l = ref.get();
				if (l == null) {
					listeners.remove(ref);
				} else {
					l.onCandidatesChanged();
				}
			}
		});
	}

	private static final RouteCalculationProgressListener QUIET_PROGRESS = new RouteCalculationProgressListener() {
		@Override
		public void onCalculationStart() {
		}

		@Override
		public void onUpdateCalculationProgress(int progress) {
		}

		@Override
		public void onRequestPrivateAccessRouting() {
		}

		@Override
		public void onCalculationFinish() {
		}
	};
}
