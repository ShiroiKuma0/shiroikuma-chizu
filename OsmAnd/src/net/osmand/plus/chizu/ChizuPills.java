package net.osmand.plus.chizu;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

/**
 * 白い熊 地図 +067: the black-yellow pill (global black-yellow-theme skill) — fully round, never filled;
 * the main action of a block has a 3 dp frame and bold text, every other one 1.25 dp and normal text.
 */
public final class ChizuPills {

	public static final float MAIN_DP = 3f;
	public static final float REGULAR_DP = 1.25f;

	private static final String KEY_JAPANESE = "overlay_pills_japanese";
	private static volatile Boolean japanese;
	private static android.content.res.Resources japaneseResources;

	private ChizuPills() {
	}

	/** +106: the pills drawn over the map are worded in Japanese, whatever language 地図 and the phone run in. Off by default. */
	public static boolean isJapanese(@NonNull Context c) {
		Boolean on = japanese;
		if (on == null) {
			on = c.getApplicationContext().getSharedPreferences(ChizuTheme.PREFS_NAME, Context.MODE_PRIVATE)
					.getBoolean(KEY_JAPANESE, false);
			japanese = on;
		}
		return on;
	}

	public static void setJapanese(@NonNull net.osmand.plus.OsmandApplication app, boolean on) {
		app.getSharedPreferences(ChizuTheme.PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_JAPANESE, on).apply();
		japanese = on;
		ChizuDriveLog.log("map pills in Japanese " + (on ? "on" : "off"));
		app.getOsmandMap().refreshMap();
	}

	/** The preferences were replaced under us (a restore): read the switch again. */
	public static void forgetJapanese() {
		japanese = null;
	}

	/**
	 * +106: the wording of a pill drawn over the map (the position pill on the phone and on the car screen,
	 * and any later one) — in 地図's language, or in Japanese when the switch is on. Every such pill takes its
	 * text from here, never from {@code app.getString}.
	 */
	@NonNull
	public static String text(@NonNull net.osmand.plus.OsmandApplication app, int id, Object... args) {
		if (!isJapanese(app)) {
			return app.getString(id, args);
		}
		android.content.res.Resources r = japaneseResources;
		if (r == null) {
			r = app.getLocaleHelper().getLocalizedResources(java.util.Locale.JAPANESE);
			japaneseResources = r;
		}
		return r.getString(id, args);
	}

	/** The pill's background: the page's own background inside, the accent frame, an accent ripple. */
	@NonNull
	public static Drawable drawable(@NonNull Context c, @ColorInt int accent, @ColorInt int inside, boolean main) {
		float density = c.getResources().getDisplayMetrics().density;
		GradientDrawable pill = new GradientDrawable();
		pill.setColor(inside);
		pill.setCornerRadius(100 * density);
		pill.setStroke(Math.max(1, Math.round((main ? MAIN_DP : REGULAR_DP) * density)), accent);
		return new RippleDrawable(ColorStateList.valueOf((accent & 0x00FFFFFF) | 0x4D000000), pill, null);
	}

