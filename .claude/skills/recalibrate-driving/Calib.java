import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteTypeRule;
import net.osmand.binary.RouteDataObject;
import net.osmand.router.*;
import net.osmand.router.BinaryRoutePlanner.RouteSegmentPoint;
import net.osmand.util.MapUtils;
import java.io.*;
import java.util.*;
import java.util.regex.*;

/** Time lost at traffic lights and at turns, from 白い熊's drive logs (car fixes) and the OBF maps. */
public class Calib {
	static class Fix { double t, lat, lon, spd, d; int trip; }
	public static void main(String[] a) throws Exception {
		List<String> logs = new ArrayList<>(), maps = new ArrayList<>();
		for (String s : a) (s.endsWith(".obf") ? maps : logs).add(s);
		List<BinaryMapIndexReader> rs = new ArrayList<>();
		for (String m : maps) rs.add(new BinaryMapIndexReader(new RandomAccessFile(m, "r"), new File(m)));
		RoutingConfiguration cfg = RoutingConfiguration.getDefault().build("car", new RoutingConfiguration.RoutingMemoryLimits(1500, 1500));
		RoutePlannerFrontEnd fe = new RoutePlannerFrontEnd();
		RoutingContext ctx = fe.buildRoutingContext(cfg, null, rs.toArray(new BinaryMapIndexReader[0]), RoutePlannerFrontEnd.RouteCalculationMode.NORMAL);
		Pattern p = Pattern.compile("^(\\d\\d):(\\d\\d):(\\d\\d)\\.(\\d+) \\[main\\] loc aa \\S+ ([-0-9.]+),([-0-9.]+) acc=([0-9.]+) spd=([-0-9.]+)");
		List<Fix> fx = new ArrayList<>();
		int trip = 0;
		for (int li = 0; li < logs.size(); li++) {
			BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(logs.get(li)), "UTF-8"));
			String line; Fix last = null; trip++;
			boolean sim = false;
			while ((line = br.readLine()) != null) {
				// simulated drives (test receiver, head unit) are not 白い熊 driving
				if (line.contains("test sim on")) sim = true;
				else if (line.contains("test sim off") || line.contains("] start 白い熊 地図") || line.endsWith("] arrived")) sim = false;
				if (sim) continue;
				Matcher m = p.matcher(line);
				if (!m.find()) continue;
				Fix f = new Fix();
				f.t = li * 100000 + Integer.parseInt(m.group(1)) * 3600 + Integer.parseInt(m.group(2)) * 60 + Integer.parseInt(m.group(3)) + Integer.parseInt(m.group(4)) / 1000.0;
				f.lat = Double.parseDouble(m.group(5)); f.lon = Double.parseDouble(m.group(6));
				double acc = Double.parseDouble(m.group(7)); f.spd = Double.parseDouble(m.group(8));
				if (acc > 20 || f.spd < 0) continue;
				if (last != null) {
					double dt = f.t - last.t;
					if (dt <= 0) continue;
					double dd = MapUtils.getDistance(last.lat, last.lon, f.lat, f.lon);
					if (dd / dt > 70) continue;
					if (dt > 60) trip++;
					f.d = (f.trip = trip) == last.trip ? last.d + dd : 0;
				} else f.trip = trip;
				f.trip = trip;
				fx.add(f); last = f;
			}
		}
		System.err.println("fixes " + fx.size());
		// traffic signals on the roads under the track
		Map<String, double[]> signals = new HashMap<>();
		Set<Long> seenRoads = new HashSet<>();
		for (int i = 0; i < fx.size(); i += 4) {
			Fix f = fx.get(i);
			if (f.spd < 1) continue;
			RouteSegmentPoint sp = fe.findRouteSegment(f.lat, f.lon, ctx, null);
			if (sp == null) continue;
			RouteDataObject r = sp.getRoad();
			if (!seenRoads.add(r.getId())) continue;
			for (int k = 0; k < r.getPointsLength(); k++) {
				int[] types = r.getPointTypes(k);
				if (types == null) continue;
				for (int ty : types) {
					RouteTypeRule rule = r.region.quickGetEncodingRule(ty);
					if (rule != null && "highway".equals(rule.getTag()) && "traffic_signals".equals(rule.getValue())) {
						double la = MapUtils.get31LatitudeY(r.getPoint31YTile(k)), lo = MapUtils.get31LongitudeX(r.getPoint31XTile(k));
						signals.put(Math.round(la * 1e4) + ":" + Math.round(lo * 1e4), new double[] {la, lo});
					}
				}
			}
		}
		System.err.println("roads " + seenRoads.size() + " signals " + signals.size());
		// cluster signals of one junction (within 50 m)
		List<double[]> sig = new ArrayList<>(signals.values()), junctions = new ArrayList<>();
		for (double[] s : sig) {
			boolean merged = false;
			for (double[] j : junctions) if (MapUtils.getDistance(j[0], j[1], s[0], s[1]) < 50) { merged = true; break; }
			if (!merged) junctions.add(s);
		}
		System.err.println("junctions " + junctions.size());
		// signal nodes the track itself runs over (within 8 m), per junction pass
		{
			int nodesPassed = 0;
			for (double[] sg : sig) {
				for (int i = 0; i < fx.size(); i++) {
					Fix f = fx.get(i);
					if (MapUtils.getDistance(f.lat, f.lon, sg[0], sg[1]) < 8) {
						nodesPassed++;
						while (i + 1 < fx.size() && fx.get(i + 1).trip == f.trip && MapUtils.getDistance(fx.get(i + 1).lat, fx.get(i + 1).lon, sg[0], sg[1]) < 60) i++;
					}
				}
			}
			System.err.println("signal nodes run over " + nodesPassed);
		}
		// passes: local minima of distance to a junction, under 25 m
		List<double[]> sigLost = new ArrayList<>();
		double R = 150;
		List<double[]> passPts = new ArrayList<>();
		for (double[] j : junctions) {
			for (int i = 1; i + 1 < fx.size(); i++) {
				Fix f = fx.get(i);
				double d = MapUtils.getDistance(f.lat, f.lon, j[0], j[1]);
				if (d > 25) continue;
				int k = i; double best = d;
				while (k + 1 < fx.size() && fx.get(k + 1).trip == f.trip && MapUtils.getDistance(fx.get(k + 1).lat, fx.get(k + 1).lon, j[0], j[1]) <= best + 0.5) {
					k++; best = Math.min(best, MapUtils.getDistance(fx.get(k).lat, fx.get(k).lon, j[0], j[1]));
				}
				double[] r = lost(fx, k, R);
				if (r != null) { sigLost.add(r); passPts.add(new double[] {fx.get(k).lat, fx.get(k).lon}); }
				// skip past the junction
				while (i + 1 < fx.size() && fx.get(i + 1).trip == f.trip && MapUtils.getDistance(fx.get(i + 1).lat, fx.get(i + 1).lon, j[0], j[1]) < 60) i++;
			}
		}
		report("traffic lights (per junction passed)", sigLost);
		// turns without a light: heading change over ±30 m of at least 60°
		List<double[]> left = new ArrayList<>(), right = new ArrayList<>();
		double RT = 100;
		for (int i = 1; i + 1 < fx.size(); i++) {
			Fix f = fx.get(i);
			int b = at(fx, i, f.d - 30), e = at(fx, i, f.d + 30);
			if (b < 0 || e < 0) continue;
			double h1 = brg(fx.get(b), f), h2 = brg(f, fx.get(e));
			double dh = MapUtils.degreesDiff(h2, h1);
			if (Math.abs(dh) < 45 || Math.abs(dh) > 150) continue;
			boolean nearSignal = false;
			for (double[] pp : junctions) if (MapUtils.getDistance(pp[0], pp[1], f.lat, f.lon) < 60) { nearSignal = true; break; }
			// take the sharpest point of this turn, then jump past it
			int k = i; double bestAbs = Math.abs(dh);
			while (k + 1 < fx.size() && fx.get(k + 1).d < f.d + 30 && fx.get(k + 1).trip == f.trip) {
				k++;
				int b2 = at(fx, k, fx.get(k).d - 30), e2 = at(fx, k, fx.get(k).d + 30);
				if (b2 < 0 || e2 < 0) break;
				double d2 = Math.abs(MapUtils.degreesDiff(brg(fx.get(k), fx.get(e2)), brg(fx.get(b2), fx.get(k))));
				if (d2 > bestAbs) { bestAbs = d2; i = k; }
			}
			if (!nearSignal) {
				double[] r = lost(fx, i, RT);
				if (r != null) (dh > 0 ? right : left).add(r);
			}
			while (i + 1 < fx.size() && fx.get(i + 1).d < fx.get(i).d + 60 && fx.get(i + 1).trip == f.trip) i++;
		}
		report("right turns, no light", right);
		report("left turns, no light", left);
	}
	static double brg(Fix a, Fix b) {
		return Math.toDegrees(Math.atan2((b.lon - a.lon) * Math.cos(Math.toRadians(a.lat)), b.lat - a.lat));
	}
	/** index of the fix at track distance d in the same trip, searching from i; -1 if outside */
	static int at(List<Fix> fx, int i, double d) {
		int trip = fx.get(i).trip;
		if (d < fx.get(i).d) { for (int k = i; k >= 0 && fx.get(k).trip == trip; k--) if (fx.get(k).d <= d) return k; return -1; }
		for (int k = i; k < fx.size() && fx.get(k).trip == trip; k++) if (fx.get(k).d >= d) return k; return -1;
	}
	static double timeAt(List<Fix> fx, int i, double d) {
		int k = at(fx, i, d);
		if (k < 0) return Double.NaN;
		Fix f = fx.get(k);
		if (f.d == d) return f.t;
		// interpolate between the fixes either side of d
		int o = f.d > d ? k - 1 : k + 1;
		if (o < 0 || o >= fx.size() || fx.get(o).trip != f.trip) return f.t;
		Fix g = fx.get(o);
		if (g.d == f.d) return f.t;
		return f.t + (g.t - f.t) * (d - f.d) / (g.d - f.d);
	}
	/** {lost s, cruise km/h, stopped 0/1} or null when the cruise around is too slow to tell */
	static double[] lost(List<Fix> fx, int i, double R) {
		double d0 = fx.get(i).d;
		double a0 = timeAt(fx, i, d0 - 3 * R), a1 = timeAt(fx, i, d0 - R), b0 = timeAt(fx, i, d0 + R), b1 = timeAt(fx, i, d0 + 3 * R);
		if (Double.isNaN(a0) || Double.isNaN(a1) || Double.isNaN(b0) || Double.isNaN(b1)) return null;
		double vIn = 2 * R / (a1 - a0), vOut = 2 * R / (b1 - b0);
		// the reference is the free speed after the junction; a slow approach is the queue the light makes
		double v = QUEUES ? vOut : Math.max(vIn, vOut);
		if (v * 3.6 < 15 || v * 3.6 > 150 || (!QUEUES && a1 - a0 > 120) || b1 - b0 > 120) return null;
		double through = QUEUES ? b0 - a0 - 2 * R / Math.max(vIn, v) * 0 : b0 - a1;
		if (QUEUES) {
			// queue included: time over [d0-3R, d0+R] against free speed over the same 4R
			return new double[] {(b0 - a0) - 4 * R / v, v * 3.6, stoppedIn(fx, i, d0, R)};
		}
		double stopped = 0;
		for (int k = at(fx, i, d0 - R); k >= 0 && k < fx.size() && fx.get(k).d <= d0 + R; k++) if (fx.get(k).spd * 3.6 < 3) { stopped = 1; break; }
		return new double[] {through - 2 * R / v, v * 3.6, stopped};
	}
	static boolean QUEUES = Boolean.getBoolean("queues");
	static double stoppedIn(List<Fix> fx, int i, double d0, double R) {
		for (int k = at(fx, i, d0 - 3 * R); k >= 0 && k < fx.size() && fx.get(k).d <= d0 + R; k++) if (fx.get(k).spd * 3.6 < 3) return 1;
		return 0;
	}
	static void report(String what, List<double[]> l) {
		if (l.isEmpty()) { System.out.println(what + ": none"); return; }
		double[] lost = new double[l.size()]; double sum = 0, cruise = 0; int stops = 0;
		for (int i = 0; i < l.size(); i++) { lost[i] = l.get(i)[0]; sum += lost[i]; cruise += l.get(i)[1]; stops += (int) l.get(i)[2]; }
		double ss = 0; int sn = 0, gn = 0; double gs = 0;
		for (double[] x : l) { if (x[2] > 0) { ss += x[0]; sn++; } else { gs += x[0]; gn++; } }
		System.out.printf(Locale.US, "  stopped: n=%d mean=%.1f s; not stopped: n=%d mean=%.1f s%n", sn, sn > 0 ? ss / sn : 0, gn, gn > 0 ? gs / gn : 0);
		Arrays.sort(lost);
		System.out.printf(Locale.US, "%s: n=%d mean=%.1f s median=%.1f s p25=%.1f p75=%.1f p90=%.1f stopped=%d%% cruise=%.0f km/h%n",
				what, l.size(), sum / l.size(), lost[l.size() / 2], lost[l.size() / 4], lost[3 * l.size() / 4], lost[9 * l.size() / 10],
				100 * stops / l.size(), cruise / l.size());
	}
}
