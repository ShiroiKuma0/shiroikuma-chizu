package net.osmand.plus.chizu;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;

import net.osmand.plus.R;

import java.util.HashMap;
import java.util.Map;

/**
 * 白い熊 地図 5.6.0+003: the icons of the UI settings page (black-yellow skill, “Row icons” and “Section
 * icons”). Every row, every section heading and every chip of the index carries an icon at the start of
 * its label: coloured by what the row does (the menu icon roles) or by what the section is (the seven
 * section colours), as tall as 1.25 × the label's text and never under 18 dp on a row, and centred on the
 * label's first line.
 * <p>
 * Which row gets which icon is one table here, keyed by the row's title string, so the page's row
 * builders stay as they were.
 */
public final class ChizuRowIcons {

	/** What a row does: the colour of its icon. Each is a settable colour of {@link ChizuTheme}. */
	public enum Role {
		VIEW(ChizuTheme.Slot.ACCENT),
		CONNECT(ChizuTheme.Slot.MENU_CONNECT),
		MESSAGE(ChizuTheme.Slot.MENU_MESSAGE),
		CONTACT(ChizuTheme.Slot.MENU_CONTACT),
		SHARE(ChizuTheme.Slot.MENU_SHARE),
		BLOCK(ChizuTheme.Slot.MENU_BLOCK),
		DELETE(ChizuTheme.Slot.MENU_DELETE);

		final ChizuTheme.Slot slot;

		Role(ChizuTheme.Slot slot) {
			this.slot = slot;
		}

		@ColorInt
		public int color(@NonNull Context c) {
			return ChizuTheme.getColor(c, slot);
		}
	}

	/** One row's icon and role. */
	public static final class Spec {
		public final int icon;
		public final Role role;

		Spec(int icon, Role role) {
			this.icon = icon;
			this.role = role;
		}
	}

	public static final Spec SETTING = new Spec(R.drawable.ic_action_settings, Role.VIEW);
	public static final Spec COLOR = new Spec(R.drawable.ic_action_appearance, Role.VIEW);
	public static final Spec SPEED = new Spec(R.drawable.ic_action_speed, Role.VIEW);
	public static final Spec LANGUAGE = new Spec(R.drawable.ic_action_map_language, Role.MESSAGE);
	public static final Spec DIRECTORY = new Spec(R.drawable.ic_action_folder, Role.SHARE);

	private static final Map<Integer, Spec> BY_TITLE = new HashMap<>();

	private static void put(int titleRes, int icon, Role role) {
		BY_TITLE.put(titleRes, new Spec(icon, role));
	}

