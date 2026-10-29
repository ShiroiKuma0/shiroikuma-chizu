package net.osmand.plus.chizu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.binary.RouteDataObject;
import net.osmand.router.RouteSegmentResult;
import net.osmand.util.Algorithms;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 白い熊 地図: traffic along a route — levels per route segment, the road ids to ban for each
 * candidate, and the traffic-adjusted travel time used to rank candidates.
 * <p>
 * The source is pluggable ({@link Source}): Google traffic tiles today, a Czech NDIC/TraFF feed later.
 */
public final class ChizuTraffic {

	public enum Level {
		FREE, SLOW, JAM, STANDSTILL, UNKNOWN;

		public boolean isJam() {
			return this == JAM || this == STANDSTILL;
		}

		public boolean isSlowOrWorse() {
			return this == SLOW || isJam();
		}
	}

	public interface Source {
		/**
		 * Grades every segment of the list; the result is parallel to {@code segments}.
		 * Blocking — call from a background thread.
		 */
		@NonNull
		List<Level> sample(@NonNull List<RouteSegmentResult> segments) throws InterruptedException;
	}

	// assumed speeds through traffic, m/s: yellow 25 km/h, red 10 km/h, dark red 5 km/h
	private static final float SLOW_SPEED = 25f / 3.6f;
	private static final float JAM_SPEED = 10f / 3.6f;
	private static final float STANDSTILL_SPEED = 5f / 3.6f;

	private ChizuTraffic() {
	}

	public static long roadId(@NonNull RouteSegmentResult segment) {
		return segment.getObject().getId();
	}

	/** Router time plus the delay that the traffic on each segment adds. Seconds. */
	public static float adjustedTime(@NonNull List<RouteSegmentResult> segments, @NonNull List<Level> levels) {
		float total = 0;
		for (int i = 0; i < segments.size(); i++) {
			total += adjustedSegmentTime(segments.get(i), levels.get(i));
		}
		return total;
	}

	/** Delay over the router's own estimate, seconds. */
	public static float delay(@NonNull List<RouteSegmentResult> segments, @NonNull List<Level> levels) {
		float total = 0;
		for (int i = 0; i < segments.size(); i++) {
			RouteSegmentResult s = segments.get(i);
			total += adjustedSegmentTime(s, levels.get(i)) - s.getSegmentTime();
		}
		return total;
	}

	public static float routerTime(@NonNull List<RouteSegmentResult> segments) {
		float total = 0;
		for (RouteSegmentResult s : segments) {
			total += s.getSegmentTime();
		}
		return total;
	}

	public static float distance(@NonNull List<RouteSegmentResult> segments) {
		float total = 0;
		for (RouteSegmentResult s : segments) {
			total += s.getDistance();
		}
		return total;
	}

	private static float adjustedSegmentTime(@NonNull RouteSegmentResult s, @NonNull Level level) {
		float time = s.getSegmentTime();
		float speed;
		switch (level) {
			case SLOW:
				speed = SLOW_SPEED;
				break;
			case JAM:
				speed = JAM_SPEED;
				break;
			case STANDSTILL:
				speed = STANDSTILL_SPEED;
				break;
			default:
				return time;
		}
		return Math.max(time, s.getDistance() / speed);
	}

	/** Road ids of red and dark-red segments. */
	@NonNull
	public static Set<Long> jamIds(@NonNull List<RouteSegmentResult> segments, @NonNull List<Level> levels) {
		Set<Long> ids = new HashSet<>();
		for (int i = 0; i < segments.size(); i++) {
			if (levels.get(i).isJam()) {
				ids.add(roadId(segments.get(i)));
			}
		}
		return ids;
	}

	/** Road ids of yellow segments. */
	@NonNull
	public static Set<Long> slowIds(@NonNull List<RouteSegmentResult> segments, @NonNull List<Level> levels) {
		Set<Long> ids = new HashSet<>();
		for (int i = 0; i < segments.size(); i++) {
			if (levels.get(i) == Level.SLOW) {
				ids.add(roadId(segments.get(i)));
			}
		}
		return ids;
	}

	/**
	 * Road ids of yellow segments that belong to a run of slow-or-worse traffic longer than
	 * {@code minRunMeters} — the yellow before a traffic light is tolerated, a crawling avenue is not.
	 */
	@NonNull
	public static Set<Long> longSlowIds(@NonNull List<RouteSegmentResult> segments, @NonNull List<Level> levels,
	                                    float minRunMeters) {
		Set<Long> ids = new HashSet<>();
		int runStart = -1;
		float runLength = 0;
		for (int i = 0; i <= segments.size(); i++) {
			boolean slow = i < segments.size() && levels.get(i).isSlowOrWorse();
			if (slow) {
				if (runStart < 0) {
					runStart = i;
					runLength = 0;
				}
				runLength += segments.get(i).getDistance();
			} else if (runStart >= 0) {
				if (runLength > minRunMeters) {
					for (int k = runStart; k < i; k++) {
						if (levels.get(k) == Level.SLOW) {
							ids.add(roadId(segments.get(k)));
						}
					}
				}
				runStart = -1;
			}
		}
		return ids;
	}

	/** Share of {@code a}'s length that runs on roads also used by {@code b}. */
	public static float sharedFraction(@NonNull List<RouteSegmentResult> a, @NonNull List<RouteSegmentResult> b) {
		Set<Long> bIds = new HashSet<>();
		for (RouteSegmentResult s : b) {
			bIds.add(roadId(s));
		}
		float shared = 0;
		float total = 0;
		for (RouteSegmentResult s : a) {
			total += s.getDistance();
			if (bIds.contains(roadId(s))) {
				shared += s.getDistance();
			}
		}
		return total > 0 ? shared / total : 1f;
	}

	/** Name of the street carrying the most jam delay, or null. */
	@Nullable
	public static String worstStreet(@NonNull List<RouteSegmentResult> segments, @NonNull List<Level> levels) {
		Map<String, Float> delays = new HashMap<>();
		for (int i = 0; i < segments.size(); i++) {
			if (!levels.get(i).isJam()) {
				continue;
			}
			RouteSegmentResult s = segments.get(i);
			String name = streetName(s.getObject());
			if (name != null) {
				float d = adjustedSegmentTime(s, levels.get(i)) - s.getSegmentTime();
				Float prev = delays.get(name);
				delays.put(name, prev == null ? d : prev + d);
			}
		}
		String worst = null;
		float max = -1;
		for (Map.Entry<String, Float> e : delays.entrySet()) {
			if (e.getValue() > max) {
				max = e.getValue();
				worst = e.getKey();
			}
		}
		return worst;
	}

	@Nullable
	private static String streetName(@NonNull RouteDataObject object) {
		String name = object.getName();
		if (Algorithms.isEmpty(name)) {
			name = object.getRef(null, false, true);
		}
		return Algorithms.isEmpty(name) ? null : name;
	}
}
