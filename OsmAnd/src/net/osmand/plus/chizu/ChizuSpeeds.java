package net.osmand.plus.chizu;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.router.GeneralRouter;
import net.osmand.router.GeneralRouter.RouteAttributeContext;
import net.osmand.router.GeneralRouter.RouteAttributeEvalRule;
import net.osmand.router.GeneralRouter.RouteDataObjectAttribute;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 白い熊 地図: 白い熊's own driving speeds, one per road type and posted limit, in place of OsmAnd's
 * “drive exactly the limit”. Each row is a road type at one limit (or with no limit posted, or a
 * tunnel at one limit); 0 means OsmAnd's own speed. A limit with no row of its own is placed
 * between the rows of its type proportionally, or takes the plus of the nearest one.
 * <p>
 * The speeds go into the router of each car calculation as rules ahead of the profile's own, so the
 * route choice and the times use them. OsmAnd's fast precomputed routing knows only its standard
 * speeds, so a calculation with these speeds uses the full search.
 */
public final class ChizuSpeeds {

	private static final String PREFS = "chizu_speeds";
	private static final String ENABLED = "enabled";
	private static final int[] LIMITS = {5, 10, 15, 20, 25, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130, 140, 150};

	public static final class Row {
		public final int no;
		public final String group;
		@NonNull
		public final String[] highways;
		public final boolean tunnel;
		@NonNull
		public final int[] limits; // empty: no limit posted
		public final int osmand;
		public final int preset;

		Row(int no, @NonNull String group, @NonNull String[] highways, boolean tunnel, @NonNull int[] limits,
		    int osmand, int preset) {
			this.no = no;
			this.group = group;
			this.highways = highways;
			this.tunnel = tunnel;
			this.limits = limits;
			this.osmand = osmand;
			this.preset = preset;
		}

		@NonNull
		public String label() {
			String l;
			if (limits.length == 0) {
				l = "No limit posted";
			} else if (limits.length == 1) {
				l = "Limit " + limits[0];
			} else {
				l = "Limit " + limits[0] + "–" + limits[limits.length - 1];
			}
			return no + ". " + (tunnel ? "Tunnel, " + l.toLowerCase(java.util.Locale.US) : l);
		}
	}

	private static final String[] MOTORWAY = {"motorway"};
	private static final String[] TRUNK = {"trunk"};
	private static final String[] PRIMARY = {"primary"};
	private static final String[] SECONDARY = {"secondary"};
	private static final String[] TERTIARY = {"tertiary"};
	private static final String[] UNCLASSIFIED = {"unclassified"};
	private static final String[] RESIDENTIAL = {"residential"};
	private static final String[] LIVING = {"living_street"};
	private static final String[] SERVICE = {"service"};
	private static final String[] MOTORWAY_LINK = {"motorway_link"};
	private static final String[] TRUNK_LINK = {"trunk_link"};
	private static final String[] OTHER_LINKS = {"primary_link", "secondary_link", "tertiary_link"};
	private static final int[] NONE = {};

