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

	private ChizuPills() {
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
