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
import java.util.Iterator;
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
 * yellow; and plain shortest (no bans). Each is graded by its own traffic and ranked by
 * traffic-adjusted time. The chooser lists the traffic variants best first, then shortest, then the
 * plain fastest route (which ignores traffic) at the bottom. Near-duplicates are merged; when nothing
 * differs from the plain route, no chooser is shown.
 * <p>
 * <b>Driving.</b> Every few minutes the road ahead is re-read; when a fresh jam would cost more than
 * the minimum saving, a detour around it is computed and offered ({@link OfferListener}).
 * <p>
 * The bans of the chosen route are held by {@link ChizuTrafficBans} so off-route recalculations keep
 * avoiding the same jams. Nothing is written to settings.
 */
public class ChizuRouteCandidates implements IRouteInformationListener {

	private static final Log LOG = PlatformUtil.getLog(ChizuRouteCandidates.class);

	private static final float SAMPLE_AHEAD_M = 40000f;
	private static final float PROTECT_START_M = 400f;
	private static final float PROTECT_END_M = 200f;
	private static final float DUPLICATE_SHARE = 0.85f;
	private static final long CALC_TIMEOUT_S = 90;
	private static final long DECLINE_MEMORY_MS = 10 * 60 * 1000;

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

	public interface OfferListener {
		/**
		 * UI thread: a detour avoiding a fresh jam saves {@code savingSeconds}.
		 * Call {@link #acceptOffer} or {@link #declineOffer}.
		 */
		void onRerouteOffer(@NonNull Candidate detour, int savingSeconds, @Nullable String street);
	}

	private final OsmandApplication app;
	private final RoutingHelper routingHelper;
	private final ChizuGoogleTrafficSource source = new ChizuGoogleTrafficSource();
	private final ExecutorService worker = Executors.newSingleThreadExecutor();

	private final List<WeakReference<Listener>> listeners = new CopyOnWriteArrayList<>();
	private volatile OfferListener offerListener;

	private volatile int generation;
	private volatile boolean working;
	private volatile boolean selecting;
	private volatile List<Candidate> candidates = Collections.emptyList();
	private volatile int selected = -1;

	private Candidate pendingOffer;
	private final Map<Long, Long> declined = new HashMap<>();
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

	public void setOfferListener(@Nullable OfferListener listener) {
		offerListener = listener;
	}

	/** Makes candidate {@code index} the active route. UI thread. */
	public void select(int index) {
		List<Candidate> list = candidates;
		if (index < 0 || index >= list.size() || index == selected) {
			return;
		}
		Candidate c = list.get(index);
		selected = index;
		ChizuTrafficBans.setActive(c.bans);
		selecting = true;
		routingHelper.chizuSetActiveRoute(c.route);
		notifyChanged();
	}

	// ---------- route events ----------

	@Override
	public void newRouteIsCalculated(boolean newRoute, ValueHolder<Boolean> showToast) {
		if (selecting) {
			// the route we set ourselves, from the chooser or an accepted offer
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
		}
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
		stopAll();
	}