	static {
		put(R.string.chizu_app_settings, R.drawable.ic_action_settings, Role.VIEW);
		// App language
		put(R.string.chizu_pills_japanese, R.drawable.ic_action_text_dark, Role.MESSAGE);
		// Export / Import
		put(R.string.chizu_exim_open, R.drawable.ic_action_export, Role.SHARE);
		put(R.string.chizu_automation, R.drawable.ic_action_save_to_file, Role.SHARE);
		put(R.string.chizu_automation_require_token, R.drawable.ic_action_lock, Role.BLOCK);
		put(R.string.chizu_automation_token, R.drawable.ic_action_privacy_and_security, Role.BLOCK);
		put(R.string.chizu_automation_regenerate, R.drawable.ic_action_refresh_dark, Role.CONNECT);
		// Text & fonts
		put(R.string.chizu_font_family, R.drawable.ic_action_text_dark, Role.VIEW);
		put(R.string.chizu_font_weight, R.drawable.ic_action_text_preview, Role.VIEW);
		put(R.string.chizu_map_text_size, R.drawable.ic_action_map_text_size, Role.VIEW);
		// Map buttons
		put(R.string.chizu_map_button_size, R.drawable.ic_action_item_size_l, Role.VIEW);
		put(R.string.chizu_map_button_roundness, R.drawable.ic_action_button_default, Role.VIEW);
		put(R.string.chizu_map_button_opacity, R.drawable.ic_action_opacity, Role.VIEW);
		// Car screen
		put(R.string.chizu_car_tilt, R.drawable.ic_action_3d, Role.VIEW);
		// Wi-Fi locator
		put(R.string.chizu_wifi_on, R.drawable.ic_action_signal, Role.CONNECT);
		put(R.string.chizu_wifi_in_car, R.drawable.ic_action_car_dark, Role.CONNECT);
		put(R.string.chizu_wifi_coverage, R.drawable.ic_action_location_radius, Role.VIEW);
		put(R.string.chizu_wifi_forget_all, R.drawable.ic_action_delete_dark, Role.DELETE);
		// Satellite receiver
		put(R.string.chizu_gnss_finish, R.drawable.ic_action_gps_info, Role.CONNECT);
		put(R.string.chizu_gnss_fresh, R.drawable.ic_action_time_span, Role.CONNECT);
		put(R.string.chizu_gnss_open_permissions, R.drawable.ic_action_privacy_and_security, Role.VIEW);
		put(R.string.chizu_wifi_scan_limit_on, R.drawable.ic_action_android, Role.VIEW);
		put(R.string.chizu_wifi_scan_limit_off, R.drawable.ic_action_android, Role.VIEW);
		// Traffic
		put(R.string.chizu_traffic_enabled, R.drawable.ic_action_car_running_time, Role.CONNECT);
		put(R.string.chizu_traffic_chooser, R.drawable.ic_action_gdirections_dark, Role.VIEW);
		put(R.string.chizu_traffic_interval, R.drawable.ic_action_time_span, Role.VIEW);
		put(R.string.chizu_traffic_min_saving, R.drawable.ic_action_time, Role.VIEW);
		put(R.string.chizu_traffic_long_yellow, R.drawable.ic_action_ruler, Role.VIEW);
		put(R.string.chizu_traffic_chosen_delay, R.drawable.ic_action_timer, Role.VIEW);
		put(R.string.chizu_shimmer, R.drawable.ic_action_stars, Role.VIEW);
		put(R.string.chizu_shimmer_loop, R.drawable.ic_action_time_span, Role.VIEW);
		put(R.string.chizu_shimmer_band, R.drawable.ic_action_sun, Role.VIEW);
		put(R.string.chizu_shimmer_length, R.drawable.ic_action_ruler, Role.VIEW);
		put(R.string.chizu_shimmer_sparkles, R.drawable.ic_action_stars, Role.VIEW);
		put(R.string.chizu_shimmer_sparkle_size, R.drawable.ic_action_item_size_s, Role.VIEW);
		// My driving speeds
		put(R.string.chizu_speeds_enabled, R.drawable.ic_action_speed, Role.VIEW);
		put(R.string.chizu_speeds_slowest, R.drawable.ic_action_max_speed, Role.VIEW);
		put(R.string.chizu_speeds_unknown, R.drawable.ic_action_speed, Role.VIEW);
		put(R.string.chizu_speeds_traffic_light, R.drawable.ic_action_timer, Role.VIEW);
		put(R.string.chizu_speeds_turn, R.drawable.ic_action_next_turn, Role.VIEW);
		put(R.string.chizu_speeds_eta_follows, R.drawable.ic_action_time_end, Role.VIEW);
		put(R.string.chizu_speeds_eta_minutes, R.drawable.ic_action_time_span, Role.VIEW);
		// Voice
		put(R.string.chizu_voice_prerender, R.drawable.ic_action_volume_up, Role.MESSAGE);
		// Diagnostics
		put(R.string.chizu_diag_location, R.drawable.ic_action_device_location, Role.VIEW);
		put(R.string.chizu_diag_gnss, R.drawable.ic_action_gps_info, Role.VIEW);
		put(R.string.chizu_diag_off_route, R.drawable.ic_action_snap_to_road, Role.VIEW);
		put(R.string.chizu_diag_roads, R.drawable.ic_action_road_works_dark, Role.VIEW);
		put(R.string.chizu_diag_routing, R.drawable.ic_action_gdirections_dark, Role.VIEW);
		put(R.string.chizu_diag_traffic, R.drawable.ic_action_car_running_time, Role.VIEW);
		put(R.string.chizu_diag_voice, R.drawable.ic_action_volume_up, Role.VIEW);
		put(R.string.chizu_diag_map, R.drawable.ic_action_compass, Role.VIEW);
		put(R.string.chizu_diag_car, R.drawable.ic_action_car_dark, Role.VIEW);
		put(R.string.chizu_diag_ui, R.drawable.ic_action_touch_screen_unlock, Role.VIEW);
		put(R.string.chizu_diag_perf, R.drawable.ic_action_battery, Role.VIEW);
		put(R.string.chizu_diag_keep, R.drawable.ic_action_history, Role.VIEW);
		put(R.string.chizu_diag_export, R.drawable.ic_action_export, Role.SHARE);
		put(R.string.chizu_diag_share, R.drawable.ic_action_gshare_dark, Role.SHARE);
		put(R.string.chizu_diag_purge, R.drawable.ic_action_delete_dark, Role.DELETE);
		// Maintenance
		put(R.string.chizu_reset_all, R.drawable.ic_action_reset, Role.CONNECT);
		put(R.string.chizu_reset_colors, R.drawable.ic_action_appearance, Role.CONNECT);
		put(R.string.chizu_reset_order, R.drawable.ic_action_sort, Role.CONNECT);
	}

