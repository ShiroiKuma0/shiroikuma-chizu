package net.osmand.plus.chizu;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import net.osmand.plus.R;

import java.util.List;
import java.util.Locale;

/**
 * shiroikuma fork: RGBA color picker — four 0..255 sliders (R, G, B, A) with a live
 * old/new preview and one-click swatches of prior-selected colors on top.
 */
public class ChizuColorPickerDialog {

	public interface Callback {
		void onColorPicked(@ColorInt int color);

		default void onReset() {
		}
	}

	private static final String[] CHANNELS = {"R", "G", "B", "A"};

	private final int[] values = new int[4];
	private final SeekBar[] seekBars = new SeekBar[4];
	private final TextView[] valueViews = new TextView[4];
	private View newColorView;
	private TextView hexView;

	public static void show(@NonNull Activity activity, @ColorInt int initialColor,
			boolean allowReset, @NonNull Callback callback) {
		new ChizuColorPickerDialog().showInternal(activity, initialColor, allowReset, callback);
	}

	private void showInternal(@NonNull Activity activity, @ColorInt int initialColor,
			boolean allowReset, @NonNull Callback callback) {
		LayoutInflater inflater = LayoutInflater.from(activity);
		View view = inflater.inflate(R.layout.chizu_dialog_color_picker, null);

		int text = ChizuTheme.getColor(activity, ChizuTheme.Slot.TEXT);
		int background = ChizuTheme.getColor(activity, ChizuTheme.Slot.CARD_BACKGROUND);
		int divider = ChizuTheme.getColor(activity, ChizuTheme.Slot.DIVIDER);
		view.setBackgroundColor(background);

		values[0] = Color.red(initialColor);
		values[1] = Color.green(initialColor);
		values[2] = Color.blue(initialColor);
		values[3] = Color.alpha(initialColor);

		View oldColorView = view.findViewById(R.id.chizu_old_color);
		newColorView = view.findViewById(R.id.chizu_new_color);
		hexView = view.findViewById(R.id.chizu_hex_value);
		hexView.setTextColor(text);
		setSwatchBackground(oldColorView, initialColor, divider);

		// prior-selected colors, one-click
		LinearLayout recentRow = view.findViewById(R.id.chizu_recent_row);
		List<Integer> recent = ChizuTheme.getRecentColors(activity);
		float density = activity.getResources().getDisplayMetrics().density;
		int box = (int) (32 * density);
		int gap = (int) (6 * density);
		for (int color : recent) {
			View swatch = new View(activity);
			LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(box, box);
			params.setMarginEnd(gap);
			swatch.setLayoutParams(params);
			setSwatchBackground(swatch, color, divider);
			swatch.setOnClickListener(v -> applyColor(color));
			recentRow.addView(swatch);
		}
		recentRow.setVisibility(recent.isEmpty() ? View.GONE : View.VISIBLE);

		// R / G / B / A sliders
		LinearLayout slidersHolder = view.findViewById(R.id.chizu_sliders_holder);
		for (int i = 0; i < 4; i++) {
			int channel = i;
			LinearLayout row = new LinearLayout(activity);
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(android.view.Gravity.CENTER_VERTICAL);
			row.setPadding(0, (int) (2 * density), 0, (int) (2 * density));

			TextView label = new TextView(activity);
			label.setText(CHANNELS[i]);
			label.setTextColor(text);
			label.setTextSize(14);
			label.setMinWidth((int) (20 * density));
			row.addView(label);

			SeekBar seekBar = new SeekBar(activity);
			seekBar.setMax(255);
			seekBar.setProgress(values[i]);
			LinearLayout.LayoutParams seekParams =
					new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
			seekBar.setLayoutParams(seekParams);
			seekBars[i] = seekBar;
			row.addView(seekBar);

			TextView value = new TextView(activity);
			value.setText(String.valueOf(values[i]));
			value.setTextColor(text);
			value.setTextSize(14);
			value.setMinWidth((int) (36 * density));
			value.setGravity(android.view.Gravity.END);
			valueViews[i] = value;
			row.addView(value);

			seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
				@Override
				public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
					values[channel] = progress;
					valueViews[channel].setText(String.valueOf(progress));
					updatePreview(divider);
				}

				@Override
				public void onStartTrackingTouch(SeekBar bar) {
				}

				@Override
				public void onStopTrackingTouch(SeekBar bar) {
				}
			});
			slidersHolder.addView(row);
		}
		updatePreview(divider);

		AlertDialog.Builder builder = new AlertDialog.Builder(activity)
				.setView(view)
				.setPositiveButton(android.R.string.ok, (dialog, which) -> {
					int picked = currentColor();
					ChizuTheme.addRecentColor(activity, picked);
					callback.onColorPicked(picked);
				})
				.setNegativeButton(android.R.string.cancel, null);
		if (allowReset) {
			builder.setNeutralButton(R.string.chizu_default, (dialog, which) -> callback.onReset());
		}
		AlertDialog dialog = builder.create();
		dialog.show();
		if (dialog.getWindow() != null) {
			dialog.getWindow().setBackgroundDrawable(swatchDrawable(background, divider, 8));
		}
		int accent = ChizuTheme.getColor(activity, ChizuTheme.Slot.ACCENT);
		for (int which : new int[] {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
			if (dialog.getButton(which) != null) {
				dialog.getButton(which).setTextColor(accent);
			}
		}
	}

	@ColorInt
	private int currentColor() {
		return Color.argb(values[3], values[0], values[1], values[2]);
	}

	private void applyColor(@ColorInt int color) {
		values[0] = Color.red(color);
		values[1] = Color.green(color);
		values[2] = Color.blue(color);
		values[3] = Color.alpha(color);
		for (int i = 0; i < 4; i++) {
			seekBars[i].setProgress(values[i]);
			valueViews[i].setText(String.valueOf(values[i]));
		}
	}

	private void updatePreview(int strokeColor) {
		int color = currentColor();
		setSwatchBackground(newColorView, color, strokeColor);
		hexView.setText(String.format(Locale.US, "#%08X", color));
	}

	private static void setSwatchBackground(@NonNull View view, @ColorInt int color, @ColorInt int stroke) {
		view.setBackground(swatchDrawable(color, stroke, 4));
	}

	@NonNull
	private static GradientDrawable swatchDrawable(@ColorInt int fill, @ColorInt int stroke, int radiusDp) {
		GradientDrawable drawable = new GradientDrawable();
		drawable.setColor(fill);
		float density = android.content.res.Resources.getSystem().getDisplayMetrics().density;
		drawable.setStroke(Math.max(1, (int) density), stroke);
		drawable.setCornerRadius(radiusDp * density);
		return drawable;
	}
}
