package net.osmand.plus.chizu;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import net.osmand.plus.R;

import java.util.List;

/**
 * shiroikuma fork: font picker — every option row is rendered in its own glyphs,
 * with a trailing "Add font…" row that imports an external .ttf/.otf via SAF.
 */
public class ChizuFontPickerDialog {

	public interface Callback {
		void onFontPicked(@NonNull String fileName);

		void onAddFont();
	}

	public static void show(@NonNull Activity activity, @NonNull String currentFileName,
			@NonNull Callback callback) {
		int text = ChizuTheme.getColor(activity, ChizuTheme.Slot.TEXT);
		int accent = ChizuTheme.getColor(activity, ChizuTheme.Slot.ACCENT);
		int background = ChizuTheme.getColor(activity, ChizuTheme.Slot.CARD_BACKGROUND);
		float density = activity.getResources().getDisplayMetrics().density;

		LinearLayout holder = new LinearLayout(activity);
		holder.setOrientation(LinearLayout.VERTICAL);
		int pad = (int) (12 * density);
		holder.setPadding(pad, pad / 2, pad, pad / 2);
		holder.setBackgroundColor(background);

		ScrollView scrollView = new ScrollView(activity);
		scrollView.addView(holder);

		AlertDialog dialog = new AlertDialog.Builder(activity)
				.setView(scrollView)
				.setNegativeButton(android.R.string.cancel, null)
				.create();

		List<ChizuFonts.FontOption> options = ChizuFonts.getAvailableFonts(activity,
				activity.getString(R.string.chizu_font_system_default),
				activity.getString(R.string.chizu_font_monospace),
				activity.getString(R.string.chizu_font_serif));
		String sample = activity.getString(R.string.chizu_font_sample);
		for (ChizuFonts.FontOption option : options) {
			TextView row = makeRow(activity, density);
			boolean selected = option.fileName.equals(currentFileName);
			row.setText(option.displayName + "   " + sample);
			row.setTextColor(selected ? accent : text);
			// each font's name drawn in its own glyphs
			row.setTypeface(ChizuFonts.typefaceFor(activity, option.fileName),
					selected ? Typeface.BOLD : Typeface.NORMAL);
			row.setOnClickListener(v -> {
				dialog.dismiss();
				callback.onFontPicked(option.fileName);
			});
			holder.addView(row);
		}

		TextView addRow = makeRow(activity, density);
		addRow.setText(R.string.chizu_font_add);
		addRow.setTextColor(accent);
		addRow.setTypeface(null, Typeface.BOLD);
		addRow.setOnClickListener(v -> {
			dialog.dismiss();
			callback.onAddFont();
		});
		holder.addView(addRow);

		dialog.show();
		if (dialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null) {
			dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(accent);
		}
	}

	@NonNull
	private static TextView makeRow(@NonNull Activity activity, float density) {
		TextView row = new TextView(activity);
		row.setTextSize(17);
		row.setGravity(Gravity.CENTER_VERTICAL);
		int padV = (int) (8 * density);
		row.setPadding(0, padV, 0, padV);
		android.util.TypedValue out = new android.util.TypedValue();
		activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true);
		row.setBackgroundResource(out.resourceId);
		return row;
	}
}
