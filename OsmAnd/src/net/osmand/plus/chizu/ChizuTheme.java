package net.osmand.plus.chizu;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.SparseIntArray;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.R;

import java.util.ArrayList;
import java.util.List;

/**
 * shiroikuma fork: the 白い熊 地図 UI theming engine.
 *
 * Each {@link Slot} is one user-settable color role. A slot maps onto the OsmAnd color
 * resources it governs; {@link net.osmand.plus.utils.ColorUtilities#getColor(Context, int, float)}
 * consults {@link #overrideFor(int)} so a stored override wins over the static resource
 * everywhere programmatic coloring goes through ColorUtilities. The static resource values
 * (black/yellow, see colors.xml) remain the defaults and cover XML-themed views.
 */
public class ChizuTheme {

	public static final String PREFS_NAME = "chizu_ui";
	public static final int UNSET = Integer.MIN_VALUE;
	public static final int BLACK = 0xFF000000;
	public static final int YELLOW = 0xFFFFFF00;
	public static final int YELLOW_SECONDARY = 0xB3FFFF00;

	private static final String RECENT_COLORS_KEY = "recent_colors";
	private static final int RECENT_COLORS_MAX = 8;

	public enum Slot {
		BACKGROUND("bg", R.string.chizu_slot_background, BLACK,
				new int[] {R.color.activity_background_color_dark, R.color.activity_background_color_light}),
		CARD_BACKGROUND("card_bg", R.string.chizu_slot_card_background, BLACK,
				new int[] {R.color.card_and_list_background_dark, R.color.card_and_list_background_light,
						R.color.list_background_color_dark, R.color.list_background_color_light}),
		TEXT("text", R.string.chizu_slot_text, YELLOW,
				new int[] {R.color.text_color_primary_dark, R.color.text_color_primary_light}),
		TEXT_SECONDARY("text_secondary", R.string.chizu_slot_text_secondary, YELLOW_SECONDARY,
				new int[] {R.color.text_color_secondary_dark, R.color.text_color_secondary_light,
						R.color.text_color_tertiary_dark, R.color.text_color_tertiary_light}),
		ACCENT("accent", R.string.chizu_slot_accent, YELLOW,
				new int[] {R.color.active_color_primary_dark, R.color.active_color_primary_light,
						R.color.icon_color_active_dark, R.color.icon_color_active_light}),
		ICON("icon", R.string.chizu_slot_icon, YELLOW,
				new int[] {R.color.icon_color_default_dark, R.color.icon_color_default_light,
						R.color.icon_color_primary_dark, R.color.icon_color_primary_light}),
		DIVIDER("divider", R.string.chizu_slot_divider, YELLOW,
				new int[] {R.color.divider_color_dark, R.color.divider_color_light,
						R.color.stroked_buttons_and_links_outline_dark, R.color.stroked_buttons_and_links_outline_light}),
		TOOLBAR_BACKGROUND("toolbar_bg", R.string.chizu_slot_toolbar_background, BLACK,
				new int[] {R.color.app_bar_main_dark, R.color.app_bar_main_light}),
		MAP_BUTTON_BACKGROUND("map_btn_bg", R.string.chizu_slot_map_button_background, BLACK,
				new int[] {R.color.map_button_background_color_dark, R.color.map_button_background_color_light}),
		MAP_BUTTON_ICON("map_btn_icon", R.string.chizu_slot_map_button_icon, YELLOW,
				new int[] {R.color.map_button_icon_color_dark, R.color.map_button_icon_color_light});

		public final String key;
		public final int labelRes;
		@ColorInt
		public final int defaultColor;
		public final int[] colorResIds;

		Slot(String key, int labelRes, @ColorInt int defaultColor, int[] colorResIds) {
			this.key = "chizu_color_" + key;
			this.labelRes = labelRes;
			this.defaultColor = defaultColor;
			this.colorResIds = colorResIds;
		}
	}

	private static SharedPreferences prefs;
	// colorResId -> override color, rebuilt on every change; empty when nothing is overridden
	private static final SparseIntArray OVERRIDES = new SparseIntArray();
	private static volatile boolean hasOverrides;

	private ChizuTheme() {
	}

	public static void init(@NonNull Context context) {
		prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
		rebuildOverrides();
	}

	@NonNull
	private static SharedPreferences prefs(@NonNull Context context) {
		if (prefs == null) {
			init(context);
		}
		return prefs;
	}

	private static void rebuildOverrides() {
		synchronized (OVERRIDES) {
			OVERRIDES.clear();
			if (prefs != null) {
				for (Slot slot : Slot.values()) {
					int stored = prefs.getInt(slot.key, UNSET);
					if (stored != UNSET) {
						for (int resId : slot.colorResIds) {
							OVERRIDES.put(resId, stored);
						}
					}
				}
			}
			hasOverrides = OVERRIDES.size() > 0;
		}
	}

	/**
	 * The runtime override for a color resource, or null when the static resource applies.
	 * Hot path — called from ColorUtilities.getColor.
	 */
	@Nullable
	public static Integer overrideFor(int colorResId) {
		if (!hasOverrides) {
			return null;
		}
		synchronized (OVERRIDES) {
			int index = OVERRIDES.indexOfKey(colorResId);
			return index >= 0 ? OVERRIDES.valueAt(index) : null;
		}
	}

	@ColorInt
	public static int getColor(@NonNull Context context, @NonNull Slot slot) {
		int stored = prefs(context).getInt(slot.key, UNSET);
		return stored != UNSET ? stored : slot.defaultColor;
	}

	public static boolean isOverridden(@NonNull Context context, @NonNull Slot slot) {
		return prefs(context).getInt(slot.key, UNSET) != UNSET;
	}

	public static void setColor(@NonNull Context context, @NonNull Slot slot, @ColorInt int color) {
		prefs(context).edit().putInt(slot.key, color).apply();
		rebuildOverrides();
	}

	public static void clearColor(@NonNull Context context, @NonNull Slot slot) {
		prefs(context).edit().remove(slot.key).apply();
		rebuildOverrides();
	}

	public static void clearAll(@NonNull Context context) {
		SharedPreferences.Editor editor = prefs(context).edit();
		for (Slot slot : Slot.values()) {
			editor.remove(slot.key);
		}
		editor.apply();
		rebuildOverrides();
	}

	@NonNull
	public static List<Integer> getRecentColors(@NonNull Context context) {
		List<Integer> result = new ArrayList<>();
		String stored = prefs(context).getString(RECENT_COLORS_KEY, "");
		if (stored != null && !stored.isEmpty()) {
			for (String part : stored.split(",")) {
				try {
					result.add((int) Long.parseLong(part.trim()));
				} catch (NumberFormatException ignored) {
				}
			}
		}
		return result;
	}

	public static void addRecentColor(@NonNull Context context, @ColorInt int color) {
		List<Integer> recent = getRecentColors(context);
		recent.remove(Integer.valueOf(color));
		recent.add(0, color);
		while (recent.size() > RECENT_COLORS_MAX) {
			recent.remove(recent.size() - 1);
		}
		StringBuilder builder = new StringBuilder();
		for (int c : recent) {
			if (builder.length() > 0) {
				builder.append(",");
			}
			builder.append(c);
		}
		prefs(context).edit().putString(RECENT_COLORS_KEY, builder.toString()).apply();
	}
}
