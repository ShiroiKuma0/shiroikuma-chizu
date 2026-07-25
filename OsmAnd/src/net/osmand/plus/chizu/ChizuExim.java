package net.osmand.plus.chizu;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
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
import net.osmand.plus.settings.backend.backup.FileSettingsHelper.SettingsExportListener;
import net.osmand.plus.settings.backend.backup.SettingsHelper.CollectListener;
import net.osmand.plus.settings.backend.backup.SettingsHelper.ImportListener;
import net.osmand.plus.settings.backend.backup.exporttype.ExportType;
import net.osmand.plus.settings.backend.backup.items.FileSettingsItem;
import net.osmand.plus.settings.backend.backup.items.SettingsItem;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.plus.utils.FileUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * shiroikuma fork: Export / Import of everything settable in the app, driven from the
 * 白い熊 地図 UI page. Exports the stock .osf settings archive (all stock export types,
 * maps included) into a user-chosen SAF directory, plus a sidecar entry with the
 * 白い熊 地図 UI prefs (colors, fonts, sizes) and the imported font files. Import reads
 * the same archive back, replacing duplicates silently.
 *
 * The chosen directory lives in its own prefs file ("chizu_exim") so it is device-local
 * and never part of any export.
 */
public class ChizuExim {

	private static final String PREFS_NAME = "chizu_exim"; // device-local; never exported
	private static final String KEY_DIR_URI = "dir_uri";
	private static final String EXPORT_PREFIX = "shiroikuma-chizu_";
	private static final String EXPORT_EXT = ".osf";
	private static final String SIDECAR_ENTRY = "chizu_ui.json";
	private static final String SIDECAR_FONTS_PREFIX = "chizu_fonts/";

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

	private volatile boolean exportCancelled;
	private File pendingExportFile;
	private long[] exportCumBytes;
	private int exportTotalItems;

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

	// ---------- backup directory ----------

	private SharedPreferences prefs() {
		return app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
	}

	@Nullable
	private Uri getDirUri() {
		String stored = prefs().getString(KEY_DIR_URI, null);
		if (stored == null) {
			return null;
		}
		try {
			return Uri.parse(stored);
		} catch (Exception e) {
			return null;
		}
	}

	@Nullable
	public DocumentFile getDir() {
		Uri uri = getDirUri();
		if (uri == null) {
			return null;
		}
		try {
			DocumentFile dir = DocumentFile.fromTreeUri(app, uri);
			return dir != null && dir.isDirectory() ? dir : null;
		} catch (Exception e) {
			return null;
		}
	}

	public void pickDirectory() {
		dirPicker.launch(getDirUri());
	}

