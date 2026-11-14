package net.osmand.plus.chizu;

import androidx.annotation.NonNull;

import net.osmand.Location;
import net.osmand.util.MapUtils;

/**
 * 白い熊 地図 +041: where and which way the position is drawn while navigating (白い熊, 2026-11-05:
 * “the car's position must snap to roads … and it must NEVER change orientation — only once we start
 * moving in the other direction”), and since +061 whenever driving.
 * <ul>
 * <li><b>Always on a road.</b> On the route OsmAnd's own snap stands. Off it, {@link ChizuRoadMatcher}
 * picks the road among all roads around (+073) and holds the believed one by dead reckoning (+074); only
 * with no road to be had is the fix drawn as it came.</li>
 * <li><b>Facing the way the car moves.</b> At {@value #BEARING_TRUST_SPEED} m/s and above the receiver's
 * own bearing is the way the car moves — it is Doppler-based, so a poor position does not spoil it — and
 * the arrow takes the road's direction turned to agree with it (+074). Until +073 the road was turned to
 * agree with the <i>previous heading</i> instead, which pointed the arrow backwards on every turn sharper
 * than 90° (ten times on 2026-11-12). Below that speed the heading holds: the road may bend it by up to
 * {@value #ROAD_ALIGN_DEG}°, never turn it round, and only movement the other way over at least
 * {@value #MOVE_M} m lasting {@value #REVERSE_MS} ms (a parking manoeuvre) turns it round.</li>
 * <li><b>+075: a weak fix never turns it round.</b> On 2026-11-13 EMUI's engine dead-reckoned through
 * every loss of the satellites, bearing included, and a 55 m fix turned the arrow round on V Ondřejově
 * for 11 s. Worse than {@value #BEARING_TURN_ACC} m, the receiver's bearing may still bend the heading by
 * up to {@value #ROAD_ALIGN_DEG}°, and movement the other way is not believed either; a turn-round waits
 * for a good fix. And with a fix of 10 m or better the receiver is trusted from
 * {@value #BEARING_TRUST_SPEED_GOOD} m/s, so that a departure the other way is seen at once instead of at
 * {@value #BEARING_TRUST_SPEED} m/s (this morning: 200° wrong for the first metres).</li>
 * </ul>
 */
public final class ChizuDisplayFix {

	private static final float MOVE_M = 15f;
	private static final float MIN_SPEED = 2f;
	private static final float REVERSE_DEG = 120f;
	private static final long REVERSE_MS = 3000;
	private static final double ROAD_SNAP_M = 30;
	private static final float ROAD_ALIGN_DEG = 60f;
	private static final double ANCHOR_RESET_M = 300;
	/** The receiver's bearing is the car's course from this speed (18 km/h)… */
	private static final float BEARING_TRUST_SPEED = 5f;
	/** …or from this one with a fix of {@value #GOOD_ACC} m or better (+075)… */
	private static final float BEARING_TRUST_SPEED_GOOD = 3f;
	private static final float GOOD_ACC = 10f;
	/** …with any fix the gate lets through: it comes from the Doppler shift, not from the position. */
	private static final float BEARING_TRUST_ACC = 100f;
	/** +075: …but worse than this it may only bend the heading, never turn it round. */
	private static final float BEARING_TURN_ACC = 30f;
	private static final long COURSE_FRESH_MS = 5000;

	private float heading = Float.NaN;
	private double anchorLat;
	private double anchorLon;
	private boolean hasAnchor;
	private long reverseSince;
	private String lastState = "";
	private float lastCourse = Float.NaN;
	private long lastCourseMs;

