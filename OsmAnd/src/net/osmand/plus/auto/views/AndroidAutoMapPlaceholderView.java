package net.osmand.plus.auto.views;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.settings.enums.ThemeUsageContext;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.plus.utils.ColorUtilities;
import net.osmand.plus.utils.UiUtilities;

import androidx.annotation.AnyRes;
import androidx.annotation.AttrRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class AndroidAutoMapPlaceholderView extends FrameLayout {

	private final View container;
	private final ImageView icon;
	private final TextView title;
	private final TextView desc;

	public AndroidAutoMapPlaceholderView(@NonNull Context context) {
		this(context, null);
	}

	public AndroidAutoMapPlaceholderView(@NonNull Context context, @Nullable AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public AndroidAutoMapPlaceholderView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		this(context, attrs, defStyleAttr, 0);
	}

	public AndroidAutoMapPlaceholderView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
		super(context, attrs, defStyleAttr, defStyleRes);

		boolean nightMode = getMyApplication().getDaynightHelper().isNightMode(ThemeUsageContext.MAP);
		inflate(UiUtilities.getThemedContext(context, nightMode), R.layout.android_auto_map_placeholder, this);

		container = findViewById(R.id.container);
		icon = findViewById(R.id.icon);
		title = findViewById(R.id.title);
		desc = findViewById(R.id.desc);
		chizuAddPillToggle();
	}

	/**
	 * 白い熊 地図 +085: one tap on the phone switches the position pill of the car screen on or off (白い熊,
	 * 2026-11-18). An outlined pill, as every action of the black-yellow theme.
	 */
	private void chizuAddPillToggle() {
		if (!(desc.getParent() instanceof android.widget.LinearLayout)) {
			return;
		}
		android.widget.LinearLayout parent = (android.widget.LinearLayout) desc.getParent();
		float d = getResources().getDisplayMetrics().density;
		TextView button = new TextView(getContext());
		android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
		bg.setColor(android.graphics.Color.BLACK);
		bg.setCornerRadius(24 * d);
		bg.setStroke(Math.round(1.25f * d), 0xFFFFFF00);
		button.setBackground(bg);
		button.setTextColor(0xFFFFFF00);
		button.setTextSize(16);
		button.setGravity(android.view.Gravity.CENTER);
		button.setPadding(Math.round(24 * d), Math.round(12 * d), Math.round(24 * d), Math.round(12 * d));
		Runnable label = () -> button.setText(net.osmand.plus.chizu.ChizuFix.isPillOnCar(getMyApplication())
				? R.string.chizu_fix_pill_on : R.string.chizu_fix_pill_off);
		label.run();
		button.setOnClickListener(v -> {
			OsmandApplication app = getMyApplication();
			net.osmand.plus.chizu.ChizuFix.setPillOnCar(app, !net.osmand.plus.chizu.ChizuFix.isPillOnCar(app));
			label.run();
		});
		android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
				android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.topMargin = Math.round(24 * d);
		lp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
		parent.addView(button, lp);
	}

	public void updateNightMode(boolean nightMode) {
		Context themedContext = UiUtilities.getThemedContext(getContext(), nightMode);
		container.setBackgroundResource(resolveAttr(themedContext, R.attr.mapBackground));
		icon.setImageDrawable(getIcon(resolveAttr(themedContext, R.attr.ic_action_android_auto_logo_colored)));
		title.setTextColor(ColorUtilities.getPrimaryTextColor(themedContext, nightMode));
		desc.setTextColor(ColorUtilities.getSecondaryTextColor(themedContext, nightMode));
	}

	@NonNull
	private Drawable getIcon(@DrawableRes int drawableId) {
		return getMyApplication().getUIUtilities().getIcon(drawableId);
	}

	@AnyRes
	private int resolveAttr(@NonNull Context context, @AttrRes int attrId) {
		return AndroidUtils.resolveAttribute(context, attrId);
	}

	@NonNull
	private OsmandApplication getMyApplication() {
		return ((OsmandApplication) getContext().getApplicationContext());
	}
}