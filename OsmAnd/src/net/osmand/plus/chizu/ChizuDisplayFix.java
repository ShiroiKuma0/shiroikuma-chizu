package net.osmand.plus.chizu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.binary.RouteDataObject;
import net.osmand.data.LatLon;
import net.osmand.util.MapUtils;

/**
 * 白い熊 地図 +041: where and which way the position is drawn while navigating (白い熊, 2026-11-05:
 * “the car's position must snap to roads … and it must NEVER change orientation — only once we start
 * moving in the other direction”).
 * <ul>
 * <li><b>Always on a road.</b> On the route OsmAnd's own snap stands. Off it, the position is put on
 * the nearest road within {@value #ROAD_SNAP_M} m that runs the way the car moves; only with no such
 * road is the fix drawn as it came.</li>
 * <li><b>Facing the way the car moves.</b> The direction comes from the road, turned to agree with the
 * current heading, and from the car's own movement over at least {@value #MOVE_M} m — never from a
 * single fix's bearing, which is noise at low speed and random in a stray fix. Below
 * {@value #MIN_SPEED} m/s it holds. It turns round only when the movement has gone the other way for
 * {@value #REVERSE_MS} ms.</li>
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

	private float heading = Float.NaN;
	private double anchorLat;
	private double anchorLon;
	private boolean hasAnchor;
	private long reverseSince;
	private boolean lastSnapped;

	@NonNull
	public synchronized Location shown(@NonNull Location raw, @NonNull Location routed, boolean onRoute,
	                                   @Nullable RouteDataObject road) {
		long now = System.currentTimeMillis();
		Location out = new Location(routed);
		float speed = raw.hasSpeed() ? raw.getSpeed() : 0f;
		// 白い熊 地図 +061: weak fixes (30 m and worse on 2026-11-06) need a wider reach to find their road and a
		// longer stretch before their movement counts — noise over 15 m turned the arrow round 12 times
		float acc = raw.hasAccuracy() ? raw.getAccuracy() : 10f;
		double snapM = Math.max(ROAD_SNAP_M, Math.min(100, 1.5 * acc));
		double moveM = Math.max(MOVE_M, Math.min(60, 2 * acc));

		// the car's own course over the last stretch of at least MOVE_M
		float course = Float.NaN;
		if (!hasAnchor) {
			anchor(raw);
		} else {
			double d = MapUtils.getDistance(anchorLat, anchorLon, raw.getLatitude(), raw.getLongitude());
			if (d > ANCHOR_RESET_M) {
				anchor(raw); // a gap (a tunnel's end): no course across it
			} else if (d >= moveM) {
				course = bearing(anchorLat, anchorLon, raw.getLatitude(), raw.getLongitude());
				anchor(raw);
			}
		}

		// the road's direction: the route's, or the nearest road's when off the route
		float roadBearing = Float.NaN;
		boolean snapped = false;
		if (onRoute) {
			if (routed.hasBearing()) {
				roadBearing = routed.getBearing();
			}
		} else if (road != null && road.getPointsLength() > 1) {
			float ref = !Float.isNaN(heading) ? heading : !Float.isNaN(course) ? course
					: raw.hasBearing() ? raw.getBearing() : Float.NaN;
			double best = Double.MAX_VALUE;
			LatLon bestPoint = null;
			float bestDir = Float.NaN;
			for (int i = 1; i < road.getPointsLength(); i++) {
				double lat1 = MapUtils.get31LatitudeY(road.getPoint31YTile(i - 1));
				double lon1 = MapUtils.get31LongitudeX(road.getPoint31XTile(i - 1));
				double lat2 = MapUtils.get31LatitudeY(road.getPoint31YTile(i));
				double lon2 = MapUtils.get31LongitudeX(road.getPoint31XTile(i));
				LatLon p = MapUtils.getProjection(raw.getLatitude(), raw.getLongitude(), lat1, lon1, lat2, lon2);
				double d = MapUtils.getDistance(p.getLatitude(), p.getLongitude(), raw.getLatitude(), raw.getLongitude());
				if (d < best) {
					best = d;
					bestPoint = p;
					bestDir = bearing(lat1, lon1, lat2, lon2);
				}
			}
			if (bestPoint != null && best <= snapM) {
				float dir = Float.isNaN(ref) ? bestDir : align(bestDir, ref);
				if (Float.isNaN(ref) || diff(dir, ref) <= ROAD_ALIGN_DEG) {
					out.setLatitude(bestPoint.getLatitude());
					out.setLongitude(bestPoint.getLongitude());
					roadBearing = dir;
					snapped = true;
				}
			}
		}
		if (snapped != lastSnapped) {
			lastSnapped = snapped;
			ChizuDriveLog.log("loc display " + (snapped ? "on the nearest road" : onRoute ? "on the route" : "free, no road near"));
		}

		// the heading: only ever the way the car moves
		if (Float.isNaN(heading)) {
			heading = !Float.isNaN(roadBearing) ? roadBearing : !Float.isNaN(course) ? course
					: raw.hasBearing() && speed >= MIN_SPEED ? raw.getBearing() : Float.NaN;
		} else if (!Float.isNaN(course) && diff(course, heading) > REVERSE_DEG) {
			// moving the other way: turn round only once it has lasted
			if (reverseSince == 0) {
				reverseSince = now;
			} else if (now - reverseSince >= REVERSE_MS) {
				float from = heading;
				heading = !Float.isNaN(roadBearing) ? align(roadBearing, course) : course;
				reverseSince = 0;
				ChizuDriveLog.log(String.format(java.util.Locale.US, "loc display turned round %.0f° → %.0f°", from, heading));
			}
		} else if (speed >= MIN_SPEED || !Float.isNaN(course)) {
			if (!Float.isNaN(course)) {
				reverseSince = 0;
			}
			float target = !Float.isNaN(roadBearing) ? align(roadBearing, heading) : course;
			if (!Float.isNaN(target) && diff(target, heading) <= REVERSE_DEG) {
				heading = target;
			}
		}
		if (!Float.isNaN(heading)) {
			out.setBearing(heading);
		}
		return out;
	}

	public synchronized void reset() {
		heading = Float.NaN;
		hasAnchor = false;
		reverseSince = 0;
	}

	private void anchor(@NonNull Location l) {
		anchorLat = l.getLatitude();
		anchorLon = l.getLongitude();
		hasAnchor = true;
	}

	/** The road's direction or its opposite, whichever is closer to {@code ref}. */
	private static float align(float roadDir, float ref) {
		return diff(roadDir, ref) <= 90 ? norm(roadDir) : norm(roadDir + 180);
	}

	private static float diff(float a, float b) {
		float d = Math.abs(norm(a) - norm(b));
		return d > 180 ? 360 - d : d;
	}

	private static float norm(float a) {
		a %= 360;
		return a < 0 ? a + 360 : a;
	}

	private static float bearing(double lat1, double lon1, double lat2, double lon2) {
		Location a = new Location("", lat1, lon1);
		return norm(a.bearingTo(new Location("", lat2, lon2)));
	}
}
