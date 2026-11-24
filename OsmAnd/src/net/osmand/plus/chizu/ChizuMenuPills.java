package net.osmand.plus.chizu;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.ColorUtils;

import net.osmand.plus.R;

import java.util.Locale;

/**
 * 白い熊 地図 +090: the black-yellow menu rule (白い熊, 2026-11-15, settled in the FairEmail fork; the
 * black-yellow-theme skill, “Menus”). Every popup menu of the app — its own dropdowns
 * ({@code OsmAndDropdownMenu.kt}, 59 call sites), AppCompat's popup menus and the toolbar overflow
 * ({@link ChizuPillMenuItemView}), and the position pill's menu ({@link ChizuWifiTeach}) — shows which lines
 * can be pressed: an action row is an outlined pill as wide as the menu with its icon at the left and a ›
 * when it opens something; a destructive one lends its red to frame, icon and label; a row that toggles
 * stays plain with the outlined switch; dividers are a clear darker-accent line.
 * <p>
 * Here: the colours those share, what a row is by its title (menu items carry no flags for it), and the
 * outlined switch — a round outline with a solid dot, right and accent when on, left and a darker,
 * opaque accent when off, never filled.
 */
public final class ChizuMenuPills {

	public static final int DISABLED_ALPHA = 97; // 38 %
	public static final int PILL_MARGIN_DP = 3;
	public static final int TOGGLE_HEIGHT_DP = 36;

	private ChizuMenuPills() {
	}

	@ColorInt
	public static int accent(@NonNull Context c) {
		return ChizuTheme.getColor(c, ChizuTheme.Slot.ACCENT);
	}

	@ColorInt
	public static int background(@NonNull Context c) {
		return ChizuTheme.getColor(c, ChizuTheme.Slot.BACKGROUND);
	}

	/** The warning role: what deletes. */
	@ColorInt
	public static int warning(@NonNull Context c) {
		return ChizuTheme.getColor(c, ChizuTheme.Slot.MENU_DELETE);
	}

	/** A group divider: 70 % of the way from the background to the accent, opaque. */
	@ColorInt
	public static int divider(@NonNull Context c) {
		return ColorUtils.blendARGB(background(c), accent(c), 0.70f);
	}

	/** A switch that is off: 55 % of the way from the background to the text, opaque — darker, not faded. */
	@ColorInt
	public static int switchOff(@NonNull Context c) {
		if (ChizuTheme.isOverridden(c, ChizuTheme.Slot.SWITCH_OFF)) {
			return ChizuTheme.getColor(c, ChizuTheme.Slot.SWITCH_OFF);
		}
		return ColorUtils.blendARGB(background(c), ChizuTheme.getColor(c, ChizuTheme.Slot.TEXT), 0.55f);
	}

	private static boolean says(@NonNull Context c, @Nullable CharSequence title, int... stringIds) {
		if (title == null) {
			return false;
		}
		String t = title.toString().toLowerCase(Locale.ROOT);
		for (int id : stringIds) {
			try {
				String word = c.getString(id).toLowerCase(Locale.ROOT);
				if (!word.isEmpty() && t.contains(word)) {
					return true;
				}
			} catch (Exception e) {
				// a string this build lacks
			}
		}
		return false;
	}

	/** The row deletes or removes something. */
	public static boolean isDestructive(@NonNull Context c, @Nullable CharSequence title) {
		return says(c, title, R.string.shared_string_delete, R.string.shared_string_remove, R.string.shared_string_delete_all);
	}

	/** The row opens a dialog, a picker or a screen: its title ends in an ellipsis. */
	public static boolean opens(@Nullable CharSequence title) {
		if (title == null) {
			return false;
		}
		String t = title.toString().trim();
		return t.endsWith("…") || t.endsWith("...");
	}