	private ChizuRowIcons() {
	}

	/** The table by the titles as the page shows them now, in the page's language. */
	@NonNull
	public static Map<String, Spec> byTitle(@NonNull Context c) {
		Map<String, Spec> map = new HashMap<>();
		for (Map.Entry<Integer, Spec> e : BY_TITLE.entrySet()) {
			map.put(c.getString(e.getKey()), e.getValue());
		}
		return map;
	}

	/** The icon's edge for a label of this text size. */
	private static int sizeFor(@NonNull TextView label, boolean floor) {
		float d = label.getResources().getDisplayMetrics().density;
		int size = Math.round(1.25f * label.getTextSize());
		return floor ? Math.max(size, Math.round(18 * d)) : size;
	}

	/**
	 * Puts {@code icon} at the start of {@code label}, in {@code color}. The size is read off the label
	 * again at every layout, so a text size set later is followed.
	 *
	 * @return the gutter: how far the label's text moved, for whatever sits under it.
	 */
	public static int set(@NonNull TextView label, int icon, @ColorInt int color, boolean floor) {
		Drawable d = AppCompatResources.getDrawable(label.getContext(), icon);
		if (d == null) {
			return 0;
		}
		d = d.mutate();
		d.setTint(color);
		FirstLine wrapped = new FirstLine(d, label);
		int size = apply(label, wrapped, floor);
		label.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
			if (wrapped.getBounds().width() != sizeFor(label, floor)) {
				label.post(() -> apply(label, wrapped, floor));
			}
		});
		return size + size / 2;
	}

	private static int apply(@NonNull TextView label, @NonNull Drawable wrapped, boolean floor) {
		int size = sizeFor(label, floor);
		wrapped.setBounds(0, 0, size, size);
		label.setCompoundDrawablesRelative(wrapped, null, null, null);
		label.setCompoundDrawablePadding(size / 2);
		return size;
	}

	/** Draws the icon centred on the label's first line instead of on the whole, possibly wrapped, label. */
	private static final class FirstLine extends Drawable {
		private final Drawable inner;
		private final TextView label;

		FirstLine(@NonNull Drawable inner, @NonNull TextView label) {
			this.inner = inner;
			this.label = label;
		}

		@Override
		public void draw(@NonNull Canvas canvas) {
			float dy = 0;
			Layout layout = label.getLayout();
			if (layout != null && layout.getLineCount() > 1) {
				int content = label.getHeight() - label.getCompoundPaddingTop() - label.getCompoundPaddingBottom();
				dy = (layout.getLineBottom(0) - layout.getLineTop(0)) / 2f - content / 2f;
			}
			inner.setBounds(getBounds());
			canvas.save();
			canvas.translate(0, dy);
			inner.draw(canvas);
			canvas.restore();
		}

		@Override
		public int getIntrinsicWidth() {
			return getBounds().width();
		}

		@Override
		public int getIntrinsicHeight() {
			return getBounds().height();
		}

		@Override
		public void setAlpha(int alpha) {
			inner.setAlpha(alpha);
		}

		@Override
		public void setColorFilter(@Nullable ColorFilter colorFilter) {
			inner.setColorFilter(colorFilter);
		}

		@Override
		public int getOpacity() {
			return PixelFormat.TRANSLUCENT;
		}
	}
}
