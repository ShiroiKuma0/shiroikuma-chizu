package net.osmand.plus.chizu;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.widget.CompoundButtonCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.FragmentActivity;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.RestartActivity;
import net.osmand.plus.settings.backend.ExportCategory;
import net.osmand.plus.settings.backend.backup.FileSettingsHelper;
import net.osmand.plus.settings.backend.backup.SettingsHelper.CollectListener;
import net.osmand.plus.settings.backend.backup.SettingsHelper.ImportListener;
import net.osmand.plus.settings.backend.backup.exporttype.ExportType;
import net.osmand.plus.settings.backend.backup.items.SettingsItem;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.plus.utils.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * shiroikuma fork: the Export / Import panel of the 白い熊 地図 UI page.
 *
 * The panel is one caller of {@link ChizuBackup} — the automation receiver
 * ({@link ChizuStateExportReceiver}) is the other, and both write the very same archive:
 * the stock settings ZIP (all selected stock export types, maps included) plus the
 * 白い熊 地図 UI sidecar (colors, fonts, sizes and the imported font files) inside it.
 * Import reads that archive back, replacing duplicates silently.
 */
public class ChizuExim {

	private static final String IMPORT_TEMP_NAME = "chizu_import.osf";
	/** Resolution of the export progress bar (the message line carries the real numbers). */
	private static final int PROGRESS_STEPS = 1000;

	/** Warning red for the "no backup directory set" state (yellow once set). */
	public static final int WARN_COLOR = 0xFFFF5252;

	private final ChizuUiFragment fragment;
	private final OsmandApplication app;

	private final ActivityResultLauncher<Uri> dirPicker;
	private final ActivityResultLauncher<String[]> importPicker;

	private AlertDialog panel;
	private ProgressDialog progress;
	private final Map<ExportType, CheckBox> typeChecks = new LinkedHashMap<>();
	private CheckBox chizuUiCheck;
	private TextView panelDirValue;
	private TextView panelDirStatus;
	private volatile int sizeCountToken;

	private final AtomicBoolean exportCancelled = new AtomicBoolean();
	private volatile long lastProgressPost;

	/** Must be constructed in the fragment's onCreate (activity-result registration). */
	ChizuExim(@NonNull ChizuUiFragment fragment, @NonNull OsmandApplication app) {
		this.fragment = fragment;
		this.app = app;
		dirPicker = fragment.registerForActivityResult(
				new ActivityResultContracts.OpenDocumentTree(), uri -> {
					if (uri != null) {
						onDirPicked(uri);
					}
				});
		importPicker = fragment.registerForActivityResult(
				new ActivityResultContracts.OpenDocument(), uri -> {
					if (uri != null) {
						onImportFilePicked(uri);
					}
				});
	}

	// ---------- backup directory (stored device-locally by ChizuBackup) ----------

	@Nullable
	public DocumentFile getDir() {
		return ChizuBackup.getDir(app);
	}

	public void pickDirectory() {
		dirPicker.launch(ChizuBackup.getDirUri(app));
	}

