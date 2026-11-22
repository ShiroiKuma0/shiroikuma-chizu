package net.osmand.plus.chizu;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 白い熊 地図 +109: how a selection control shows which of its parts is chosen.
 *
 * <p>Upstream marks the chosen part of a selector with a faint tint of the accent inside a half-transparent
 * frame, next to parts drawn in the outline colour. That works on a palette whose outline is a pale grey. On
 * this one the outline is the accent itself, so the chosen part came out as the dimmest of its row.
 *
 * <p>The rule here is the one a main pill follows: weight, not fill. The chosen part has a thick frame in the
 * full colour and a bold label; every other part a thin frame and a label at 55 % of the colour. Nothing is
 * filled. Segmented selectors, the older radio rows, the two-choice button, group tiles and chips all read
 * their widths and their dimming from this class.
 */
public final class ChizuSelection {

	/** Frame of the chosen part — the width of a main pill's frame. */
	public static final float SELECTED_DP = 3f;
	/** Frame of every other part — the width of a regular pill's frame. */
	public static final float UNSELECTED_DP = 1.25f;
	/** How much of its colour a part that is not chosen keeps. */
	public static final float DIM = 0.55f;

	private ChizuSelection() {
	}

	/** The colour at 55 % of its strength, opaque — #8C8C00 for the yellow accent. */
	@ColorInt
	public static int dim(@ColorInt int color) {
		return 0xFF000000
				| Math.round(Color.red(color) * DIM) << 16
				| Math.round(Color.green(color) * DIM) << 8
				| Math.round(Color.blue(color) * DIM);
	}

	/** The frame width in pixels, never less than one. */
	public static int strokePx(@NonNull Context c, boolean selected) {
		float density = c.getResources().getDisplayMetrics().density;
		return Math.max(1, Math.round((selected ? SELECTED_DP : UNSELECTED_DP) * density));
	}

	/** The frame colour: the colour itself for the chosen part, dimmed for the others. */
	@ColorInt
	public static int strokeColor(boolean selected, @ColorInt int color) {
		return selected ? color : dim(color);
	}

	/** An unfilled frame for one part; {@code radii} as {@link GradientDrawable#setCornerRadii} takes them. */
	@NonNull
	public static GradientDrawable frame(@NonNull Context c, boolean selected, @ColorInt int color,
	                                     @Nullable float[] radii) {
		GradientDrawable frame = new GradientDrawable();
		frame.setColor(Color.TRANSPARENT);
		if (radii != null) {
			frame.setCornerRadii(radii);
		}
		frame.setStroke(strokePx(c, selected), strokeColor(selected, color));
		return frame;
	}

	/** The label of one part: bold in {@code selectedColor} when chosen, normal and dimmed otherwise. */
	public static void label(@Nullable TextView label, boolean selected, @ColorInt int selectedColor,
	                         @ColorInt int otherColor) {
		if (label != null) {
			label.setTextColor(selected ? selectedColor : dim(otherColor));
			label.setTypeface(Typeface.create(label.getTypeface(), selected ? Typeface.BOLD : Typeface.NORMAL));
		}
	}

	/**
	 * A row of two or three parts side by side, of which {@code selectedIndex} is chosen. Parts that are
	 * missing or hidden are skipped, and the outer corners go to whichever parts end up first and last.
	 */
	public static void row(@NonNull Context c, int selectedIndex, @ColorInt int accent, @ColorInt int textColor,
	                       float radius, boolean rtl, @NonNull View[] parts, @NonNull TextView[] labels) {
		int first = -1;
		int last = -1;
		for (int i = 0; i < parts.length; i++) {
			if (parts[i] != null && parts[i].getVisibility() == View.VISIBLE) {
				if (first < 0) {
					first = i;
				}
				last = i;
			}
		}
		for (int i = 0; i < parts.length; i++) {
			if (parts[i] == null) {
				continue;
			}
			boolean start = rtl ? i == last : i == first;
			boolean end = rtl ? i == first : i == last;
			float l = start ? radius : 0;
			float r = end ? radius : 0;
			boolean selected = i == selectedIndex;
			parts[i].setBackground(frame(c, selected, accent, new float[] {l, l, r, r, r, r, l, l}));
			label(labels[i], selected, textColor, accent);
		}
	}
}