	private void onDirPicked(@NonNull Uri uri) {
		try {
			app.getContentResolver().takePersistableUriPermission(uri,
					Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
		} catch (Exception ignored) {
		}
		prefs().edit().putString(KEY_DIR_URI, uri.toString()).apply();
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
				if (file.isFile() && name != null
						&& name.startsWith(EXPORT_PREFIX) && name.endsWith(EXPORT_EXT)) {
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

		// Maps — a separate block at the very beginning, deselected by default
		CheckBox mapsMaster = addCheck(list, ctx, app.getString(R.string.chizu_exim_maps),
				false, true, 0, accent, text);
		Map<ExportType, CheckBox> mapTypeChecks = new LinkedHashMap<>();
		for (ExportType type : ExportType.mapValues()) {
			if (type.isAvailable() && !type.isHidden()) {
				CheckBox cb = addCheck(list, ctx, type.getTitle(ctx), false, false, 24, accent, text);
				typeChecks.put(type, cb);
				mapTypeChecks.put(type, cb);
			}
		}
		mapsMaster.setOnCheckedChangeListener((button, isChecked) -> {
			for (CheckBox cb : mapTypeChecks.values()) {
				cb.setChecked(isChecked);
			}
		});
		startMapSizeCount(mapTypeChecks, mapsMaster);

		// The stock categories, everything selected by default
		for (ExportCategory category : ExportCategory.values()) {
			addPanelHeading(list, ctx, app.getString(category.getTitleId()), accent);
			if (category == ExportCategory.SETTINGS) {
				chizuUiCheck = addCheck(list, ctx, app.getString(R.string.chizu_exim_chizu_ui),
						true, false, 0, accent, text);
			}
			for (ExportType type : ExportType.availableValuesOf(category)) {
				if (!type.isMap() && !type.isHidden()) {
					CheckBox cb = addCheck(list, ctx, type.getTitle(ctx), true, false, 0, accent, text);
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
		exportCancelled = false;
		showProgress(app.getString(R.string.chizu_exim_exporting));
		String baseName = exportBaseName();
		// collecting the export data can take a while (map file scans) — off the UI thread
		new Thread(() -> {
			List<SettingsItem> items = types.isEmpty()
					? new ArrayList<>()
					: app.getFileSettingsHelper().getFilteredSettingsItems(types, true, false, false);
			app.runInUIThread(() -> startExport(dir, baseName, items, withChizu));
		}).start();
	}

	private void startExport(@NonNull DocumentFile dir, @NonNull String baseName,
			@NonNull List<SettingsItem> items, boolean withChizu) {
		if (exportCancelled || !fragment.isAdded()) {
			hideProgress();
			return;
		}
		if (items.isEmpty()) {
			// nothing but the 白い熊 地図 UI sidecar — write the archive directly
			new Thread(() -> {
				boolean ok = writeSidecarOnlyArchive(dir, baseName);
				app.runInUIThread(() -> finishExportUi(ok, baseName + EXPORT_EXT));
			}).start();
			return;
		}
		pendingExportFile = new File(FileUtils.getTempDir(app), baseName + EXPORT_EXT);
		showExportProgress(items);
		SettingsExportListener listener = new SettingsExportListener() {
			@Override
			public void onSettingsExportFinished(@NonNull File file, boolean succeed) {
				if (exportCancelled) {
					//noinspection ResultOfMethodCallIgnored
					file.delete();
					hideProgress();
					return;
				}
				if (!succeed) {
					hideProgress();
					app.showToastMessage(R.string.chizu_exim_export_failed);
					return;
				}
				if (progress != null) {
					progress.setMessage(app.getString(R.string.chizu_exim_saving));
				}
				new Thread(() -> {
					boolean ok = copyToDir(file, dir, baseName + EXPORT_EXT, withChizu);
					//noinspection ResultOfMethodCallIgnored
					file.delete();
					app.runInUIThread(() -> finishExportUi(ok, baseName + EXPORT_EXT));
				}).start();
			}

			@Override
			public void onSettingsExportProgressUpdate(int value) {
				updateExportProgress(value);
			}
		};
		app.getFileSettingsHelper().exportSettings(FileUtils.getTempDir(app), baseName, listener, items, true);
	}

	/** Horizontal MB progress with a live item counter and a working Cancel. */
	private void showExportProgress(@NonNull List<SettingsItem> items) {
		hideProgress();
		FragmentActivity activity = fragment.getActivity();
		if (activity == null) {
			return;
		}
		exportTotalItems = items.size();
		exportCumBytes = new long[items.size()];
		long cum = 0;
		for (int i = 0; i < items.size(); i++) {
			SettingsItem item = items.get(i);
			if (item instanceof FileSettingsItem) {
				cum += ((FileSettingsItem) item).getSize();
			}
			exportCumBytes[i] = cum;
		}
		progress = new ProgressDialog(activity);
		progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
		progress.setMessage(itemsLine(0));
		progress.setMax(Math.max(1, (int) (cum >> 20)));
		progress.setProgressNumberFormat("%1d/%2d MB");
		progress.setCancelable(false);
		progress.setButton(DialogInterface.BUTTON_NEGATIVE,
				app.getString(R.string.shared_string_cancel), (d, which) -> cancelExport());
		progress.show();
		styleProgressWindow(progress);
		stylePillButton(progress.getButton(DialogInterface.BUTTON_NEGATIVE));
	}

	@NonNull
	private String itemsLine(int done) {
		return app.getString(R.string.chizu_exim_items_progress, done, exportTotalItems);
	}

	private void updateExportProgress(int valueMb) {
		if (progress == null) {
			return;
		}
		progress.setProgress(valueMb);
		long bytes = ((long) valueMb) << 20;
		int done = 0;
		if (exportCumBytes != null) {
			for (long boundary : exportCumBytes) {
				if (boundary <= bytes) {
					done++;
				} else {
					break;
				}
			}
		}
		progress.setMessage(itemsLine(Math.min(done, exportTotalItems)));
	}

	private void cancelExport() {
		exportCancelled = true;
		if (pendingExportFile != null) {
			app.getFileSettingsHelper().cancelExportForFile(pendingExportFile);
		}
		hideProgress();
	}

	private void finishExportUi(boolean ok, @NonNull String fileName) {
		hideProgress();
		if (exportCancelled) {
			return; // cancelled by 白い熊 — no dialogs, the panel stays open
		}
		if (!fragment.isAdded()) {
			return;
		}
		if (ok) {
			fragment.refreshPage();
			refreshPanelDirBox();
			showExportDoneDialog(fileName);
		} else {
			app.showToastMessage(R.string.chizu_exim_export_failed);
		}
	}

	@NonNull
	private String exportBaseName() {
		String version;
		try {
			version = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
		} catch (Exception e) {
			version = "unknown";
		}
		String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date());
		return EXPORT_PREFIX + version + "_export_" + stamp;
	}

	/** Streams the finished .osf into the SAF dir, appending the 白い熊 sidecar entries if wanted. */
	private boolean copyToDir(@NonNull File source, @NonNull DocumentFile dir,
			@NonNull String fileName, boolean withChizu) {
		DocumentFile dest = dir.createFile("application/octet-stream", fileName);
		if (dest == null) {
			return false;
		}
		try (OutputStream rawOut = app.getContentResolver().openOutputStream(dest.getUri())) {
			if (rawOut == null) {
				return false;
			}
			if (!withChizu) {
				try (InputStream in = new FileInputStream(source)) {
					cancellableCopy(in, rawOut);
				}
				return true;
			}
			// re-zip entry by entry so the sidecar can ride inside the same archive
			try (ZipInputStream zin = new ZipInputStream(new FileInputStream(source));
					ZipOutputStream zout = new ZipOutputStream(rawOut)) {
				zout.setLevel(Deflater.BEST_SPEED);
				ZipEntry entry;
				while ((entry = zin.getNextEntry()) != null) {
					zout.putNextEntry(new ZipEntry(entry.getName()));
					cancellableCopy(zin, zout);
					zout.closeEntry();
				}
				writeSidecarEntries(zout);
			}
			return true;
		} catch (Exception e) {
			try {
				dest.delete();
			} catch (Exception ignored) {
			}
			return false;
		}
	}

	private boolean writeSidecarOnlyArchive(@NonNull DocumentFile dir, @NonNull String baseName) {
		DocumentFile dest = dir.createFile("application/octet-stream", baseName + EXPORT_EXT);
		if (dest == null) {
			return false;
		}
		try (OutputStream rawOut = app.getContentResolver().openOutputStream(dest.getUri())) {
			if (rawOut == null) {
				return false;
			}
			try (ZipOutputStream zout = new ZipOutputStream(rawOut)) {
				writeSidecarEntries(zout);
			}
			return true;
		} catch (Exception e) {
			try {
				dest.delete();
			} catch (Exception ignored) {
			}
			return false;
		}
	}

	private void writeSidecarEntries(@NonNull ZipOutputStream zout) throws Exception {
		zout.putNextEntry(new ZipEntry(SIDECAR_ENTRY));
		zout.write(chizuPrefsJson().toString(2).getBytes("UTF-8"));
		zout.closeEntry();
		File[] fonts = ChizuFonts.getFontsDir(app).listFiles();
		if (fonts != null) {
			for (File font : fonts) {
				if (font.isFile()) {
					zout.putNextEntry(new ZipEntry(SIDECAR_FONTS_PREFIX + font.getName()));
					try (InputStream in = new FileInputStream(font)) {
						cancellableCopy(in, zout);
					}
					zout.closeEntry();
				}
			}
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
			File temp = new File(FileUtils.getTempDir(app), "chizu_import" + EXPORT_EXT);
			try (InputStream in = app.getContentResolver().openInputStream(uri);
					OutputStream out = new FileOutputStream(temp)) {
				if (in == null) {
					throw new IOException("no stream");
				}
				streamCopy(in, out);
			} catch (Exception e) {
				app.runInUIThread(() -> {
					hideProgress();
					app.showToastMessage(R.string.chizu_exim_import_failed);
				});
				return;
			}
			boolean chizuApplied = withChizu && applySidecar(temp);
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

	/** Extracts and applies the 白い熊 地図 UI sidecar; true when it was found and applied. */
	private boolean applySidecar(@NonNull File archive) {
		boolean applied = false;
		try (ZipInputStream zin = new ZipInputStream(new FileInputStream(archive))) {
			ZipEntry entry;
			while ((entry = zin.getNextEntry()) != null) {
				String name = entry.getName();
				if (SIDECAR_ENTRY.equals(name)) {
					java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
					streamCopy(zin, buffer);
					applyChizuPrefsJson(new JSONObject(buffer.toString("UTF-8")));
					applied = true;
				} else if (name.startsWith(SIDECAR_FONTS_PREFIX) && !entry.isDirectory()) {
					String fontName = name.substring(SIDECAR_FONTS_PREFIX.length());
					if (!fontName.isEmpty() && !fontName.contains("/") && !fontName.contains("..")) {
						File target = new File(ChizuFonts.getFontsDir(app), fontName);
						try (OutputStream out = new FileOutputStream(target)) {
							streamCopy(zin, out);
						}
						applied = true;
					}
				}
				zin.closeEntry();
			}
		} catch (Exception e) {
			return applied;
		}
		return applied;
	}

	// ---------- the 白い熊 地図 UI sidecar payload ----------

	@NonNull
	private JSONObject chizuPrefsJson() throws Exception {
		JSONObject json = new JSONObject();
		Map<String, ?> all = app.getSharedPreferences(ChizuTheme.PREFS_NAME, Context.MODE_PRIVATE).getAll();
		for (Map.Entry<String, ?> entry : all.entrySet()) {
			Object value = entry.getValue();
			JSONObject typed = new JSONObject();
			if (value instanceof Boolean) {
				typed.put("t", "b").put("v", value);
			} else if (value instanceof Integer) {
				typed.put("t", "i").put("v", value);
			} else if (value instanceof Long) {
				typed.put("t", "l").put("v", value);
			} else if (value instanceof Float) {
				typed.put("t", "f").put("v", ((Float) value).doubleValue());
			} else if (value instanceof String) {
				typed.put("t", "s").put("v", value);
			} else if (value instanceof Set) {
				typed.put("t", "ss").put("v", new JSONArray((Set<?>) value));
			} else {
				continue;
			}
			json.put(entry.getKey(), typed);
		}
		return json;
	}

	private void applyChizuPrefsJson(@NonNull JSONObject json) throws Exception {
		SharedPreferences.Editor editor =
				app.getSharedPreferences(ChizuTheme.PREFS_NAME, Context.MODE_PRIVATE).edit();
		java.util.Iterator<String> keys = json.keys();
		while (keys.hasNext()) {
			String key = keys.next();
			JSONObject typed = json.getJSONObject(key);
			String type = typed.getString("t");
			switch (type) {
				case "b":
					editor.putBoolean(key, typed.getBoolean("v"));
					break;
				case "i":
					editor.putInt(key, typed.getInt("v"));
					break;
				case "l":
					editor.putLong(key, typed.getLong("v"));
					break;
				case "f":
					editor.putFloat(key, (float) typed.getDouble("v"));
					break;
				case "s":
					editor.putString(key, typed.getString("v"));
					break;
				case "ss":
					JSONArray array = typed.getJSONArray("v");
					Set<String> set = new HashSet<>();
					for (int i = 0; i < array.length(); i++) {
						set.add(array.getString(i));
					}
					editor.putStringSet(key, set);
					break;
			}
		}
		editor.apply();
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

	/** Stream copy that aborts (throws) as soon as the export is cancelled. */
	private void cancellableCopy(@NonNull InputStream in, @NonNull OutputStream out) throws IOException {
		byte[] buffer = new byte[8192];
		int read;
		while ((read = in.read(buffer)) != -1) {
			if (exportCancelled) {
				throw new IOException("export cancelled");
			}
			out.write(buffer, 0, read);
		}
	}

	// ---------- small helpers ----------

	private static void streamCopy(@NonNull InputStream in, @NonNull OutputStream out) throws IOException {
		byte[] buffer = new byte[8192];
		int read;
		while ((read = in.read(buffer)) != -1) {
			out.write(buffer, 0, read);
		}
	}

	private int color(ChizuTheme.Slot slot) {
		return ChizuTheme.getColor(app, slot);
	}

	private int dp(float value) {
		return (int) (value * app.getResources().getDisplayMetrics().density + 0.5f);
	}
}