	/** The dialog's buttons as pills: the positive one is the main action, the others regular. */
	public static void styleDialog(@NonNull AlertDialog dialog) {
		styleButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE), true);
		styleButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), false);
		styleButton(dialog.getButton(AlertDialog.BUTTON_NEUTRAL), false);
	}

	public static void styleButton(@Nullable Button button, boolean main) {
		styleButton(button, main, false);
	}

	/** +107: true when a button's label says that it deletes — such a button lends the warning red to frame and label. */
	public static boolean deletes(@NonNull Context c, @Nullable CharSequence label) {
		if (label == null || label.length() == 0) {
			return false;
		}
		String text = label.toString().trim();
		for (int id : new int[] {net.osmand.plus.R.string.shared_string_delete, net.osmand.plus.R.string.shared_string_delete_all,
				net.osmand.plus.R.string.shared_string_remove, net.osmand.plus.R.string.shared_string_uninstall}) {
			if (text.equalsIgnoreCase(c.getString(id))) {
				return true;
			}
		}
		return false;
	}

	public static void styleButton(@Nullable Button button, boolean main, boolean destructive) {
		if (button == null || button.getVisibility() != android.view.View.VISIBLE) {
			return;
		}
		Context c = button.getContext();
		float density = c.getResources().getDisplayMetrics().density;
		int accent = destructive ? ChizuMenuPills.warning(c) : ChizuTheme.getColor(c, ChizuTheme.Slot.ACCENT);
		int inside = ChizuTheme.getColor(c, ChizuTheme.Slot.CARD_BACKGROUND);
		// +107: a Material button tints whatever background it is given with its own (see-through) tint
		if (button instanceof androidx.core.view.TintableBackgroundView) {
			((androidx.core.view.TintableBackgroundView) button).setSupportBackgroundTintList(null);
		}
		button.setBackground(drawable(c, accent, inside, main));
		button.setTextColor(accent);
		button.setTypeface(Typeface.create(button.getTypeface(), main ? Typeface.BOLD : Typeface.NORMAL));
		button.setAllCaps(false);
		int h = Math.round(20 * density);
		int v = Math.round(6 * density);
		button.setPadding(h, v, h, v);
		button.setMinHeight(0);
		button.setMinimumHeight(0);
		ViewGroup.LayoutParams params = button.getLayoutParams();
		if (params instanceof ViewGroup.MarginLayoutParams) {
			((ViewGroup.MarginLayoutParams) params).setMarginStart(Math.round(8 * density));
			button.setLayoutParams(params);
		}
	}

	/**
	 * +107: upstream's own button ({@code DialogButton}, on every bottom sheet and card) as a pill. Its primary
	 * kinds were filled slabs and its tertiary kind bare text; now the primary is the 3 dp bold pill, every
	 * other the 1.25 dp one, and a harmful kind lends the warning red to frame and label. Never filled.
	 */
	public static void styleDialogButton(@NonNull android.view.View button,
			@NonNull net.osmand.plus.widgets.dialogbutton.DialogButtonType type) {
		android.view.View body = button.findViewById(net.osmand.plus.R.id.button_body);
		android.widget.TextView title = button.findViewById(net.osmand.plus.R.id.button_text);
		if (body == null || title == null) {
			return;
		}
		Context c = button.getContext();
		boolean main = type == net.osmand.plus.widgets.dialogbutton.DialogButtonType.PRIMARY
				|| type == net.osmand.plus.widgets.dialogbutton.DialogButtonType.PRIMARY_HARMFUL;
		boolean harmful = type == net.osmand.plus.widgets.dialogbutton.DialogButtonType.PRIMARY_HARMFUL
				|| type == net.osmand.plus.widgets.dialogbutton.DialogButtonType.SECONDARY_HARMFUL
				|| type == net.osmand.plus.widgets.dialogbutton.DialogButtonType.TERTIARY_HARMFUL;
		int color = harmful ? ChizuMenuPills.warning(c) : ChizuTheme.getColor(c, ChizuTheme.Slot.ACCENT);
		int dim = (color & 0x00FFFFFF) | (ChizuMenuPills.DISABLED_ALPHA << 24);
		ColorStateList colors = new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {}}, new int[] {dim, color});
		float density = c.getResources().getDisplayMetrics().density;
		GradientDrawable pill = new GradientDrawable();
		pill.setColor(0);
		pill.setCornerRadius(100 * density);
		pill.setStroke(Math.max(1, Math.round((main ? MAIN_DP : REGULAR_DP) * density)), colors);
		body.setBackground(pill);
		title.setTextColor(colors);
		title.setTypeface(Typeface.create(title.getTypeface(), main ? Typeface.BOLD : Typeface.NORMAL));
		for (Drawable icon : title.getCompoundDrawables()) {
			if (icon != null) {
				icon.mutate().setTintList(colors);
			}
		}
	}
}