	/** The icon's colour by what the row does: red deletes, orange copies/shares/exports, the accent for the rest. */
	@ColorInt
	public static int iconColor(@NonNull Context c, @Nullable CharSequence title, boolean destructive) {
		if (destructive) {
			return warning(c);
		}
		if (says(c, title, R.string.shared_string_share, R.string.shared_string_export, R.string.shared_string_copy,
				R.string.shared_string_save, R.string.shared_string_download)) {
			return ChizuTheme.getColor(c, ChizuTheme.Slot.MENU_SHARE);
		}
		return accent(c);
	}

	/** The outlined switch as a drawable; it follows {@code state_checked}. */
	public static final class SwitchDrawable extends Drawable {
		private final int on, off, width, height;
		private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF rect = new RectF();
		private boolean checked;

		public SwitchDrawable(@ColorInt int on, @ColorInt int off, int width, int height, int strokeWidth) {
			this.on = on;
			this.off = off;
			this.width = width;
			this.height = height;
			stroke.setStyle(Paint.Style.STROKE);
			stroke.setStrokeWidth(strokeWidth);
			fill.setStyle(Paint.Style.FILL);
		}

		@Override
		public void draw(@NonNull Canvas canvas) {
			Rect b = getBounds();
			// drawn at its own size, centred, whatever bounds the host gives it
			float w = Math.min(width, b.width());
			float h = Math.min(height, b.height());
			float left = b.exactCenterX() - w / 2f;
			float top = b.exactCenterY() - h / 2f;
			int color = checked ? on : off;
			float inset = stroke.getStrokeWidth() / 2f;
			rect.set(left + inset, top + inset, left + w - inset, top + h - inset);
			stroke.setColor(color);
			canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, stroke);
			fill.setColor(color);
			float cx = checked ? left + w - h / 2f : left + h / 2f;
			canvas.drawCircle(cx, top + h / 2f, h * 0.23f, fill);
		}

		@Override
		public boolean isStateful() {
			return true;
		}

		@Override
		protected boolean onStateChange(@NonNull int[] state) {
			boolean c = false;
			for (int s : state) {
				if (s == android.R.attr.state_checked) {
					c = true;
					break;
				}
			}
			if (c == checked) {
				return false;
			}
			checked = c;
			invalidateSelf();
			return true;
		}

		@Override
		public int getIntrinsicWidth() {
			return width;
		}

		@Override
		public int getIntrinsicHeight() {
			return height;
		}

		@Override
		public void setAlpha(int alpha) {
		}

		@Override
		public void setColorFilter(@Nullable ColorFilter colorFilter) {
		}

		@Override
		public int getOpacity() {
			return PixelFormat.TRANSLUCENT;
		}
	}

	/**
	 * The theme's switch for the fork's own pages and menus: a {@link SwitchCompat} whose track is the
	 * outlined pill with its dot and whose thumb is nothing, so it is never filled. {@code small} is the
	 * menu size (44 × 24 dp), else 52 × 28 dp.
	 */
	@NonNull
	public static SwitchCompat newSwitch(@NonNull Context c, boolean small) {
		float d = c.getResources().getDisplayMetrics().density;
		int w = Math.round((small ? 44 : 52) * d);
		int h = Math.round((small ? 24 : 28) * d);
		SwitchCompat toggle = new SwitchCompat(c) {
			@Override
			public void setThumbTintList(@Nullable android.content.res.ColorStateList tint) {
				// the outlined switch has its own colours
			}

			@Override
			public void setTrackTintList(@Nullable android.content.res.ColorStateList tint) {
			}
		};
		ColorDrawable thumb = new ColorDrawable(Color.TRANSPARENT) {
			@Override
			public int getIntrinsicWidth() {
				return w / 2;
			}

			@Override
			public int getIntrinsicHeight() {
				return h;
			}
		};
		toggle.setThumbDrawable(thumb);
		toggle.setTrackDrawable(new SwitchDrawable(accent(c), switchOff(c), w, h, Math.round(2 * d)));
		toggle.setSwitchMinWidth(w);
		toggle.setMinHeight(0);
		toggle.setMinimumHeight(0);
		toggle.setBackground(null);
		return toggle;
	}
}
