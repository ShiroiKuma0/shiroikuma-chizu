package net.osmand.plus.chizu;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.AbsListView;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.view.menu.ListMenuItemView;
import androidx.appcompat.view.menu.MenuItemImpl;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import net.osmand.plus.R;

/**
 * 白い熊 地図 +090: the row of every AppCompat popup menu — {@code PopupMenu}, its submenus and the toolbar's
 * overflow — in the black-yellow menu look (see {@link ChizuMenuPills}). It is swapped in through the app's
 * own copies of AppCompat's {@code abc_popup_menu_item_layout} and {@code abc_cascading_menu_item_layout},
 * which override the library's by name, so no call site changes. After the row view of the FairEmail fork
 * ({@code PillMenuItemView}), where the look was settled.
 */
@SuppressLint("RestrictedApi")
public class ChizuPillMenuItemView extends ListMenuItemView {

	private boolean ready;
	private LinearLayout content;
	private TextView title;
	private ImageView arrow;
	private int padStart, padTop, padEnd, padBottom;
	private int contentHeight;

	public ChizuPillMenuItemView(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public ChizuPillMenuItemView(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
	}

	@Override
	protected void onFinishInflate() {
		super.onFinishInflate();
		try {
			content = findViewById(androidx.appcompat.R.id.content);
			title = findViewById(androidx.appcompat.R.id.title);
			arrow = findViewById(androidx.appcompat.R.id.submenuarrow);
			ready = content != null && title != null;
			if (ready) {
				padStart = content.getPaddingStart();
				padTop = content.getPaddingTop();
				padEnd = content.getPaddingEnd();
				padBottom = content.getPaddingBottom();
				contentHeight = content.getLayoutParams().height;
				// the stock group divider is all but invisible on a black menu: a clear line, as wide as the pills
				View divider = findViewById(androidx.appcompat.R.id.group_divider);
				if (divider != null) {
					divider.setBackgroundColor(ChizuMenuPills.divider(getContext()));
					ViewGroup.MarginLayoutParams dlp = (ViewGroup.MarginLayoutParams) divider.getLayoutParams();
					dlp.leftMargin = dp(8);
					dlp.rightMargin = dp(8);
					divider.setLayoutParams(dlp);
				}
			}
		} catch (Throwable e) {
			ready = false;
		}
	}

	@Override
	public void initialize(MenuItemImpl itemData, int menuType) {
		super.initialize(itemData, menuType);
		if (ready) {
			try {
				style(itemData);
			} catch (Throwable e) {
				// the stock row stands
			}
		}
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		// the pill carries the ripple; the list's rectangular press highlight would paint over its round ends
		ViewParent parent = getParent();
		if (ready && parent instanceof AbsListView) {
			AbsListView list = (AbsListView) parent;
			if (!(list.getSelector() instanceof ColorDrawable)) {
				list.setSelector(new ColorDrawable(Color.TRANSPARENT));
			}
		}
	}

	private void style(MenuItemImpl item) {
		Context context = getContext();
		boolean checkable = item.isCheckable();
		boolean pill = !checkable;
		boolean destructive = pill && ChizuMenuPills.isDestructive(context, item.getTitle());

		int color = destructive ? ChizuMenuPills.warning(context) : ChizuMenuPills.accent(context);
		int iconColor = ChizuMenuPills.iconColor(context, item.getTitle(), destructive);
		if (!item.isEnabled()) {
			color = ColorUtils.setAlphaComponent(color, ChizuMenuPills.DISABLED_ALPHA);
			iconColor = ColorUtils.setAlphaComponent(iconColor, ChizuMenuPills.DISABLED_ALPHA);
		}

		ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) content.getLayoutParams();
		int h = dp(8);
		int v = pill ? dp(ChizuMenuPills.PILL_MARGIN_DP) : 0;
		// a row that toggles has nothing to frame, so it is only as tall as its switch needs
		int height = checkable ? dp(ChizuMenuPills.TOGGLE_HEIGHT_DP) : contentHeight;
		if (lp.leftMargin != h || lp.topMargin != v || lp.rightMargin != h || lp.bottomMargin != v || lp.height != height) {
			lp.height = height;
			lp.setMargins(h, v, h, v);
			content.setLayoutParams(lp);
		}
		content.setBackground(pill ? pill(color) : null);
		// the switch of a row that toggles ends where the pills end
		content.setPaddingRelative(padStart, padTop, checkable ? 0 : padEnd, padBottom);
		title.setTextColor(color);

		if (arrow != null) {
			boolean opens = pill && (item.hasSubMenu() || ChizuMenuPills.opens(item.getTitle()));
			if (opens) {
				Drawable chevron = ContextCompat.getDrawable(context, R.drawable.chizu_menu_chevron);
				if (chevron != null) {
					chevron = chevron.mutate();
					chevron.setTint(color);
					arrow.setImageDrawable(chevron);
				}
			}
			arrow.setVisibility(opens ? View.VISIBLE : View.GONE);
		}

		for (int i = 0; i < content.getChildCount(); i++) {
			View child = content.getChildAt(i);
			if (child instanceof CompoundButton) {
				// a checkbox or a radio button: the outlined switch instead
				if (child.getTag() == null) {
					CompoundButton cb = (CompoundButton) child;
					cb.setButtonDrawable(new ChizuMenuPills.SwitchDrawable(ChizuMenuPills.accent(context),
							ChizuMenuPills.switchOff(context), dp(44), dp(24), dp(2)));
					cb.setBackground(null);
					ViewGroup.MarginLayoutParams clp = (ViewGroup.MarginLayoutParams) cb.getLayoutParams();
					clp.setMarginStart(dp(8)); // a long label stops short of the switch
					cb.setLayoutParams(clp);
					cb.setTag(Boolean.TRUE);
				}
			} else if (child.getId() == androidx.appcompat.R.id.icon) {
				// clear of the round end of the pill, and not squeezed in a low row
				ViewGroup.MarginLayoutParams ilp = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
				if (ilp.topMargin != dp(2)) {
					ilp.setMarginStart(dp(14));
					ilp.setMarginEnd(-dp(6));
					ilp.topMargin = dp(2);
					ilp.bottomMargin = dp(2);
					child.setLayoutParams(ilp);
				}
				if (child instanceof ImageView) {
					((ImageView) child).setImageTintList(ColorStateList.valueOf(iconColor));
				}
			}
		}
	}

	private Drawable pill(int color) {
		float radius = dp(100);
		GradientDrawable frame = new GradientDrawable();
		frame.setShape(GradientDrawable.RECTANGLE);
		frame.setCornerRadius(radius);
		frame.setColor(Color.TRANSPARENT);
		frame.setStroke(Math.max(1, Math.round(ChizuPills.REGULAR_DP * getResources().getDisplayMetrics().density)), color);
		GradientDrawable mask = new GradientDrawable();
		mask.setShape(GradientDrawable.RECTANGLE);
		mask.setCornerRadius(radius);
		mask.setColor(Color.WHITE);
		int ripple = ColorUtils.setAlphaComponent(color, Math.round(Color.alpha(color) * 0.3f));
		return new RippleDrawable(ColorStateList.valueOf(ripple), frame, mask);
	}

	private int dp(int value) {
		return Math.round(value * getResources().getDisplayMetrics().density);
	}
}