	public static final List<Row> ROWS = Collections.unmodifiableList(Arrays.asList(
			new Row(1, "Motorway", MOTORWAY, false, new int[] {130}, 130, 160),
			new Row(2, "Motorway", MOTORWAY, false, new int[] {110}, 110, 140),
			new Row(3, "Motorway", MOTORWAY, false, new int[] {100}, 100, 130),
			new Row(4, "Motorway", MOTORWAY, false, new int[] {80}, 80, 100),
			new Row(5, "Motorway", MOTORWAY, true, new int[] {80}, 80, 90),
			new Row(6, "Motorway", MOTORWAY, false, NONE, 110, 0),
			new Row(7, "Trunk", TRUNK, false, new int[] {110}, 110, 130),
			new Row(8, "Trunk", TRUNK, false, new int[] {100}, 100, 130),
			new Row(9, "Trunk", TRUNK, false, new int[] {90}, 90, 110),
			new Row(10, "Trunk", TRUNK, false, new int[] {80}, 80, 110),
			new Row(11, "Trunk", TRUNK, false, new int[] {70}, 70, 110),
			new Row(12, "Trunk", TRUNK, true, new int[] {70}, 70, 90),
			new Row(13, "Trunk", TRUNK, false, new int[] {60}, 60, 80),
			new Row(14, "Trunk", TRUNK, false, new int[] {50}, 50, 70),
			new Row(15, "Primary", PRIMARY, false, new int[] {90}, 90, 110),
			new Row(16, "Primary", PRIMARY, false, new int[] {80}, 80, 100),
			new Row(17, "Primary", PRIMARY, false, new int[] {70}, 70, 100),
			new Row(18, "Primary", PRIMARY, false, new int[] {50}, 50, 90),
			new Row(19, "Primary", PRIMARY, false, NONE, 65, 90),
			new Row(20, "Secondary", SECONDARY, false, new int[] {90}, 90, 0),
			new Row(21, "Secondary", SECONDARY, false, new int[] {70}, 70, 0),
			new Row(22, "Secondary", SECONDARY, false, new int[] {50}, 50, 0),
			new Row(23, "Secondary", SECONDARY, false, new int[] {40}, 40, 0),
			new Row(24, "Secondary", SECONDARY, false, new int[] {30}, 30, 50),
			new Row(25, "Secondary", SECONDARY, false, NONE, 60, 0),
			new Row(26, "Tertiary", TERTIARY, false, new int[] {90}, 90, 0),
			new Row(27, "Tertiary", TERTIARY, false, new int[] {70}, 70, 0),
			new Row(28, "Tertiary", TERTIARY, false, new int[] {50}, 50, 0),
			new Row(29, "Tertiary", TERTIARY, false, new int[] {40}, 40, 0),
			new Row(30, "Tertiary", TERTIARY, false, new int[] {30}, 30, 50),
			new Row(31, "Tertiary", TERTIARY, false, NONE, 45, 0),
			new Row(32, "Unclassified", UNCLASSIFIED, false, new int[] {90}, 90, 0),
			new Row(33, "Unclassified", UNCLASSIFIED, false, new int[] {70}, 70, 0),
			new Row(34, "Unclassified", UNCLASSIFIED, false, new int[] {50}, 50, 0),
			new Row(35, "Unclassified", UNCLASSIFIED, false, new int[] {30}, 30, 0),
			new Row(36, "Unclassified", UNCLASSIFIED, false, NONE, 35, 0),
			new Row(37, "Residential", RESIDENTIAL, false, new int[] {50}, 50, 90),
			new Row(38, "Residential", RESIDENTIAL, false, new int[] {40}, 40, 70),
			new Row(39, "Residential", RESIDENTIAL, false, new int[] {30}, 30, 60),
			new Row(40, "Residential", RESIDENTIAL, false, new int[] {20}, 20, 0),
			new Row(41, "Residential", RESIDENTIAL, false, NONE, 35, 0),
			new Row(42, "Living street and service road", LIVING, false, new int[] {20}, 20, 0),
			new Row(43, "Living street and service road", LIVING, false, NONE, 25, 0),
			new Row(44, "Living street and service road", SERVICE, false, new int[] {50}, 50, 0),
			new Row(45, "Living street and service road", SERVICE, false, new int[] {30}, 30, 0),
			new Row(46, "Living street and service road", SERVICE, false, new int[] {20}, 20, 0),
			new Row(47, "Living street and service road", SERVICE, false, NONE, 30, 0),
			new Row(48, "Ramps", MOTORWAY_LINK, false, new int[] {130}, 100, 0),
			new Row(49, "Ramps", MOTORWAY_LINK, false, new int[] {80, 90}, 100, 0),
			new Row(50, "Ramps", MOTORWAY_LINK, false, NONE, 100, 0),
			new Row(51, "Ramps", TRUNK_LINK, false, new int[] {80}, 90, 0),
			new Row(52, "Ramps", TRUNK_LINK, false, new int[] {50}, 90, 0),
			new Row(53, "Ramps", TRUNK_LINK, false, NONE, 90, 0),
			new Row(54, "Ramps", OTHER_LINKS, false, new int[] {50}, 50, 0),
			new Row(55, "Ramps", OTHER_LINKS, false, NONE, 0, 0)
	));

	// OsmAnd's own speed per type when no limit is posted (routing.xml), and for ramps whatever the limit
	private static final Map<String, Integer> STOCK_NO_LIMIT = new LinkedHashMap<>();
	private static final Map<String, Integer> STOCK_FIXED = new LinkedHashMap<>();