	private void onDirPicked(@NonNull Uri uri) {
		try {
			app.getContentResolver().takePersistableUriPermission(uri,
					Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
		} catch (Exception ignored) {
		}
		ChizuBackup.setDirUri(app, uri);
		fragment.refreshPage();
		refreshPanelDirBox();
	}

	// ---------- status for the UI page ----------

	public static class Status {
		public final String dirName;   // null when no directory is set
		public final String message;
		public final boolean warn;     // red when true, yellow otherwise

		Status(@Nullable String dirName, @NonNull String message, boolean warn) {
			this.dirName = dirName;
			this.message = message;
			this.warn = warn;
		}
	}

	@NonNull
	public Status queryStatus() {
		DocumentFile dir = getDir();
		if (dir == null) {
			return new Status(null, app.getString(R.string.chizu_exim_warn_nodir), true);
		}
		String name = dir.getName() != null ? dir.getName() : "…";
		DocumentFile newest = newestExport(dir);
		if (newest == null) {
			return new Status(name, app.getString(R.string.chizu_exim_warn_none), false);
		}
		String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
				.format(new Date(newest.lastModified()));
		return new Status(name, String.format(app.getString(R.string.chizu_exim_last), stamp), false);
	}

	@Nullable
	private DocumentFile newestExport(@NonNull DocumentFile dir) {
		DocumentFile newest = null;
		try {
			for (DocumentFile file : dir.listFiles()) {
				String name = file.getName();
				// .zip is the family convention; .osf are the backups written before it
				if (file.isFile() && ChizuBackup.isExportName(name)) {
					if (newest == null || file.lastModified() > newest.lastModified()) {
						newest = file;
					}
				}
			}
		} catch (Exception ignored) {
		}
		return newest;
	}

	// ---------- the Export / Import panel ----------

	public void showPanel() {
		FragmentActivity activity = fragment.getActivity();
		if (activity == null) {
			return;
		}
		Context ctx = activity;
		int accent = color(ChizuTheme.Slot.ACCENT);
		int text = color(ChizuTheme.Slot.TEXT);
		typeChecks.clear();

		LinearLayout list = new LinearLayout(ctx);
		list.setOrientation(LinearLayout.VERTICAL);
		list.setPadding(dp(20), dp(4), dp(20), dp(8));

		// The backup directory — a bordered, tappable box at the very top of the panel
		LinearLayout dirBox = new LinearLayout(ctx);
		dirBox.setOrientation(LinearLayout.VERTICAL);
		dirBox.setPadding(dp(12), dp(8), dp(12), dp(8));
		GradientDrawable dirBorder = new GradientDrawable();
		dirBorder.setColor(color(ChizuTheme.Slot.CARD_BACKGROUND));
		dirBorder.setCornerRadius(dp(6));
		dirBorder.setStroke(Math.max(1, dp(1)), color(ChizuTheme.Slot.DIVIDER));
		dirBox.setBackground(dirBorder);
		LinearLayout.LayoutParams dirParams = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		dirParams.topMargin = dp(4);
		dirParams.bottomMargin = dp(6);
		dirBox.setLayoutParams(dirParams);

		TextView dirCaption = new TextView(ctx);
		dirCaption.setText(R.string.chizu_exim_dir);
		dirCaption.setTextSize(12);
		dirCaption.setTextColor(accent);
		dirBox.addView(dirCaption);

		panelDirValue = new TextView(ctx);
		panelDirValue.setTextSize(15);
		panelDirValue.setTypeface(panelDirValue.getTypeface(), Typeface.BOLD);
		dirBox.addView(panelDirValue);

		panelDirStatus = new TextView(ctx);
		panelDirStatus.setTextSize(13);
		dirBox.addView(panelDirStatus);

		dirBox.setOnClickListener(v -> pickDirectory());
		list.addView(dirBox);
		refreshPanelDirBox();

		// Every row starts ticked or unticked exactly as LIST_CATEGORIES answers for it,
		// so this sheet and 保存復元's automation picker open on the same selection
		List<ChizuBackup.Cat> catalogue = ChizuBackup.catalogue(app);

		// Why half the rows below are greyed: the shared folder is not this app's to back up.
		if (ChizuStorage.isStorageFolderShared(app)) {
			TextView note = new TextView(ctx);
			note.setText(app.getString(R.string.chizu_exim_shared_folder_note,
					app.getAppPath(null).getAbsolutePath()));
			note.setTextSize(13);
			note.setTextColor(text);
			note.setAlpha(0.7f);
			note.setPadding(0, dp(6), 0, dp(2));
			list.addView(note);
		}

		// Maps — a separate block at the very beginning, deselected by default
		CheckBox mapsMaster = addCheck(list, ctx, app.getString(R.string.chizu_exim_maps),
				ChizuBackup.startsTicked(catalogue, ChizuBackup.GROUP_MAPS), true, 0, accent, text);
		Map<ExportType, CheckBox> mapTypeChecks = new LinkedHashMap<>();
		for (ExportType type : ExportType.mapValues()) {
			if (type.isAvailable() && !type.isHidden()) {
				CheckBox cb = addCheck(list, ctx, type.getTitle(ctx),
						ChizuBackup.startsTicked(catalogue, type), false, 24, accent, text);
				greyOutIfItCannotTravel(cb, type);
				typeChecks.put(type, cb);
				mapTypeChecks.put(type, cb);
			}
		}
		mapsMaster.setOnCheckedChangeListener((button, isChecked) -> {
			for (CheckBox cb : mapTypeChecks.values()) {
				if (cb.isEnabled()) {
					cb.setChecked(isChecked);
				}
			}
		});
		// a master over rows that can all do nothing is itself a promise it cannot keep
		if (!ChizuBackup.travels(app, ExportType.STANDARD_MAPS)) {
			mapsMaster.setChecked(false);
			mapsMaster.setEnabled(false);
			mapsMaster.setAlpha(0.45f);
		}
		startMapSizeCount(mapTypeChecks, mapsMaster);

		// The stock categories — all selected by default but the downloadable voice packages
		for (ExportCategory category : ExportCategory.values()) {
			addPanelHeading(list, ctx, app.getString(category.getTitleId()), accent);
			if (category == ExportCategory.SETTINGS) {
				chizuUiCheck = addCheck(list, ctx, app.getString(R.string.chizu_exim_chizu_ui),
						ChizuBackup.startsTicked(catalogue, ChizuBackup.ID_CHIZU_UI), false, 0, accent, text);
			}
			for (ExportType type : ExportType.availableValuesOf(category)) {
				if (!type.isMap() && !type.isHidden()) {
					CheckBox cb = addCheck(list, ctx, type.getTitle(ctx),
							ChizuBackup.startsTicked(catalogue, type), false, 0, accent, text);
					greyOutIfItCannotTravel(cb, type);
					typeChecks.put(type, cb);
				}
			}
		}

		ScrollView scroll = new ScrollView(ctx);
		scroll.addView(list);

		AlertDialog dialog = new AlertDialog.Builder(activity)
				.setTitle(R.string.chizu_section_exim)
				.setView(scroll)
				.setPositiveButton(R.string.shared_string_export, null)
				.setNegativeButton(R.string.shared_string_import, null)
				.setNeutralButton(R.string.shared_string_cancel, null)
				.create();
		dialog.show();
		styleDialogWindow(dialog);
		stylePillButtons(dialog);
		// click listeners set after show() so failures can leave the panel open
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> onExportClicked());
		dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> onImportClicked());
		dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> dialog.dismiss());
		dialog.setOnDismissListener(d -> sizeCountToken++); // stop the size counter
		panel = dialog;
	}

	/**
	 * Counts the on-disk size of every map subcategory on a background thread and
	 * live-updates the sub-lines and the running Maps total as each one finishes.
	 */
	private void startMapSizeCount(@NonNull Map<ExportType, CheckBox> mapChecks,
			@NonNull CheckBox mapsMaster) {
		int token = ++sizeCountToken;
		String mapsLabel = app.getString(R.string.chizu_exim_maps);
		new Thread(() -> {
			long total = 0;
			for (Map.Entry<ExportType, CheckBox> entry : mapChecks.entrySet()) {
				if (token != sizeCountToken) {
					return;
				}
				long size = 0;
				try {
					for (Object object : entry.getKey().fetchExportData(app, false)) {
						if (object instanceof File) {
							size += ((File) object).length();
						}
					}
				} catch (Exception ignored) {
				}
				total += size;
				long runningTotal = total;
				long typeSize = size;
				CheckBox checkBox = entry.getValue();
				String title = entry.getKey().getTitle(app);
				app.runInUIThread(() -> {
					if (token == sizeCountToken) {
						checkBox.setText(title + " — " + AndroidUtils.formatSize(app, typeSize));
						mapsMaster.setText(mapsLabel + " — "
								+ AndroidUtils.formatSize(app, runningTotal) + "…");
					}
				});
			}
			long finalTotal = total;
			app.runInUIThread(() -> {
				if (token == sizeCountToken) {
					mapsMaster.setText(mapsLabel + " — " + AndroidUtils.formatSize(app, finalTotal));
				}
			});
		}).start();
	}

	private void refreshPanelDirBox() {
		if (panelDirValue == null || panelDirStatus == null) {
			return;
		}
		int accent = color(ChizuTheme.Slot.ACCENT);
		Status status = queryStatus();
		if (status.dirName == null) {
			panelDirValue.setText(R.string.chizu_exim_dir_unset);
			panelDirValue.setTextColor(WARN_COLOR);
		} else {
			panelDirValue.setText(status.dirName);
			panelDirValue.setTextColor(accent);
		}
		panelDirStatus.setText(status.message);
		panelDirStatus.setTextColor(status.warn ? WARN_COLOR : accent);
	}

	private void addPanelHeading(@NonNull LinearLayout list, @NonNull Context ctx,
			@NonNull String label, int accent) {
		LinearLayout wrap = new LinearLayout(ctx);
		wrap.setOrientation(LinearLayout.VERTICAL);
		wrap.setPadding(0, dp(12), 0, dp(2));

		TextView heading = new TextView(ctx);
		heading.setText(label);
		heading.setTextSize(16);
		heading.setTypeface(heading.getTypeface(), Typeface.BOLD);
		heading.setTextColor(accent);
		wrap.addView(heading);

		heading.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
		View rule = new View(ctx);
		rule.setBackgroundColor(accent);
		LinearLayout.LayoutParams ruleParams =
				new LinearLayout.LayoutParams(heading.getMeasuredWidth(), Math.max(1, dp(1.5f)));
		ruleParams.topMargin = dp(2);
		rule.setLayoutParams(ruleParams);
		wrap.addView(rule);

		list.addView(wrap);
	}

	/**
	 * Greys out a category that cannot travel, so a row never promises a backup that will not
	 * happen.
	 *
	 * <p>With Main storage on a shared folder the export carries pointers and settings, not that
	 * folder's files ({@link ChizuBackup#travels}). Leaving those rows tickable would let 白い熊 tick
	 * "Maps" and get an archive with no maps in it, silently — the same false positive the
	 * automation picker was just taught to avoid. Unticked as well as disabled, so nothing that
	 * cannot travel is ever in the selection.
	 */
	private void greyOutIfItCannotTravel(@NonNull CheckBox cb, @Nullable ExportType type) {
		if (ChizuBackup.travels(app, type)) {
			return;
		}
		cb.setChecked(false);
		cb.setEnabled(false);
		cb.setAlpha(0.45f);
	}

	private CheckBox addCheck(@NonNull LinearLayout list, @NonNull Context ctx, @NonNull String label,
			boolean checked, boolean bold, int indentDp, int accent, int textColor) {
		CheckBox cb = new CheckBox(ctx);
		cb.setText(label);
		cb.setChecked(checked);
		cb.setTextSize(15);
		cb.setTextColor(textColor);
		if (bold) {
			cb.setTypeface(cb.getTypeface(), Typeface.BOLD);
		}
		CompoundButtonCompat.setButtonTintList(cb, ColorStateList.valueOf(accent));
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.setMarginStart(dp(indentDp));
		cb.setLayoutParams(params);
		list.addView(cb);
		return cb;
	}

	@NonNull
	private List<ExportType> selectedTypes() {
		List<ExportType> result = new ArrayList<>();
		for (Map.Entry<ExportType, CheckBox> entry : typeChecks.entrySet()) {
			if (entry.getValue().isChecked()) {
				result.add(entry.getKey());
			}
		}
		return result;
	}

	private boolean chizuUiSelected() {
		return chizuUiCheck != null && chizuUiCheck.isChecked();
	}

	// ---------- export ----------

	private void onExportClicked() {
		List<ExportType> types = selectedTypes();
		boolean withChizu = chizuUiSelected();
		if (types.isEmpty() && !withChizu) {
			app.showToastMessage(R.string.chizu_exim_none_selected);
			return;
		}
		DocumentFile dir = getDir();
		if (dir == null) {
			app.showToastMessage(R.string.chizu_exim_warn_nodir);
			return;
		}
		exportCancelled.set(false);
		String fileName = ChizuBackup.fileName();
		ChizuBackup.Selection selection =
				new ChizuBackup.Selection(types, withChizu, types.size() + (withChizu ? 1 : 0));
		ChizuBackup.Dest dest = new ChizuBackup.SafDest(app, dir, fileName);
		showExportProgress();
		// the very core the automation receiver drives — one export path, two callers.
		// Published while it runs, so an automation CANCEL_EXPORT unwinds this one too.
		ChizuBackup.beginRun(null, exportCancelled);
		new Thread(() -> {
			try {
				ChizuBackup.Result result =
						ChizuBackup.export(app, selection, dest, this::onExportProgress, exportCancelled);
				app.runInUIThread(() -> finishExportUi(result, fileName));
			} finally {
				ChizuBackup.endRun(exportCancelled);
			}
		}, "chizu-ui-export").start();
	}

	/** Horizontal progress with the live byte counter the core reports, and a working Cancel. */
	private void showExportProgress() {
		hideProgress();
		FragmentActivity activity = fragment.getActivity();
		if (activity == null) {
			return;
		}
		lastProgressPost = 0;
		progress = new ProgressDialog(activity);
		progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
		progress.setMessage(app.getString(R.string.chizu_exim_exporting));
		progress.setMax(PROGRESS_STEPS);
		progress.setProgressNumberFormat(null);
		progress.setCancelable(false);
		progress.setButton(DialogInterface.BUTTON_NEGATIVE,
				app.getString(R.string.shared_string_cancel), (d, which) -> cancelExport());
		progress.show();
		styleProgressWindow(progress);
		stylePillButton(progress.getButton(DialogInterface.BUTTON_NEGATIVE));
	}

	/** Called from the export thread — throttled so the main looper is not flooded. */
	private void onExportProgress(long current, long total, @NonNull String unit, @NonNull String text) {
		long now = SystemClock.elapsedRealtime();
		if (now - lastProgressPost < 200 && current < total) {
			return;
		}
		lastProgressPost = now;
		app.runInUIThread(() -> {
			if (progress != null) {
				progress.setMessage(text);
				progress.setProgress(total > 0 ? (int) (current * PROGRESS_STEPS / total) : 0);
			}
		});
	}

	/** The panel's own stop button — the same flag a CANCEL_EXPORT raises, one way to unwind. */
	private void cancelExport() {
		exportCancelled.set(true);
		hideProgress();
	}

	private void finishExportUi(@NonNull ChizuBackup.Result result, @NonNull String fileName) {
		hideProgress();
		if (exportCancelled.get()) {
			return; // cancelled by 白い熊 — no dialogs, the panel stays open
		}
		if (!fragment.isAdded()) {
			return;
		}
		if (result.ok) {
			fragment.refreshPage();
			refreshPanelDirBox();
			showExportDoneDialog(fileName);
		} else {
			app.showToastMessage(R.string.chizu_exim_export_failed);
		}
	}

	// ---------- import ----------

	private void onImportClicked() {
		if (selectedTypes().isEmpty() && !chizuUiSelected()) {
			app.showToastMessage(R.string.chizu_exim_none_selected);
			return;
		}
		importPicker.launch(new String[] {"application/zip", "application/octet-stream", "*/*"});
	}

	private void onImportFilePicked(@NonNull Uri uri) {
		showProgress(app.getString(R.string.chizu_exim_importing));
		List<ExportType> types = selectedTypes();
		boolean withChizu = chizuUiSelected();
		new Thread(() -> {
			File temp = new File(FileUtils.getTempDir(app), IMPORT_TEMP_NAME);
			try (InputStream in = app.getContentResolver().openInputStream(uri);
					OutputStream out = new FileOutputStream(temp)) {
				if (in == null) {
					throw new IOException("no stream");
				}
				ChizuBackup.streamCopy(in, out);
			} catch (Exception e) {
				app.runInUIThread(() -> {
					hideProgress();
					app.showToastMessage(R.string.chizu_exim_import_failed);
				});
				return;
			}
			boolean chizuApplied = withChizu && ChizuBackup.applySidecar(app, temp);
			app.runInUIThread(() -> collectAndImport(temp, types, chizuApplied));
		}).start();
	}

	private void collectAndImport(@NonNull File file, @NonNull List<ExportType> types, boolean chizuApplied) {
		FileSettingsHelper helper = app.getFileSettingsHelper();
		CollectListener collectListener = (succeed, empty, items) -> {
			if (!succeed || empty) {
				hideProgress();
				if (chizuApplied) {
					showImportDoneDialog();
				} else {
					app.showToastMessage(R.string.chizu_exim_import_failed);
				}
				return;
			}
			List<SettingsItem> selected = new ArrayList<>();
			for (SettingsItem item : items) {
				ExportType type = ExportType.findBy(item);
				if (type != null && types.contains(type)) {
					item.setShouldReplace(true);
					selected.add(item);
				}
			}
			if (selected.isEmpty()) {
				hideProgress();
				if (chizuApplied) {
					showImportDoneDialog();
				} else {
					app.showToastMessage(R.string.chizu_exim_import_failed);
				}
				return;
			}
			ImportListener importListener = new ImportListener() {
				@Override
				public void onImportFinished(boolean importOk, boolean needRestart,
						@NonNull List<SettingsItem> finishedItems) {
					hideProgress();
					if (importOk || chizuApplied) {
						showImportDoneDialog();
					} else {
						app.showToastMessage(R.string.chizu_exim_import_failed);
					}
				}
			};
			helper.importSettings(file, selected, "", 1, importListener);
		};
		helper.collectSettings(file, "", 1, collectListener);
	}

	// ---------- finish dialogs + the close chain ----------

	private void showExportDoneDialog(@NonNull String fileName) {
		FragmentActivity activity = fragment.getActivity();
		if (activity == null) {
			return;
		}
		AlertDialog dialog = new AlertDialog.Builder(activity)
				.setTitle(R.string.chizu_exim_export_done)
				.setMessage(String.format(app.getString(R.string.chizu_exim_export_done_msg), fileName))
				.setPositiveButton(R.string.shared_string_ok, (d, which) -> closeChain(false))
				.setCancelable(false)
				.create();
		dialog.show();
		styleDialogWindow(dialog);
		stylePillButtons(dialog);
	}

	private void showImportDoneDialog() {
		FragmentActivity activity = fragment.getActivity();
		if (activity == null) {
			return;
		}
		AlertDialog dialog = new AlertDialog.Builder(activity)
				.setTitle(R.string.chizu_exim_import_done)
				.setMessage(R.string.chizu_exim_import_done_msg)
				.setNegativeButton(R.string.chizu_exim_restart_later, (d, which) -> closeChain(false))
				.setPositiveButton(R.string.restart_now, (d, which) -> closeChain(true))
				.setCancelable(false)
				.create();
		dialog.show();
		styleDialogWindow(dialog);
		stylePillButtons(dialog);
	}

	/** Closes the Export/Import panel and the UI settings page beneath it. */
	private void closeChain(boolean restart) {
		if (panel != null) {
			panel.dismiss();
			panel = null;
		}
		FragmentActivity activity = fragment.getActivity();
		if (restart && activity != null) {
			RestartActivity.doRestartSilent(activity);
			return;
		}
		fragment.closePage();
	}

	// ---------- black-yellow dialog styling (Arcanechat-style pills) ----------

	private void showProgress(@NonNull String message) {
		hideProgress();
		FragmentActivity activity = fragment.getActivity();
		if (activity == null) {
			return;
		}
		progress = new ProgressDialog(activity);
		progress.setMessage(message);
		progress.setIndeterminate(true);
		progress.setCancelable(false);
		progress.show();
		styleProgressWindow(progress);
	}

	private void hideProgress() {
		if (progress != null) {
			try {
				progress.dismiss();
			} catch (Exception ignored) {
			}
			progress = null;
		}
	}

	private void styleDialogWindow(@NonNull AlertDialog dialog) {
		if (dialog.getWindow() != null) {
			dialog.getWindow().setBackgroundDrawable(new InsetDrawable(borderedCard(), dp(16)));
		}
	}

	private void styleProgressWindow(@NonNull ProgressDialog dialog) {
		if (dialog.getWindow() != null) {
			dialog.getWindow().setBackgroundDrawable(new InsetDrawable(borderedCard(), dp(16)));
		}
	}

	@NonNull
	private GradientDrawable borderedCard() {
		GradientDrawable card = new GradientDrawable();
		card.setColor(color(ChizuTheme.Slot.CARD_BACKGROUND));
		card.setCornerRadius(dp(8));
		card.setStroke(Math.max(1, dp(2)), color(ChizuTheme.Slot.ACCENT));
		return card;
	}

	private void stylePillButtons(@NonNull AlertDialog dialog) {
		for (int which : new int[] {AlertDialog.BUTTON_POSITIVE,
				AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
			stylePillButton(dialog.getButton(which));
		}
	}

	private void stylePillButton(@Nullable Button button) {
		if (button == null) {
			return;
		}
		int accent = color(ChizuTheme.Slot.ACCENT);
		int fill = color(ChizuTheme.Slot.CARD_BACKGROUND);
		GradientDrawable pill = new GradientDrawable();
		pill.setColor(fill);
		pill.setCornerRadius(dp(50));
		pill.setStroke(Math.max(1, dp(1.5f)), accent);
		RippleDrawable ripple = new RippleDrawable(
				ColorStateList.valueOf((accent & 0x00FFFFFF) | 0x33000000), pill, null);
		button.setBackground(ripple);
		button.setTextColor(accent);
		button.setPadding(dp(20), dp(6), dp(20), dp(6));
		ViewGroup.LayoutParams params = button.getLayoutParams();
		if (params instanceof ViewGroup.MarginLayoutParams) {
			((ViewGroup.MarginLayoutParams) params).setMarginStart(dp(8));
			button.setLayoutParams(params);
		}
	}

	// ---------- small helpers ----------

	private int color(ChizuTheme.Slot slot) {
		return ChizuTheme.getColor(app, slot);
	}

	private int dp(float value) {
		return (int) (value * app.getResources().getDisplayMetrics().density + 0.5f);
	}
}