	private void stopAll() {
		generation++;
		ChizuTrafficBans.clear();
		synchronized (declined) {
			declined.clear();
		}
		cancelDriveCheck();
		pendingOffer = null;
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
				List<Candidate> result = computeCandidates(gen, baseline, start, end, intermediates, mode);
				app.runInUIThread(() -> {
					if (gen != generation) {
						return;
					}
					working = false;
					candidates = result;
					selected = -1;
					if (result.size() > 1) {
						// the best traffic-aware route is pre-selected, as Waze does
						select(0);
					}
					notifyChanged();
				});
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

	@NonNull
	private List<Candidate> computeCandidates(int gen, @NonNull RouteCalculationResult baseline, @NonNull Location start,
	                                          @NonNull LatLon end, @Nullable List<LatLon> intermediates,
	                                          @NonNull ApplicationMode mode) throws InterruptedException {
		TrafficMap seen = new TrafficMap();
		Candidate fastest = grade(Kind.FASTEST, baseline, Collections.emptySet(), seen);
		checkGeneration(gen);

		List<Candidate> variants = new ArrayList<>();
		for (Kind kind : new Kind[] {Kind.AVOID_RED, Kind.AVOID_LONG_YELLOW, Kind.AVOID_YELLOW}) {
			Set<Long> bans = seen.bansFor(kind, app);
			if (bans.isEmpty()) {
				continue; // identical to the plain route
			}
			Candidate c = calculate(gen, kind, start, end, intermediates, mode, bans, false, seen);
			if (c != null) {
				// one refinement: jams the detour itself runs into
				Set<Long> refined = seen.bansFor(kind, app);
				if (!refined.equals(bans)) {
					Candidate r = calculate(gen, kind, start, end, intermediates, mode, refined, false, seen);
					if (r != null && r.adjustedTime < c.adjustedTime) {
						c = r;
					}
				}
				variants.add(c);
			}
		}
		Candidate shortest = calculate(gen, Kind.SHORTEST, start, end, intermediates, mode,
				Collections.emptySet(), true, seen);

		// traffic variants best first, near-duplicates merged into the better one or into the plain route
		Collections.sort(variants, (a, b) -> Float.compare(a.adjustedTime, b.adjustedTime));
		List<Candidate> shown = new ArrayList<>();
		for (Candidate c : variants) {
			if (!isDuplicate(c, shown) && !isDuplicate(c, Collections.singletonList(fastest))) {
				shown.add(c);
			}
		}
		if (shortest != null) {
			List<Candidate> others = new ArrayList<>(shown);
			others.add(fastest);
			if (!isDuplicate(shortest, others)) {
				shown.add(shortest);
			}
		}
		shown.add(fastest);
		LOG.info("Traffic candidates: " + shown.size() + " shown of " + (variants.size() + 2));
		return shown.size() > 1 ? shown : Collections.emptyList();
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
	                            @NonNull Set<Long> bans, boolean shortest, @NonNull TrafficMap seen)
			throws InterruptedException {
		checkGeneration(gen);
		RouteCalculationResult res = calculateRoute(start, end, intermediates, mode, bans, shortest);
		checkGeneration(gen);
		if (res == null || !res.isCalculated()) {
			LOG.info("Traffic candidate " + kind + ": no route");
			return null;
		}
		return grade(kind, res, bans, seen);
	}

	@Nullable
	private RouteCalculationResult calculateRoute(@NonNull Location start, @NonNull LatLon end,
	                                              @Nullable List<LatLon> intermediates, @NonNull ApplicationMode mode,
	                                              @NonNull Set<Long> bans, boolean shortest)
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
		if (!done.await(CALC_TIMEOUT_S, TimeUnit.SECONDS)) {
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
		final Set<Long> longSlow = new HashSet<>();
		final Set<Long> protectedIds = new HashSet<>();

		void add(@NonNull List<RouteSegmentResult> segments, @NonNull List<Level> grades, float longYellowMeters) {
			for (int i = 0; i < segments.size(); i++) {
				Level l = grades.get(i);
				if (l != Level.UNKNOWN) {
					levels.put(ChizuTraffic.roadId(segments.get(i)), l);
				}
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
				checkAhead();
				app.runInUIThread(this, ChizuTrafficPrefs.getIntervalMinutes(app) * 60_000L);
			}
		};
		app.runInUIThread(driveCheck, ChizuTrafficPrefs.getIntervalMinutes(app) * 60_000L);
	}

	private void cancelDriveCheck() {
		driveCheck = null;
	}

	private void checkAhead() {
		RouteCalculationResult route = routingHelper.getRoute();
		if (!routingHelper.isFollowingMode() || pendingOffer != null || !isApplicable(route)) {
			return;
		}
		List<RouteSegmentResult> ahead = route.getOriginalRoute(route.getCurrentRoute());
		Location location = routingHelper.getLastFixedLocation();
		LatLon end = routingHelper.getFinalLocation();
		if (ahead == null || ahead.isEmpty() || location == null || end == null) {
			return;
		}
		List<LatLon> intermediates = routingHelper.getIntermediatePoints();
		ApplicationMode mode = routingHelper.getAppMode();
		int gen = generation;
		worker.execute(() -> {
			try {
				List<RouteSegmentResult> head = headOf(ahead, SAMPLE_AHEAD_M);
				List<Level> levels = new ArrayList<>(source.sample(head));
				Set<Long> jams = ChizuTraffic.jamIds(head, levels);
				Set<Long> protect = new HashSet<>();
				protectEnds(ahead, protect);
				jams.removeAll(protect);
				long now = System.currentTimeMillis();
				synchronized (declined) {
					for (Iterator<Map.Entry<Long, Long>> it = declined.entrySet().iterator(); it.hasNext(); ) {
						Map.Entry<Long, Long> e = it.next();
						if (now - e.getValue() > DECLINE_MEMORY_MS) {
							it.remove();
						} else {
							jams.remove(e.getKey());
						}
					}
				}
				jams.removeAll(ChizuTrafficBans.getActive());
				if (jams.isEmpty()) {
					return;
				}
				while (levels.size() < ahead.size()) {
					levels.add(Level.UNKNOWN); // beyond the sampled head: the router's own time
				}
				float remainingTime = ChizuTraffic.adjustedTime(ahead, levels);
				Set<Long> bans = new HashSet<>(ChizuTrafficBans.getActive());
				bans.addAll(jams);
				RouteCalculationResult res = calculateRoute(new Location(location), end, intermediates, mode, bans, false);
				if (gen != generation || res == null || !res.isCalculated()) {
					return;
				}
				Candidate detour = grade(Kind.AVOID_RED, res, bans, new TrafficMap());
				float detourTime = detour.adjustedTime;
				int saving = Math.round(remainingTime - detourTime);
				LOG.info("Traffic ahead: " + jams.size() + " jammed roads, detour saves " + saving + " s");
				if (saving < ChizuTrafficPrefs.getMinSavingMinutes(app) * 60) {
					return;
				}
				String street = ChizuTraffic.worstStreet(ahead, levels);
				app.runInUIThread(() -> {
					OfferListener l = offerListener;
					if (gen != generation || l == null || !routingHelper.isFollowingMode()) {
						return;
					}
					pendingOffer = detour;
					l.onRerouteOffer(detour, saving, street);
				});
			} catch (InterruptedException e) {
				// superseded
			} catch (Exception e) {
				LOG.error("Traffic check failed", e);
			}
		});
	}

	/** UI thread. */
	public void acceptOffer() {
		Candidate c = pendingOffer;
		pendingOffer = null;
		if (c == null || !routingHelper.isFollowingMode()) {
			return;
		}
		ChizuTrafficBans.setActive(c.bans);
		selecting = true;
		routingHelper.chizuSetActiveRoute(c.route);
	}

	/** UI thread: the jams of this offer are not offered again for ten minutes. */
	public void declineOffer() {
		Candidate c = pendingOffer;
		pendingOffer = null;
		if (c == null) {
			return;
		}
		Set<Long> mine = new HashSet<>(c.bans);
		mine.removeAll(ChizuTrafficBans.getActive());
		long now = System.currentTimeMillis();
		synchronized (declined) {
			for (Long id : mine) {
				declined.put(id, now);
			}
		}
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