	static {
		STOCK_NO_LIMIT.put("motorway", 110);
		STOCK_NO_LIMIT.put("trunk", 100);
		STOCK_NO_LIMIT.put("primary", 65);
		STOCK_NO_LIMIT.put("primary_link", 65);
		STOCK_NO_LIMIT.put("secondary", 60);
		STOCK_NO_LIMIT.put("secondary_link", 60);
		STOCK_NO_LIMIT.put("tertiary", 45);
		STOCK_NO_LIMIT.put("tertiary_link", 45);
		STOCK_NO_LIMIT.put("unclassified", 35);
		STOCK_NO_LIMIT.put("road", 35);
		STOCK_NO_LIMIT.put("residential", 35);
		STOCK_NO_LIMIT.put("service", 30);
		STOCK_NO_LIMIT.put("living_street", 25);
		STOCK_NO_LIMIT.put("track", 15);
		STOCK_FIXED.put("motorway_link", 100);
		STOCK_FIXED.put("trunk_link", 90);
	}

	private ChizuSpeeds() {
	}

	private static SharedPreferences prefs(@NonNull Context context) {
		return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}

	public static boolean isEnabled(@NonNull Context context) {
		return prefs(context).getBoolean(ENABLED, true);
	}

	public static void setEnabled(@NonNull Context context, boolean enabled) {
		prefs(context).edit().putBoolean(ENABLED, enabled).apply();
	}

	/**
	 * The slowest speed a road counts with, km/h. It stands in for the low end of the stock “Speed
	 * range”, which by default lifted every road below 50 km/h to 50, own rows included.
	 */
	public static int getSlowest(@NonNull Context context) {
		return prefs(context).getInt("slowest", 5);
	}

	public static void setSlowest(@NonNull Context context, int kmh) {
		prefs(context).edit().putInt("slowest", kmh).apply();
	}

	/** The speed of a road whose type the profile has no speed for, km/h (the stock “Default speed”). */
	public static int getUnknown(@NonNull Context context) {
		return prefs(context).getInt("unknown", 50);
	}

	public static void setUnknown(@NonNull Context context, int kmh) {
		prefs(context).edit().putInt("unknown", kmh).apply();
	}

	/** Whether the arrival time follows the recent moving speed (the stock “Adapt to my speed”). */
	public static boolean isEtaFollowsSpeed(@NonNull Context context) {
		return prefs(context).getBoolean("eta_follows_speed", false);
	}

	public static void setEtaFollowsSpeed(@NonNull Context context, boolean follows) {
		prefs(context).edit().putBoolean("eta_follows_speed", follows).apply();
	}

	/** The span of the recent moving speed, minutes. */
	public static int getEtaMinutes(@NonNull Context context) {
		return prefs(context).getInt("eta_minutes", 15);
	}

	public static void setEtaMinutes(@NonNull Context context, int minutes) {
		prefs(context).edit().putInt("eta_minutes", minutes).apply();
	}

	/** The speed set for a row; 0 means OsmAnd's own. */
	public static int get(@NonNull Context context, @NonNull Row row) {
		return prefs(context).getInt("row_" + row.no, row.preset);
	}

	public static void set(@NonNull Context context, @NonNull Row row, int kmh) {
		prefs(context).edit().putInt("row_" + row.no, kmh).apply();
	}

	/** True when any row differs from OsmAnd, so that calculations use these speeds. */
	public static boolean isActive(@NonNull Context context) {
		if (!isEnabled(context)) {
			return false;
		}
		for (Row r : ROWS) {
			if (get(context, r) > 0) {
				return true;
			}
		}
		return false;
	}

	/** True when these settings, not the profile's Travel speed screen, give a car profile its speeds. */
	public static boolean governsTravelSpeed(@NonNull Context context, @Nullable net.osmand.plus.settings.backend.ApplicationMode mode) {
		return mode != null && mode.isDerivedRoutingFrom(net.osmand.plus.settings.backend.ApplicationMode.CAR)
				&& isActive(context);
	}

	/** OsmAnd's own speed on such a road, km/h. */
	public static int stockSpeed(@Nullable String highway, int limit) {
		if (highway == null) {
			return 0;
		}
		Integer fixed = STOCK_FIXED.get(highway);
		if (fixed != null) {
			return fixed;
		}
		if (limit > 0) {
			return limit;
		}
		Integer v = STOCK_NO_LIMIT.get(highway);
		return v == null ? 0 : v;
	}