	@NonNull
	public synchronized Location shown(@NonNull Location raw, @NonNull Location routed, boolean onRoute,
	                                   @NonNull ChizuRoadMatcher matcher) {
		long now = android.os.SystemClock.elapsedRealtime();
		Location out = new Location(routed);
		float speed = raw.hasSpeed() ? raw.getSpeed() : 0f;
		// 白い熊 地図 +061: weak fixes (30 m and worse on 2026-11-06) need a wider reach to find their road and a
		// longer stretch before their movement counts — noise over 15 m turned the arrow round 12 times
		float acc = raw.hasAccuracy() ? raw.getAccuracy() : 10f;
		double snapM = Math.max(ROAD_SNAP_M, Math.min(100, 1.5 * acc));
		// +073: the road the car is believed to be on, and those joined to it, are kept even with a weak fix
		double believedM = Math.max(snapM, Math.min(150, 2.5 * acc));
		double moveM = Math.max(MOVE_M, Math.min(60, 2 * acc));
		float trustFrom = acc <= GOOD_ACC ? BEARING_TRUST_SPEED_GOOD : BEARING_TRUST_SPEED;
		float trusted = raw.hasBearing() && speed >= trustFrom && acc <= BEARING_TRUST_ACC
				? raw.getBearing() : Float.NaN;
		// +075: a weak fix's bearing may bend the heading, never turn it round
		boolean weakBearing = acc > BEARING_TURN_ACC;
		if (!Float.isNaN(trusted) && weakBearing && !Float.isNaN(heading)
				&& ChizuRoadMatcher.diff(trusted, heading) > ROAD_ALIGN_DEG) {
			trusted = Float.NaN;
		}

		// the car's own course over the last stretch of at least MOVE_M
		float course = Float.NaN;
		if (!hasAnchor) {
			anchor(raw);
		} else {
			double d = MapUtils.getDistance(anchorLat, anchorLon, raw.getLatitude(), raw.getLongitude());
			if (d > ANCHOR_RESET_M) {
				anchor(raw); // a gap (a tunnel's end): no course across it
			} else if (d >= moveM) {
				course = ChizuRoadMatcher.bearing(anchorLat, anchorLon, raw.getLatitude(), raw.getLongitude());
				anchor(raw);
				lastCourse = course;
				lastCourseMs = now;
			}
		}
		// the way the car moves, as far as it is known: the receiver at speed, else the recent course
		float movement = !Float.isNaN(trusted) ? trusted
				: !Float.isNaN(lastCourse) && now - lastCourseMs <= COURSE_FRESH_MS ? lastCourse : Float.NaN;

		// the road's direction: the route's, or the matched road's when off the route
		float roadDir = Float.NaN;
		String state;
		ChizuRoadMatcher.Match m = null;
		if (onRoute) {
			matcher.forget();
			if (routed.hasBearing()) {
				roadDir = routed.getBearing();
			}
			state = "on the route";
		} else {
			matcher.request(raw.getLatitude(), raw.getLongitude());
			float ref = !Float.isNaN(movement) ? movement : heading;
			m = matcher.match(raw.getLatitude(), raw.getLongitude(), ref, speed, acc, snapM, believedM, ROAD_ALIGN_DEG);
			if (m != null) {
				out.setLatitude(m.lat);
				out.setLongitude(m.lon);
				roadDir = m.dir;
				state = "held".equals(m.how) ? "held on the last road"
						: "held/weak".equals(m.how) ? "held on the last road through weak fixes" : "on the nearest road";
			} else {
				state = "free, no road near";
			}
		}
		if (!state.equals(lastState)) {
			lastState = state;
			ChizuDriveLog.log("loc display " + state);
		}
		if (ChizuDriveLog.on(ChizuDiag.Group.LOCATION)) {
			ChizuDriveLog.log(String.format(java.util.Locale.US, "loc match %s acc=%.0f spd=%.1f brg=%s course=%s heading=%s",
					onRoute ? "route" : m == null ? "free" : m.describe(), acc, speed, deg(trusted), deg(lastCourse), deg(heading)));
		}

		// the heading
		float before = heading;
		if (!Float.isNaN(trusted)) {
			// at speed the receiver knows which way the car goes; the road refines it when it agrees
			float target = trusted;
			if (!Float.isNaN(roadDir)) {
				float along = ChizuRoadMatcher.align(roadDir, trusted);
				if (ChizuRoadMatcher.diff(along, trusted) <= ROAD_ALIGN_DEG) {
					target = along;
				}
			}
			heading = target;
			reverseSince = 0;
		} else if (Float.isNaN(heading)) {
			float by = !Float.isNaN(movement) ? movement : raw.hasBearing() && speed >= MIN_SPEED ? raw.getBearing() : Float.NaN;
			heading = !Float.isNaN(roadDir) ? (Float.isNaN(by) ? roadDir : ChizuRoadMatcher.align(roadDir, by)) : by;
		} else if (!Float.isNaN(course) && !weakBearing && ChizuRoadMatcher.diff(course, heading) > REVERSE_DEG) {
			// slow, and moving the other way: turn round only once it has lasted — and never on weak fixes (+075)
			if (reverseSince == 0) {
				reverseSince = now;
			} else if (now - reverseSince >= REVERSE_MS) {
				heading = !Float.isNaN(roadDir) ? ChizuRoadMatcher.align(roadDir, course) : course;
				reverseSince = 0;
			}
		} else {
			// slow: hold, letting the road bend the heading but never turn it round
			if (!Float.isNaN(course)) {
				reverseSince = 0;
			}
			if (!Float.isNaN(roadDir) && (speed >= MIN_SPEED || !Float.isNaN(course))) {
				float target = ChizuRoadMatcher.align(roadDir, heading);
				if (ChizuRoadMatcher.diff(target, heading) <= ROAD_ALIGN_DEG) {
					heading = target;
				}
			}
		}
		if (!Float.isNaN(before) && !Float.isNaN(heading) && ChizuRoadMatcher.diff(before, heading) > REVERSE_DEG) {
			ChizuDriveLog.log(String.format(java.util.Locale.US, "loc display turned round %.0f° → %.0f° (%s)", before, heading,
					!Float.isNaN(trusted) ? "receiver bearing" : "moved the other way"));
		}
		if (!Float.isNaN(heading)) {
			out.setBearing(heading);
		}
		return out;
	}

	@NonNull
	private static String deg(float a) {
		return Float.isNaN(a) ? "-" : String.format(java.util.Locale.US, "%.0f", a);
	}

	public synchronized void reset() {
		heading = Float.NaN;
		hasAnchor = false;
		reverseSince = 0;
		lastCourse = Float.NaN;
		lastState = "";
	}

	private void anchor(@NonNull Location l) {
		anchorLat = l.getLatitude();
		anchorLon = l.getLongitude();
		hasAnchor = true;
	}
}
