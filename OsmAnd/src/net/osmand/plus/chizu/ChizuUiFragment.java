package net.osmand.plus.chizu;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import net.osmand.plus.R;
import net.osmand.plus.base.BaseOsmAndFragment;
import net.osmand.plus.quickaction.MapButtonsHelper;
import net.osmand.plus.utils.AndroidUtils;

import java.util.Locale;

/**
 * shiroikuma fork: the 白い熊 地図 UI page.
 *
 * One tight, deeply indented page: bold underlined section headings, every item
 * significantly indented per level, every control with a live preview. Opened from
 * Settings ("白い熊 地図 UI") or by long-pressing the drawer (hamburger) map button.
 */
public class ChizuUiFragment extends BaseOsmAndFragment {

	public static final String TAG = ChizuUiFragment.class.getName();

	// kxkb indent ladder: section heading 36, its rows 72, sub-heading 54, sub-rows 90
	private static final int SECTION_INSET_DP = 36;
	private static final int SUBHEAD_INSET_DP = 54;
	private static final int BASE_INSET_DP = 14;

	private LinearLayout holder;
	private float density;
	private ActivityResultLauncher<String[]> fontImportLauncher;
	private ChizuExim exim;

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		fontImportLauncher = registerForActivityResult(
				new ActivityResultContracts.OpenDocument(), this::onFontImported);
		exim = new ChizuExim(this, app);
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.chizu_ui_fragment, container, false);
		density = view.getResources().getDisplayMetrics().density;
		holder = view.findViewById(R.id.chizu_holder);
		view.findViewById(R.id.chizu_back).setOnClickListener(v -> requireActivity().onBackPressed());
		return view;
	}

	@Override
	public void onResume() {
		super.onResume();
		buildRows();
	}

	private void buildRows() {
		View root = getView();
		if (root == null) {
			return;
		}
		int background = color(ChizuTheme.Slot.BACKGROUND);
		int text = color(ChizuTheme.Slot.TEXT);
		int accent = color(ChizuTheme.Slot.ACCENT);
		int divider = color(ChizuTheme.Slot.DIVIDER);

		root.setBackgroundColor(background);
		TextView title = root.findViewById(R.id.chizu_title);
		title.setTextColor(text);
		applyGlobalFont(title);
		ImageView back = root.findViewById(R.id.chizu_back);
		back.setColorFilter(color(ChizuTheme.Slot.ICON));
		root.findViewById(R.id.chizu_toolbar).setBackgroundColor(color(ChizuTheme.Slot.TOOLBAR_BACKGROUND));
		root.findViewById(R.id.chizu_toolbar_rule).setBackgroundColor(divider);

		holder.removeAllViews();

		// ===== Export / Import =====
		addSection(R.string.chizu_section_exim, accent);
		addEximDirRow(1);
		addEximOpenRow(1);
		addAutomationSwitchRow(1);
		addAutomationTokenRow(1);

		// ===== Foundation =====
		addSection(R.string.chizu_section_foundation, accent);
		addColorRow(ChizuTheme.Slot.BACKGROUND, 1);
		addColorRow(ChizuTheme.Slot.CARD_BACKGROUND, 1);
		addColorRow(ChizuTheme.Slot.TEXT, 1);
		addColorRow(ChizuTheme.Slot.TEXT_SECONDARY, 1);
		addColorRow(ChizuTheme.Slot.ACCENT, 1);
		addColorRow(ChizuTheme.Slot.ICON, 1);
		addColorRow(ChizuTheme.Slot.DIVIDER, 1);

		// ===== Text & fonts =====
		addSection(R.string.chizu_section_fonts, accent);
		addSubgroup(R.string.chizu_subgroup_app_font, accent, 1);
		addFontFamilyRow(2);
		addFontWeightRow(2);
		TextView fontSample = addSampleRow(2);
		refreshFontSample(fontSample);
		addSubgroup(R.string.chizu_subgroup_sizes, accent, 1);
		addMapTextSizeRow(2);

		// ===== Top bar =====
		addSection(R.string.chizu_section_top_bar, accent);
		addColorRow(ChizuTheme.Slot.TOOLBAR_BACKGROUND, 1);

		// ===== Map buttons =====
		addSection(R.string.chizu_section_map_buttons, accent);
		addSubgroup(R.string.chizu_subgroup_colors, accent, 1);
		addColorRow(ChizuTheme.Slot.MAP_BUTTON_BACKGROUND, 2);
		addColorRow(ChizuTheme.Slot.MAP_BUTTON_ICON, 2);
		addSubgroup(R.string.chizu_subgroup_shape, accent, 1);
		addMapButtonSliders(2);

		// ===== Maintenance =====
		addSection(R.string.chizu_section_maintenance, accent);
		addResetRow(1);
		addNoteRow(R.string.chizu_restart_note, 1);
	}

	// ---------- row builders ----------

	// kxkb-style section header: a 1px full-width separator above every group but the
	// first, then a big bold heading with a text-wide underline.
	private void addSection(int labelRes, @ColorInt int accent) {
		boolean first = holder.getChildCount() == 0;
		LinearLayout section = new LinearLayout(requireContext());
		section.setOrientation(LinearLayout.VERTICAL);
		section.setPadding(0, first ? dp(12) : dp(10), 0, dp(2));

		if (!first) {
			View spacer = new View(requireContext());
			spacer.setBackgroundColor(accent);
			section.addView(spacer, new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, 1));
		}

		LinearLayout inner = new LinearLayout(requireContext());
		inner.setOrientation(LinearLayout.VERTICAL);
		inner.setPadding(dp(SECTION_INSET_DP), first ? 0 : dp(8), dp(BASE_INSET_DP), 0);

		TextView label = new TextView(requireContext());
		label.setText(labelRes);
		label.setTextSize(20);
		label.setTypeface(currentGlobalOrDefault(), Typeface.BOLD);
		label.setTextColor(accent);
		inner.addView(label);
		inner.addView(textWideRule(label, accent, 2.5f));

		section.addView(inner);
		holder.addView(section);
	}

	private void addSubgroup(int labelRes, @ColorInt int accent, int level) {
		LinearLayout sub = new LinearLayout(requireContext());
		sub.setOrientation(LinearLayout.VERTICAL);
		sub.setPadding(dp(SUBHEAD_INSET_DP), dp(10), dp(BASE_INSET_DP), dp(2));

		TextView label = new TextView(requireContext());
		label.setText(labelRes);
		label.setTextSize(17);
		label.setTypeface(currentGlobalOrDefault(), Typeface.BOLD);
		label.setTextColor(accent);
		sub.addView(label);
		sub.addView(textWideRule(label, accent, 1.5f));

		holder.addView(sub);
	}

	/** An underline exactly as wide as the label's text. */
	private View textWideRule(@NonNull TextView label, @ColorInt int accent, float heightDp) {
		label.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
		View rule = new View(requireContext());
		rule.setBackgroundColor(accent);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
				label.getMeasuredWidth(), Math.max(1, dp(heightDp)));
		params.topMargin = dp(2);
		rule.setLayoutParams(params);
		return rule;
	}

	// ---------- Export / Import section ----------

	// report-only: red when no backup directory is set, yellow (with the name) once it is;
	// the directory is chosen inside the Export / Import panel
	private void addEximDirRow(int level) {
		LinearLayout box = new LinearLayout(requireContext());
		box.setOrientation(LinearLayout.VERTICAL);
		box.setPadding(indent(level), dp(6), dp(BASE_INSET_DP), dp(6));

		TextView caption = new TextView(requireContext());
		caption.setText(R.string.chizu_exim_dir_label);
		caption.setTextSize(12);
		caption.setTextColor(color(ChizuTheme.Slot.ACCENT));
		applyGlobalFont(caption);
		box.addView(caption);

		ChizuExim.Status status = exim.queryStatus();
		TextView value = new TextView(requireContext());
		value.setTextSize(15);
		value.setTypeface(currentGlobalOrDefault(), Typeface.BOLD);
		if (status.dirName == null) {
			value.setText(R.string.chizu_exim_dir_unset_page);
			value.setTextColor(ChizuExim.WARN_COLOR);
		} else {
			value.setText(status.dirName);
			value.setTextColor(color(ChizuTheme.Slot.ACCENT));
		}
		box.addView(value);

		TextView statusLine = new TextView(requireContext());
		statusLine.setText(status.message);
		statusLine.setTextSize(13);
		statusLine.setTextColor(status.warn ? ChizuExim.WARN_COLOR : color(ChizuTheme.Slot.ACCENT));
		applyGlobalFont(statusLine);
		box.addView(statusLine);

		holder.addView(box);
	}

	private void addEximOpenRow(int level) {
		LinearLayout box = new LinearLayout(requireContext());
		box.setOrientation(LinearLayout.VERTICAL);
		box.setPadding(indent(level), dp(5), dp(BASE_INSET_DP), dp(5));
		TypedValue out = new TypedValue();
		requireContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true);
		box.setBackgroundResource(out.resourceId);

		TextView title = new TextView(requireContext());
		title.setText(R.string.chizu_exim_open);
		title.setTextSize(16);
		title.setTextColor(color(ChizuTheme.Slot.TEXT));
		applyGlobalFont(title);
		box.addView(title);

		TextView descr = new TextView(requireContext());
		descr.setText(R.string.chizu_exim_open_descr);
		descr.setTextSize(13);
		descr.setTextColor(color(ChizuTheme.Slot.TEXT_SECONDARY));
		applyGlobalFont(descr);
		box.addView(descr);

		box.setOnClickListener(v -> exim.showPanel());
		holder.addView(box);
	}

	// the automation gate lives with the backup rows it drives — 保存復元 contract, §2
	private void addAutomationSwitchRow(int level) {
		LinearLayout row = tightRow(level);
		row.addView(stackedLabels(getString(R.string.chizu_automation),
				getString(R.string.chizu_automation_descr)));

		SwitchCompat toggle = new SwitchCompat(requireContext());
		int accent = color(ChizuTheme.Slot.ACCENT);
		toggle.setThumbTintList(ColorStateList.valueOf(accent));
		toggle.setTrackTintList(ColorStateList.valueOf(color(ChizuTheme.Slot.DIVIDER)));
		toggle.setChecked(ChizuAutomation.isEnabled(requireContext()));
		toggle.setOnCheckedChangeListener(
				(button, checked) -> ChizuAutomation.setEnabled(requireContext(), checked));
		row.addView(toggle);

		row.setOnClickListener(v -> toggle.toggle());
		holder.addView(row);
	}

	private void addAutomationTokenRow(int level) {
		LinearLayout row = tightRow(level);
		String token = ChizuAutomation.getToken(requireContext());

		LinearLayout labels = stackedLabels(getString(R.string.chizu_automation_token),
				ChizuAutomation.abbreviate(token));
		((TextView) labels.getChildAt(1)).setTextColor(color(ChizuTheme.Slot.ACCENT));
		((TextView) labels.getChildAt(1)).setTypeface(Typeface.MONOSPACE);
		row.addView(labels);

		TextView regenerate = new TextView(requireContext());
		regenerate.setText(R.string.chizu_automation_regenerate);
		regenerate.setTextSize(14);
		regenerate.setTextColor(color(ChizuTheme.Slot.ACCENT));
		regenerate.setPadding(dp(12), dp(6), 0, dp(6));
		regenerate.setOnClickListener(v -> new AlertDialog.Builder(requireActivity())
				.setMessage(R.string.chizu_automation_regenerate_confirm)
				.setPositiveButton(android.R.string.ok, (dialog, which) -> {
					ChizuAutomation.regenerate(requireContext());
					buildRows();
					Toast.makeText(requireContext(), R.string.chizu_automation_regenerated,
							Toast.LENGTH_LONG).show();
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show());
		row.addView(regenerate);

		row.setOnClickListener(v -> copyToken(token));
		holder.addView(row);
	}

	private void copyToken(@NonNull String token) {
		ClipboardManager clipboard =
				(ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
		if (clipboard == null) {
			return;
		}
		clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.chizu_automation_token), token));
		Toast.makeText(requireContext(), R.string.chizu_automation_token_copied, Toast.LENGTH_SHORT).show();
	}

	/** A weighted title + description column, as the tappable rows use. */
	private LinearLayout stackedLabels(@NonNull String title, @NonNull String description) {
		LinearLayout labels = new LinearLayout(requireContext());
		labels.setOrientation(LinearLayout.VERTICAL);
		labels.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		TextView titleView = new TextView(requireContext());
		titleView.setText(title);
		titleView.setTextSize(16);
		titleView.setTextColor(color(ChizuTheme.Slot.TEXT));
		applyGlobalFont(titleView);
		labels.addView(titleView);

		TextView descrView = new TextView(requireContext());
		descrView.setText(description);
		descrView.setTextSize(13);
		descrView.setTextColor(color(ChizuTheme.Slot.TEXT_SECONDARY));
		applyGlobalFont(descrView);
		labels.addView(descrView);

		return labels;
	}

	/** Rebuilds the page (directory status changed, colors imported, …). */
	void refreshPage() {
		if (getView() != null) {
			buildRows();
		}
	}

	/** Closes this settings page (the end of the export/import close chain). */
	void closePage() {
		FragmentActivity activity = getActivity();
		if (activity != null) {
			activity.getSupportFragmentManager().popBackStack();
		}
	}

	private void addColorRow(ChizuTheme.Slot slot, int level) {
		LinearLayout row = tightRow(level);

		TextView label = itemLabel(getString(slot.labelRes));
		row.addView(label);

		View swatch = new View(requireContext());
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(30), dp(22));
		swatch.setLayoutParams(params);
		setSwatch(swatch, color(slot));
		row.addView(swatch);

		row.setOnClickListener(v -> ChizuColorPickerDialog.show(requireActivity(), color(slot),
				ChizuTheme.isOverridden(requireContext(), slot),
				new ChizuColorPickerDialog.Callback() {
					@Override
					public void onColorPicked(int picked) {
						ChizuTheme.setColor(requireContext(), slot, picked);
						buildRows();
					}

					@Override
					public void onReset() {
						ChizuTheme.clearColor(requireContext(), slot);
						buildRows();
					}
				}));
		holder.addView(row);
	}

	private void addFontFamilyRow(int level) {
		LinearLayout row = tightRow(level);
		row.addView(itemLabel(getString(R.string.chizu_font_family)));

		TextView value = valueLabel();
		String family = ChizuFonts.getGlobalFamily();
		value.setText(familyDisplayName(family));
		// render the current choice in its own glyphs
		value.setTypeface(ChizuFonts.typefaceFor(requireContext(), family));
		row.addView(value);

		row.setOnClickListener(v -> ChizuFontPickerDialog.show(requireActivity(), ChizuFonts.getGlobalFamily(),
				new ChizuFontPickerDialog.Callback() {
					@Override
					public void onFontPicked(@NonNull String fileName) {
						ChizuFonts.setGlobalFamily(fileName);
						buildRows();
					}

					@Override
					public void onAddFont() {
						fontImportLauncher.launch(new String[] {"*/*"});
					}
				}));
		holder.addView(row);
	}

	private void addFontWeightRow(int level) {
		LinearLayout row = tightRow(level);
		row.addView(itemLabel(getString(R.string.chizu_font_weight)));

		TextView value = valueLabel();
		int weight = ChizuFonts.getGlobalWeight();
		value.setText(weight > 0 ? String.valueOf(weight) : getString(R.string.chizu_weight_default));
		row.addView(value);

		row.setOnClickListener(v -> {
			String[] labels = {getString(R.string.chizu_weight_default),
					"100", "200", "300", "400", "500", "600", "700", "800", "900"};
			int[] weights = {0, 100, 200, 300, 400, 500, 600, 700, 800, 900};
			int checked = 0;
			for (int i = 0; i < weights.length; i++) {
				if (weights[i] == weight) {
					checked = i;
				}
			}
			new AlertDialog.Builder(requireActivity())
					.setSingleChoiceItems(labels, checked, (dialog, which) -> {
						ChizuFonts.setGlobalWeight(weights[which]);
						dialog.dismiss();
						buildRows();
					})
					.setNegativeButton(android.R.string.cancel, null)
					.show();
		});
		holder.addView(row);
	}

	private TextView addSampleRow(int level) {
		TextView sample = new TextView(requireContext());
		sample.setPadding(indent(level), dp(2), dp(BASE_INSET_DP), dp(6));
		sample.setTextSize(18);
		sample.setText(R.string.chizu_font_sample);
		holder.addView(sample);
		return sample;
	}

	private void refreshFontSample(TextView sample) {
		sample.setTextColor(color(ChizuTheme.Slot.TEXT));
		applyGlobalFont(sample);
	}

	private void addMapTextSizeRow(int level) {
		float current = settings.TEXT_SCALE.get();
		TextView sample = new TextView(requireContext());
		sample.setPadding(indent(level), 0, dp(BASE_INSET_DP), dp(4));
		sample.setText(R.string.chizu_font_sample);
		sample.setTextColor(color(ChizuTheme.Slot.TEXT_SECONDARY));
		sample.setTextSize(14 * current);

		addSliderRow(getString(R.string.chizu_map_text_size), level, 50, 200,
				Math.round(current * 100), "%", (value, preview) -> {
					settings.TEXT_SCALE.set(value / 100f);
					sample.setTextSize(14 * (value / 100f));
				}, null);
		holder.addView(sample);
	}

	private void addMapButtonSliders(int level) {
		MapButtonsHelper buttonsHelper = app.getMapButtonsHelper();

		int sizeValue = buttonsHelper.getDefaultSizePref().get();
		int cornerValue = buttonsHelper.getDefaultCornerRadiusPref().get();
		float opacityValue = buttonsHelper.getDefaultOpacityPref().get();
		int size = sizeValue > 0 ? sizeValue : 48;
		int corner = cornerValue >= 0 ? cornerValue : 36;
		int opacity = Math.round((opacityValue >= 0 ? opacityValue : 1f) * 100);

		addSliderRow(getString(R.string.chizu_map_button_size), level, 32, 72, size, " dp",
				(value, preview) -> {
					buttonsHelper.getDefaultSizePref().set(value);
					updateButtonPreview(preview);
				}, this::updateButtonPreview);
		addSliderRow(getString(R.string.chizu_map_button_roundness), level, 0, 36, corner, " dp",
				(value, preview) -> {
					buttonsHelper.getDefaultCornerRadiusPref().set(value);
					updateButtonPreview(preview);
				}, this::updateButtonPreview);
		addSliderRow(getString(R.string.chizu_map_button_opacity), level, 0, 100, opacity, "%",
				(value, preview) -> {
					buttonsHelper.getDefaultOpacityPref().set(value / 100f);
					updateButtonPreview(preview);
				}, this::updateButtonPreview);
	}

	private void updateButtonPreview(@NonNull View preview) {
		MapButtonsHelper buttonsHelper = app.getMapButtonsHelper();
		int sizeValue = buttonsHelper.getDefaultSizePref().get();
		int cornerValue = buttonsHelper.getDefaultCornerRadiusPref().get();
		float opacityValue = buttonsHelper.getDefaultOpacityPref().get();
		int sizePx = dp(sizeValue > 0 ? Math.min(sizeValue, 48) : 48);

		GradientDrawable drawable = new GradientDrawable();
		int background = color(ChizuTheme.Slot.MAP_BUTTON_BACKGROUND);
		int alpha = Math.round((opacityValue >= 0 ? opacityValue : 1f) * 255);
		drawable.setColor((background & 0x00FFFFFF) | (alpha << 24));
		drawable.setStroke(dp(1), color(ChizuTheme.Slot.DIVIDER));
		drawable.setCornerRadius(dp(cornerValue >= 0 ? cornerValue : 36));

		ViewGroup.LayoutParams params = preview.getLayoutParams();
		params.width = sizePx;
		params.height = sizePx;
		preview.setLayoutParams(params);
		preview.setBackground(drawable);
	}

	private interface SliderListener {
		void onChanged(int value, @NonNull View preview);
	}

	private interface PreviewConfigurator {
		void configure(@NonNull View preview);
	}

	private void addSliderRow(String label, int level, int min, int max, int current,
			String unit, SliderListener listener, @Nullable PreviewConfigurator previewConfigurator) {
		LinearLayout row = tightRow(level);

		TextView labelView = itemLabel(label);
		labelView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.55f));
		row.addView(labelView);

		SeekBar seekBar = new SeekBar(requireContext());
		seekBar.setMax(max - min);
		seekBar.setProgress(current - min);
		seekBar.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.45f));
		row.addView(seekBar);

		TextView value = valueLabel();
		value.setMinWidth(dp(52));
		value.setGravity(Gravity.END);
		value.setText(String.format(Locale.US, "%d%s", current, unit));
		row.addView(value);

		View preview = new View(requireContext());
		LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(dp(30), dp(30));
		previewParams.setMarginStart(dp(8));
		preview.setLayoutParams(previewParams);
		if (previewConfigurator != null) {
			previewConfigurator.configure(preview);
			row.addView(preview);
		}

		seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
				int newValue = min + progress;
				value.setText(String.format(Locale.US, "%d%s", newValue, unit));
				listener.onChanged(newValue, preview);
			}

			@Override
			public void onStartTrackingTouch(SeekBar bar) {
			}

			@Override
			public void onStopTrackingTouch(SeekBar bar) {
			}
		});
		holder.addView(row);
	}

	private void addResetRow(int level) {
		LinearLayout row = tightRow(level);
		TextView label = itemLabel(getString(R.string.chizu_reset_all));
		label.setTextColor(color(ChizuTheme.Slot.ACCENT));
		row.addView(label);
		row.setOnClickListener(v -> new AlertDialog.Builder(requireActivity())
				.setMessage(R.string.chizu_reset_all_confirm)
				.setPositiveButton(android.R.string.ok, (dialog, which) -> {
					ChizuTheme.clearAll(requireContext());
					ChizuFonts.setGlobalFamily("");
					ChizuFonts.setGlobalWeight(0);
					buildRows();
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show());
		holder.addView(row);
	}

	private void addNoteRow(int labelRes, int level) {
		TextView note = new TextView(requireContext());
		note.setText(labelRes);
		note.setTextSize(12);
		note.setTextColor(color(ChizuTheme.Slot.TEXT_SECONDARY));
		note.setPadding(indent(level), dp(6), dp(BASE_INSET_DP), 0);
		holder.addView(note);
	}

	// ---------- helpers ----------

	private LinearLayout tightRow(int level) {
		LinearLayout row = new LinearLayout(requireContext());
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		// tight: minimal vertical padding, deep start indent per level
		row.setPadding(indent(level), dp(5), dp(BASE_INSET_DP), dp(5));
		TypedValue out = new TypedValue();
		requireContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true);
		row.setBackgroundResource(out.resourceId);
		return row;
	}

	private TextView itemLabel(String label) {
		TextView view = new TextView(requireContext());
		view.setText(label);
		view.setTextSize(16);
		view.setTextColor(color(ChizuTheme.Slot.TEXT));
		applyGlobalFont(view);
		view.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		return view;
	}

	private TextView valueLabel() {
		TextView view = new TextView(requireContext());
		view.setTextSize(15);
		view.setTextColor(color(ChizuTheme.Slot.TEXT_SECONDARY));
		return view;
	}

	private void setSwatch(View view, @ColorInt int fill) {
		GradientDrawable drawable = new GradientDrawable();
		drawable.setColor(fill);
		drawable.setStroke(Math.max(1, dp(1)), color(ChizuTheme.Slot.DIVIDER));
		drawable.setCornerRadius(dp(4));
		view.setBackground(drawable);
	}

	private void applyGlobalFont(TextView view) {
		Typeface global = ChizuFonts.getGlobalTypeface();
		if (global != null) {
			int style = view.getTypeface() != null ? view.getTypeface().getStyle() : Typeface.NORMAL;
			view.setTypeface(global, style);
		}
	}

	private Typeface currentGlobalOrDefault() {
		Typeface global = ChizuFonts.getGlobalTypeface();
		return global != null ? global : Typeface.DEFAULT;
	}

	private String familyDisplayName(String family) {
		if (family.isEmpty()) {
			return getString(R.string.chizu_font_system_default);
		}
		if (ChizuFonts.MONOSPACE.equals(family)) {
			return getString(R.string.chizu_font_monospace);
		}
		if (ChizuFonts.SERIF.equals(family)) {
			return getString(R.string.chizu_font_serif);
		}
		int dot = family.lastIndexOf('.');
		return dot > 0 ? family.substring(0, dot) : family;
	}

	private void onFontImported(@Nullable Uri uri) {
		if (uri == null) {
			return;
		}
		String name = ChizuFonts.importFont(requireContext(), uri);
		if (name != null) {
			ChizuFonts.setGlobalFamily(name);
			buildRows();
		} else {
			Toast.makeText(requireContext(), R.string.chizu_font_import_failed, Toast.LENGTH_SHORT).show();
		}
	}

	private int color(ChizuTheme.Slot slot) {
		return ChizuTheme.getColor(requireContext(), slot);
	}

	private int indent(int level) {
		// kxkb ladder: section rows at 72dp, sub-rows at 90dp
		return dp(level >= 2 ? 90 : 72);
	}

	private int dp(float value) {
		return (int) (value * density + 0.5f);
	}

	public static void showInstance(@NonNull FragmentActivity activity) {
		FragmentManager manager = activity.getSupportFragmentManager();
		if (AndroidUtils.isFragmentCanBeAdded(manager, TAG)) {
			manager.beginTransaction()
					.replace(R.id.fragmentContainer, new ChizuUiFragment(), TAG)
					.addToBackStack(TAG)
					.commitAllowingStateLoss();
		}
	}
}
