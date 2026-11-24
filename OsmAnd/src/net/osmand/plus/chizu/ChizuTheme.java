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
	public static final int YELLOW_SECONDARY = 0xFFC8C800; // 白い熊 地図 +067: the skill’s dim gold (was 70 % yellow)

	// 白い熊 地図 5.6.0+003: the colours that carry meaning, written once (black-yellow skill, 2026-11-23 defaults)
	public static final int SWITCH_OFF_DIM = 0xFF8C8C00;
	public static final int HEAVY_GREEN = 0xFF00A550;
	public static final int HEAVY_BLUE = 0xFF0066FF;
	public static final int LIGHT_BLUE = 0xFF00B0FF;
	public static final int ORANGE = 0xFFFF8000;
	public static final int ROSE = 0xFFFF3D7F;
	public static final int RED = 0xFFFF0000;

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
				new int[] {R.color.map_button_icon_color_dark, R.color.map_button_icon_color_light}),
		// 白い熊 地図 5.6.0+003: roles of the fork's own surfaces, tied to no OsmAnd colour resource
		SWITCH_OFF("switch_off", R.string.chizu_slot_switch_off, SWITCH_OFF_DIM, new int[0]),
		MENU_CONNECT("menu_connect", R.string.chizu_slot_menu_connect, HEAVY_GREEN, new int[0]),
		MENU_MESSAGE("menu_message", R.string.chizu_slot_menu_message, HEAVY_BLUE, new int[0]),
		MENU_CONTACT("menu_contact", R.string.chizu_slot_menu_contact, LIGHT_BLUE, new int[0]),
		MENU_SHARE("menu_share", R.string.chizu_slot_menu_share, ORANGE, new int[0]),
		MENU_BLOCK("menu_block", R.string.chizu_slot_menu_block, ROSE, new int[0]),
		MENU_DELETE("menu_delete", R.string.chizu_slot_menu_delete, RED, new int[0]),
		SECTION_BASE("section_base", R.string.chizu_slot_section_base, YELLOW, new int[0]),
		SECTION_CONTROLS("section_controls", R.string.chizu_slot_section_controls, HEAVY_GREEN, new int[0]),
		SECTION_WORDS("section_words", R.string.chizu_slot_section_words, LIGHT_BLUE, new int[0]),
		SECTION_FRAME("section_frame", R.string.chizu_slot_section_frame, HEAVY_BLUE, new int[0]),
		SECTION_REACH("section_reach", R.string.chizu_slot_section_reach, ORANGE, new int[0]),
		SECTION_WARNING("section_warning", R.string.chizu_slot_section_warning, RED, new int[0]),
		SECTION_FLASH("section_flash", R.string.chizu_slot_section_flash, ROSE, new int[0]);

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
	private static volatile int generation;

	private ChizuTheme() {
	}

	public static void init(@NonNull Context context) {
		prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
		rebuildOverrides();
	}

	@NonNull
	static SharedPreferences prefs(@NonNull Context context) {
		if (prefs == null) {
			init(context);
		}
		return prefs;
	}

	/** A foundation colour straight from the store: what was set, else the default. */
	@ColorInt
	static int slotColor(@Nullable SharedPreferences store, @NonNull Slot slot) {
		int stored = store == null ? UNSET : store.getInt(slot.key, UNSET);
		return stored != UNSET ? stored : slot.defaultColor;
	}

	/**
	 * 5.6.0+004: every colour resource a role governs, with the role's present value — set or default.
	 * Always filled: one role feeds the dark and the light name alike, so the values of {@code colors.xml}
	 * are never what shows (白い熊, 2026-11-24: always black-yellow, whichever theme is on).
	 */
	static void rebuildOverrides() {
		synchronized (OVERRIDES) {
			OVERRIDES.clear();
			if (prefs != null) {
				for (Slot slot : Slot.values()) {
					int color = slotColor(prefs, slot);
					for (int resId : slot.colorResIds) {
						OVERRIDES.put(resId, color);
					}
				}
				// content on the accent fill is derived, never picked: readable whatever the accent
				int onAccent = contrastOn(slotColor(prefs, Slot.ACCENT));
				OVERRIDES.put(R.color.chizu_on_accent_dark, onAccent);
				OVERRIDES.put(R.color.chizu_on_accent_light, onAccent);
				OVERRIDES.put(R.color.chizu_dialog_border, slotColor(prefs, Slot.DIVIDER));
				ChizuRoles.fill(prefs, OVERRIDES);
			}
			hasOverrides = OVERRIDES.size() > 0;
			generation++;
		}
	}

	/** Counts the states of the roles: {@link ChizuPalette} rebuilds its loader when this moved. */
	static int generation() {
		return generation;
	}

	/** Colour resource id → ARGB, for the loader. */
	@NonNull
	static java.util.Map<Integer, Integer> resourceColors() {
		java.util.Map<Integer, Integer> map = new java.util.HashMap<>();
		synchronized (OVERRIDES) {
			for (int i = 0; i < OVERRIDES.size(); i++) {
				map.put(OVERRIDES.keyAt(i), OVERRIDES.valueAt(i));
			}
		}
		return map;
	}

	/**
	 * Black or white, whichever reads better on {@code background}. Uses the WCAG relative
	 * luminance so a mid-tone accent flips at the point where contrast actually crosses over.
	 */
	@ColorInt
	public static int contrastOn(@ColorInt int background) {
		double luminance = 0.2126 * channel(android.graphics.Color.red(background))
				+ 0.7152 * channel(android.graphics.Color.green(background))
				+ 0.0722 * channel(android.graphics.Color.blue(background));
		// contrast against white is (1.05 / (L + 0.05)), against black (L + 0.05) / 0.05
		return luminance > 0.1791 ? BLACK : 0xFFFFFFFF;
	}

	private static double channel(int component) {
		double c = component / 255.0;
		return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
	}

	/**
	 * The colour resource for text and icons drawn on top of the accent fill.
	 */
	public static int getOnAccentColorId(boolean nightMode) {
		return nightMode ? R.color.chizu_on_accent_dark : R.color.chizu_on_accent_light;
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
		ChizuRoles.clearAll(editor); // 5.6.0+004: the item roles go back to their rules too
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
