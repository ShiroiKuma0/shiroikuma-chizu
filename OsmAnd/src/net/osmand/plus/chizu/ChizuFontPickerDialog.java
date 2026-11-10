package net.osmand.plus.chizu;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
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

		// 白い熊 地図 +067: an action that opens the file picker — a regular pill as wide as its label, with ›
		TextView addRow = new TextView(activity);
		addRow.setTextSize(16);
		addRow.setText(activity.getString(R.string.chizu_font_add) + "  ›");
		addRow.setTextColor(accent);
		int ph = Math.round(16 * density);
		int pv = Math.round(9 * density);
		addRow.setPadding(ph, pv, ph, pv);
		addRow.setBackground(ChizuPills.drawable(activity, accent, background, false));
		android.widget.LinearLayout.LayoutParams addParams = new android.widget.LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		addParams.setMargins(Math.round(16 * density), Math.round(6 * density), 0, Math.round(6 * density));
		addRow.setLayoutParams(addParams);
		addRow.setOnClickListener(v -> {
			dialog.dismiss();
			callback.onAddFont();
		});
		holder.addView(addRow);

		dialog.show();
		ChizuPills.styleDialog(dialog); // 白い熊 地図 +067
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