	/** The speed these settings give such a road, km/h (OsmAnd's own where nothing is set). */
	public static int speedFor(@NonNull Context context, @Nullable String highway, boolean tunnel, int limit) {
		int stock = stockSpeed(highway, limit);
		if (highway == null || !isEnabled(context)) {
			return stock;
		}
		if (limit <= 0) {
			for (Row r : ROWS) {
				if (r.limits.length == 0 && contains(r.highways, highway)) {
					int v = get(context, r);
					return v > 0 ? v : stock;
				}
			}
			return stock;
		}
		Integer s = interpolated(context, highway, tunnel, limit);
		if (s == null && tunnel) {
			s = interpolated(context, highway, false, limit);
		}
		return s == null ? stock : s;
	}

	/** The speed at {@code limit} from the rows of the type, or null when none of them is set. */
	@Nullable
	private static Integer interpolated(@NonNull Context context, @NonNull String highway, boolean tunnel, int limit) {
		List<int[]> anchors = new ArrayList<>(); // {limit, speed}
		boolean anySet = false;
		for (Row r : ROWS) {
			if (r.tunnel != tunnel || r.limits.length == 0 || !contains(r.highways, highway)) {
				continue;
			}
			int v = get(context, r);
			anySet |= v > 0;
			for (int l : r.limits) {
				anchors.add(new int[] {l, v > 0 ? v : stockSpeed(highway, l)});
			}
		}
		if (!anySet) {
			return null;
		}
		Collections.sort(anchors, (a, b) -> Integer.compare(a[0], b[0]));
		int[] lo = anchors.get(0);
		int[] hi = anchors.get(anchors.size() - 1);
		if (limit <= lo[0]) {
			return Math.max(5, limit + lo[1] - lo[0]);
		}
		if (limit >= hi[0]) {
			return limit + hi[1] - hi[0];
		}
		for (int i = 1; i < anchors.size(); i++) {
			int[] a = anchors.get(i - 1);
			int[] b = anchors.get(i);
			if (limit <= b[0]) {
				if (b[0] == a[0]) {
					return b[1];
				}
				return Math.round(a[1] + (b[1] - a[1]) * (limit - a[0]) / (float) (b[0] - a[0]));
			}
		}
		return hi[1];
	}

	/** The fastest speed any row asks for, km/h, so the profile's own ceiling can be raised to it. */
	public static int maxSpeed(@NonNull Context context) {
		int max = 0;
		for (Row r : ROWS) {
			max = Math.max(max, get(context, r));
		}
		for (Row r : ROWS) {
			for (String h : r.highways) {
				for (int l : LIMITS) {
					max = Math.max(max, speedFor(context, h, r.tunnel, l));
				}
			}
		}
		return max;
	}

	/**
	 * Puts these speeds into the router of one calculation: per type and limit ahead of all of the
	 * profile's speed rules, and the no-limit speeds just after its “take the posted limit” rules.
	 */
	public static void apply(@NonNull Context context, @NonNull GeneralRouter router) {
		RouteAttributeContext speeds = router.getObjContext(RouteDataObjectAttribute.ROAD_SPEED);
		List<String> highways = new ArrayList<>();
		for (Row r : ROWS) {
			for (String h : r.highways) {
				if (!highways.contains(h)) {
					highways.add(h);
				}
			}
		}
		// no-limit rows first, as their place depends on the profile's own rules only
		int afterLimits = speeds.chizuIndexAfter("$maxspeed");
		for (String h : highways) {
			int v = speedFor(context, h, false, 0);
			if (v > 0 && v != stockSpeed(h, 0)) {
				RouteAttributeEvalRule rule = speeds.chizuInsertRule(afterLimits++, String.valueOf(v), "speed");
				rule.registerAndTagValueCondition("highway", h, false);
			}
		}
		int at = 0;
		for (boolean tunnel : new boolean[] {true, false}) {
			for (String h : highways) {
				for (int l : LIMITS) {
					int v = speedFor(context, h, tunnel, l);
					if (tunnel && v == speedFor(context, h, false, l)) {
						continue; // a tunnel like any other road of the type
					}
					if (v <= 0 || v == stockSpeed(h, l)) {
						continue;
					}
					RouteAttributeEvalRule rule = speeds.chizuInsertRule(at++, String.valueOf(v), "speed");
					rule.registerAndTagValueCondition("highway", h, false);
					rule.registerAndTagValueCondition("maxspeed", String.valueOf(l), false);
					if (tunnel) {
						rule.registerAndTagValueCondition("tunnel", "yes", false);
					}
				}
			}
		}
	}

	private static boolean contains(@NonNull String[] a, @NonNull String s) {
		for (String x : a) {
			if (x.equals(s)) {
				return true;
			}
		}
		return false;
	}
}
