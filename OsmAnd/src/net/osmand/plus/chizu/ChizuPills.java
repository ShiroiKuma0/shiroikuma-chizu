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
		if (button == null || button.getVisibility() != android.view.View.VISIBLE) {
			return;
		}
		Context c = button.getContext();
		float density = c.getResources().getDisplayMetrics().density;
		int accent = ChizuTheme.getColor(c, ChizuTheme.Slot.ACCENT);
		int inside = ChizuTheme.getColor(c, ChizuTheme.Slot.CARD_BACKGROUND);
		button.setBackground(drawable(c, accent, inside, main));
		button.setTextColor(accent);
		button.setTypeface(button.getTypeface(), main ? Typeface.BOLD : Typeface.NORMAL);
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
}
