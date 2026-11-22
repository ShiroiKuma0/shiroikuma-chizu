package net.osmand.plus.widgets.multistatetoggle;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.plus.utils.ColorUtilities;
import net.osmand.plus.utils.UiUtilities;
import net.osmand.plus.widgets.multistatetoggle.RadioItem.OnRadioItemClickListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public abstract class MultiStateToggleButton<_Radio extends RadioItem> {

	protected static final float BACKGROUND_ALPHA = 0.1f;
	protected static final float BACKGROUND_STROKE_ALPHA = 0.5f;
	// 白い熊 地図 +108: the frame widths of a main and a regular pill, and how far an unselected segment dims.
	protected static final float SELECTED_STROKE_DP = 3f;
	protected static final float UNSELECTED_STROKE_DP = 1.25f;
	protected static final float UNSELECTED_DIM = 0.55f;

	protected final OsmandApplication app;
	protected final UiUtilities uiUtilities;

	protected final LinearLayout container;
	protected final List<ViewGroup> buttons = new ArrayList<>();
	protected final List<View> dividers = new ArrayList<>();

	protected final List<_Radio> items = new ArrayList<>();
	protected final boolean nightMode;
	protected _Radio selectedItem;

	public MultiStateToggleButton(@NonNull OsmandApplication app,
	                              @NonNull LinearLayout container,
	                              boolean nightMode) {
		this.app = app;
		this.uiUtilities = app.getUIUtilities();
		this.container = container;
		this.nightMode = nightMode;
	}

	@SafeVarargs
	public final void setItems(@NonNull _Radio... radioItems) {
		setItems(Arrays.asList(radioItems));
	}

	public void setItems(Collection<_Radio> radioItems) {
		if (radioItems == null || radioItems.size() < 2) return;
		items.clear();
		items.addAll(radioItems);
		initView();
	}

	@NonNull
	public List<_Radio> getItems() {
		return items;
	}

	public final void setSelectedItem(int itemIndex) {
		_Radio selectedItem = itemIndex < 0 || itemIndex >= items.size() ? null : items.get(itemIndex);
		setSelectedItem(selectedItem);
	}

	public final void setSelectedItemByTag(@Nullable Object tag) {
		_Radio selectedItem = findItemByTag(tag);
		setSelectedItem(selectedItem);
	}

	public final void setSelectedItem(@Nullable _Radio selectedItem) {
		this.selectedItem = selectedItem;
		updateView();
	}

	public int getSelectedItemIndex() {
		return items.indexOf(selectedItem);
	}

	@Nullable
	public _Radio findItemByTag(@Nullable Object tag) {
		for (_Radio item : items) {
			if (Objects.equals(item.getTag(), tag)) {
				return item;
			}
		}
		return null;
	}

	private void initView() {
		buttons.clear();
		dividers.clear();
		container.removeAllViews();
		for (int i = 0; i < items.size(); i++) {
			createBtn(items.get(i));
		}
		updateView();
	}

	private void createBtn(@NonNull _Radio item) {
		Context context = container.getContext();
		LayoutInflater inflater = UiUtilities.getInflater(context, nightMode);
		ViewGroup button = (ViewGroup) inflater.inflate(
				getRadioItemLayoutId(), container, false);
		button.setOnClickListener(v -> {
			OnRadioItemClickListener l = item.getListener();
			if (l != null && l.onRadioItemClick(item, container)) {
				setSelectedItem(item);
			}
		});
		initItemView(button, item);
		buttons.add(button);
		container.addView(button);
	}

	public void setItemsEnabled(boolean enable) {
		for (_Radio item: items) {
			item.setEnabled(enable);
		}
		updateView();
	}

	public void updateView() {
		int activeColor = ColorUtilities.getActiveColor(app, nightMode);
		int defaultColor = ColorUtilities.getDefaultIconColor(app, nightMode);
		int textColorPrimary = ColorUtilities.getPrimaryTextColor(app, nightMode);
		int textColorSecondary = ColorUtilities.getSecondaryTextColor(app, nightMode);

		int selectedItemIndex = getSelectedItemIndex();
		int radius = AndroidUtils.dpToPx(app, 4);
		float[] leftBtnRadii = {radius, radius, 0, 0, 0, 0, radius, radius};
		float[] rightBtnRadii = {0, 0, radius, radius, radius, radius, 0, 0};
		float[] internalBtnRadii = {0, 0, 0, 0, 0, 0, 0, 0};
		boolean isLayoutRtl = AndroidUtils.isLayoutRtl(app);

		for (int i = 0; i < items.size(); i++) {
			_Radio item = items.get(i);
			ViewGroup button = buttons.get(i);
			View borderBackgroundView = getBorderBackgroundView(button);

			boolean enabled = item.isEnabled();
			button.setEnabled(enabled);
			int textColor = enabled ? textColorPrimary : textColorSecondary;
			int selectedBgColor = enabled ? activeColor : defaultColor;
			int contentColor = item.getCustomColor() != null ? item.getCustomColor() : activeColor;
			int itemColor = enabled ? contentColor : defaultColor;
			GradientDrawable background = new GradientDrawable();

			if (i == 0) {
				background.setCornerRadii(isLayoutRtl ? rightBtnRadii : leftBtnRadii);
			} else if (isLastItem(i)) {
				background.setCornerRadii(isLayoutRtl ? leftBtnRadii : rightBtnRadii);
			} else {
				background.setCornerRadii(internalBtnRadii);
			}
			// 白い熊 地図 +108: the selected segment is told by weight, as a main pill is — a thick frame in the
			// full colour and bold text, nothing filled — and the others step back to a thin, dim frame and dim
			// text. Upstream's 10 % fill inside a half-transparent frame was the dimmest segment of the row here,
			// where the outline of an unselected one is the full accent.
			if (selectedItem == item) {
				background.setStroke(AndroidUtils.dpToPx(app, SELECTED_STROKE_DP), selectedBgColor);
				borderBackgroundView.setBackground(background);
				updateItemView(button, item, true, textColor);
			} else {
				LayerDrawable layerDrawable = new LayerDrawable(new GradientDrawable[]{background});
				setupLayerInset(i, selectedItemIndex, radius, layerDrawable);
				background.setStroke(AndroidUtils.dpToPx(app, UNSELECTED_STROKE_DP), dim(selectedBgColor));
				borderBackgroundView.setBackground(layerDrawable);
				updateItemView(button, item, false, item.getCustomColor() != null && enabled ? itemColor : dim(itemColor));
			}
			String contentDescription = item.getContentDescription();
			if (contentDescription != null) {
				button.setContentDescription(contentDescription);
			}
			Drawable selectedDrawable = UiUtilities.getColoredSelectableDrawable(app, selectedBgColor, BACKGROUND_ALPHA);
			borderBackgroundView.setForeground(selectedDrawable);
		}
	}

	/** 白い熊 地図 +108: the colour at 55 % of its strength, opaque — #8C8C00 for the yellow accent. */
	@ColorInt
	private static int dim(@ColorInt int color) {
		return 0xFF000000
				| Math.round(((color >> 16) & 0xFF) * UNSELECTED_DIM) << 16
				| Math.round(((color >> 8) & 0xFF) * UNSELECTED_DIM) << 8
				| Math.round((color & 0xFF) * UNSELECTED_DIM);
	}

	private void setupLayerInset(int currentItemIndex, int selectedItemIndex, int radius, @NonNull LayerDrawable layerDrawable) {
		int hideSide = radius * -1;
		if (currentItemIndex == 0) {
			layerDrawable.setLayerInset(0, 0, 0, selectedItemIndex == currentItemIndex + 1 ? hideSide : 0, 0);
		} else if (isLastItem(currentItemIndex)) {
			layerDrawable.setLayerInset(0, hideSide, 0, 0, 0);
		} else {
			layerDrawable.setLayerInset(0, hideSide, 0, selectedItemIndex == currentItemIndex + 1 ? hideSide : 0, 0);
		}
	}

	protected View getBorderBackgroundView(@NonNull ViewGroup button){
		return button;
	}

	protected abstract int getRadioItemLayoutId();

	protected abstract void initItemView(@NonNull ViewGroup view, @NonNull _Radio item);

	protected abstract void updateItemView(@NonNull ViewGroup view, @NonNull _Radio item,
	                                       boolean selected, @ColorInt int color);

	private boolean isLastItem(int index) {
		return index == items.size() - 1;
	}
}