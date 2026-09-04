package net.osmand.plus.chizu;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.ExportCategory;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.plus.settings.backend.backup.FileSettingsHelper;
import net.osmand.plus.settings.backend.backup.FileSettingsHelper.SettingsExportListener;
import net.osmand.plus.settings.backend.backup.SettingsHelper.CollectListener;
import net.osmand.plus.settings.backend.backup.SettingsHelper.ImportListener;
import net.osmand.plus.settings.backend.backup.exporttype.ExportType;
import net.osmand.plus.settings.backend.backup.items.FileSettingsItem;
import net.osmand.plus.settings.backend.backup.items.SettingsItem;
import net.osmand.plus.utils.FileUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * shiroikuma fork: the backup core — one archive holding everything settable in the app.
 *
 * Callable headlessly: the 白い熊 地図 UI panel ({@link ChizuExim}) and the automation
 * receiver ({@link ChizuStateExportReceiver}) are two thin callers of {@link #export}.
 * The archive is the stock .osf settings ZIP (all selected stock export types, maps
 * included) plus the 白い熊 地図 UI sidecar (colors, fonts, sizes and the imported font
 * files) as extra entries inside the very same ZIP — one file per backup, always.
 *
 * The backup directory and the automation token live in their own device-local prefs
 * file ({@link #PREFS_NAME}), which is never part of any export.
 */
public class ChizuBackup {

	/** Device-local prefs: backup directory + automation token. Never exported. */
	static final String PREFS_NAME = "chizu_exim";
	private static final String KEY_DIR_URI = "dir_uri";

	/** Family convention (白い熊, 2026-07-25): {@code <english-app-name>_<stamp>.zip}. */
	public static final String EXPORT_PREFIX = "shiroikuma-chizu_";
	public static final String EXPORT_EXT = ".zip";
	/** Extension of backups written before the family convention landed. */
	public static final String LEGACY_EXT = ".osf";

	private static final String SIDECAR_ENTRY = "chizu_ui.json";
	private static final String SIDECAR_FONTS_PREFIX = "chizu_fonts/";

	/** Category id of the 白い熊 地図 UI sidecar (sub-option of the Settings group). */
	public static final String ID_CHIZU_UI = "settings.chizu_ui";
	/** Category id of the maps group — a group of its own, as in the Export/Import panel. */
	public static final String GROUP_MAPS = "maps";

	/**
	 * The non-map categories that start <b>unticked</b> in every picker: downloaded voice
	 * packages, re-obtainable from OsmAnd's own servers. Everything under {@link #GROUP_MAPS}
	 * starts unticked too (gigabytes, equally re-downloadable) — that one is structural, see
	 * {@link #catalogue}. All the rest is authored and cannot be re-obtained, so it starts ticked.
	 */
	private static final Set<String> UNTICKED_BY_DEFAULT =
			new HashSet<>(Arrays.asList("resources.tts_voice", "resources.voice"));

	private static final String UNIT_BYTES = "bytes";
	private static final String UNIT_CATEGORIES = "categories";
	/** Progress is reported at most once per this many bytes written. */
	private static final long PROGRESS_STEP_BYTES = 1L << 20;

	private ChizuBackup() {
	}

	// ---------- device-local prefs (backup directory) ----------

	@NonNull
	static SharedPreferences prefs(@NonNull Context context) {
		return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
	}

	@Nullable
	public static Uri getDirUri(@NonNull Context context) {
		String stored = prefs(context).getString(KEY_DIR_URI, null);
		if (stored == null) {
			return null;
		}
		try {
			return Uri.parse(stored);
		} catch (Exception e) {
			return null;
		}
	}

	public static void setDirUri(@NonNull Context context, @NonNull Uri uri) {
		prefs(context).edit().putString(KEY_DIR_URI, uri.toString()).apply();
	}

	/** The configured backup directory, or null when none is set / it no longer resolves. */
	@Nullable
	public static DocumentFile getDir(@NonNull Context context) {
		Uri uri = getDirUri(context);
		if (uri == null) {
			return null;
		}
		try {
			DocumentFile dir = DocumentFile.fromTreeUri(context, uri);
			return dir != null && dir.isDirectory() ? dir : null;
		} catch (Exception e) {
			return null;
		}
	}

	// ---------- naming ----------

	@NonNull
	public static String fileName() {
		String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date());
		return EXPORT_PREFIX + stamp + EXPORT_EXT;
	}

	/** True for anything this app ever wrote as a backup (new .zip and legacy .osf names). */
	public static boolean isExportName(@Nullable String name) {
		return name != null && name.startsWith(EXPORT_PREFIX)
				&& (name.endsWith(EXPORT_EXT) || name.endsWith(LEGACY_EXT));
	}

	// ---------- the category catalogue ----------

	/** One selectable category: a group row (type == null, parent == null) or one of its parts. */
	public static class Cat {

		public final String id;
		public final String label;
		@Nullable
		public final String parent;
		@Nullable
		public final ExportType type;
		/** Whether a picker starts this one ticked — the contract's optional fourth field. */
		public final boolean defaultSelected;

		Cat(@NonNull String id, @NonNull String label, @Nullable String parent, @Nullable ExportType type) {
			this(id, label, parent, type, true);
		}

		Cat(@NonNull String id, @NonNull String label, @Nullable String parent, @Nullable ExportType type,
				boolean defaultSelected) {
			this.id = id;
			this.label = label;
			this.parent = parent;
			this.type = type;
			this.defaultSelected = defaultSelected;
		}
	}

	/**
	 * Every exportable category, groups first and each group's parts right below it —
	 * exactly the tree the Export/Import panel shows.
	 */
	@NonNull
	public static List<Cat> catalogue(@NonNull OsmandApplication app) {
		List<Cat> list = new ArrayList<>();

		list.add(new Cat(GROUP_MAPS, app.getString(R.string.chizu_exim_maps), null, null, false));
		for (ExportType type : ExportType.mapValues()) {
			if (type.isAvailable() && !type.isHidden()) {
				list.add(new Cat(GROUP_MAPS + "." + idOf(type), type.getTitle(app), GROUP_MAPS, type, false));
			}
		}
		for (ExportCategory category : ExportCategory.values()) {
			String group = category.name().toLowerCase(Locale.ROOT);
			list.add(new Cat(group, app.getString(category.getTitleId()), null, null));
			if (category == ExportCategory.SETTINGS) {
				list.add(new Cat(ID_CHIZU_UI, app.getString(R.string.chizu_exim_chizu_ui), group, null));
			}
			for (ExportType type : ExportType.availableValuesOf(category)) {
				if (!type.isMap() && !type.isHidden()) {
					String id = group + "." + idOf(type);
					list.add(new Cat(id, type.getTitle(app), group, type, !UNTICKED_BY_DEFAULT.contains(id)));
				}
			}
		}
		return list;
	}

	/** Whether the category with this id starts ticked; an id we do not know starts ticked. */
	public static boolean startsTicked(@NonNull List<Cat> catalogue, @NonNull String id) {
		Cat cat = find(catalogue, id);
		return cat == null || cat.defaultSelected;
	}

	/** The same answer for one stock export type — what the in-app picker seeds its rows from. */
	public static boolean startsTicked(@NonNull List<Cat> catalogue, @NonNull ExportType type) {
		for (Cat cat : catalogue) {
			if (cat.type == type) {
				return cat.defaultSelected;
			}
		}
		return true;
	}

	@NonNull
	private static String idOf(@NonNull ExportType type) {
		return type.name().toLowerCase(Locale.ROOT);
	}

	@Nullable
	private static Cat find(@NonNull List<Cat> catalogue, @NonNull String id) {
		for (Cat cat : catalogue) {
			if (cat.id.equals(id)) {
				return cat;
			}
		}
		return null;
	}

	/** What one request asked for, resolved onto stock export types + the 白い熊 sidecar. */
	public static class Selection {

		public final List<ExportType> types;
		public final boolean withChizu;
		/** Number of leaf categories selected — what the reply reports as "<n> categories". */
		public final int count;

		public Selection(@NonNull List<ExportType> types, boolean withChizu, int count) {
			this.types = types;
			this.withChizu = withChizu;
			this.count = count;
		}
	}

	/**
	 * Resolves a comma-separated {@code items} list. Null/empty selects the default set —
	 * every part that starts ticked, which is everything but the maps and the voice packages.
	 * A group id selects all of its parts, ticked by default or not (the groups carry no data
	 * of their own): naming one is an explicit ask, not a default. Returns null when an id is
	 * not in the catalogue.
	 */
	@Nullable
	public static Selection select(@NonNull OsmandApplication app, @Nullable String items) {
		List<Cat> catalogue = catalogue(app);
		Set<String> wanted = new LinkedHashSet<>();
		if (items == null || items.trim().isEmpty()) {
			for (Cat cat : catalogue) {
				if (cat.parent != null && cat.defaultSelected) {
					wanted.add(cat.id);
				}
			}
		} else {
			for (String raw : items.split(",")) {
				String id = raw.trim().toLowerCase(Locale.ROOT);
				if (id.isEmpty()) {
					continue;
				}
				Cat cat = find(catalogue, id);
				if (cat == null) {
					return null;
				}
				if (cat.parent == null) {
					for (Cat child : catalogue) {
						if (id.equals(child.parent)) {
							wanted.add(child.id);
						}
					}
				} else {
					wanted.add(cat.id);
				}
			}
		}
		List<ExportType> types = new ArrayList<>();
		boolean withChizu = false;
		for (String id : wanted) {
			Cat cat = find(catalogue, id);
			if (cat == null) {
				continue;
			}
			if (cat.type != null) {
				types.add(cat.type);
			} else if (ID_CHIZU_UI.equals(cat.id)) {
				withChizu = true;
			}
		}
		return new Selection(types, withChizu, wanted.size());
	}

	// ---------- destinations ----------

	/** Where one backup goes: a plain file (All-files access) or a SAF document. */
	public interface Dest {

		@NonNull
		OutputStream open() throws IOException;

		/** Byte length of what was written — queried after the stream is closed. */
		long length();

		void delete();

		/** What the reply reports as the written path. */
		@NonNull
		String path();
	}

	public static class FileDest implements Dest {

		private final File file;

		public FileDest(@NonNull File file) {
			this.file = file;
		}

		@NonNull
		@Override
		public OutputStream open() throws IOException {
			File parent = file.getParentFile();
			if (parent != null && !parent.exists() && !parent.mkdirs()) {
				throw new IOException("cannot create " + parent.getAbsolutePath());
			}
			return new FileOutputStream(file);
		}

		@Override
		public long length() {
			return file.length();
		}

		@Override
		public void delete() {
			//noinspection ResultOfMethodCallIgnored
			file.delete();
		}

		@NonNull
		@Override
		public String path() {
			return file.getAbsolutePath();
		}
	}

	public static class SafDest implements Dest {

		private final Context context;
		private final DocumentFile dir;
		private final String name;
		private DocumentFile created;

		public SafDest(@NonNull Context context, @NonNull DocumentFile dir, @NonNull String name) {
			this.context = context;
			this.dir = dir;
			this.name = name;
		}

		@NonNull
		@Override
		public OutputStream open() throws IOException {
			created = dir.createFile("application/zip", name);
			if (created == null) {
				throw new IOException("cannot create " + name);
			}
			OutputStream out = context.getContentResolver().openOutputStream(created.getUri());
			if (out == null) {
				throw new IOException("cannot write " + name);
			}
			return out;
		}

		@Override
		public long length() {
			return created != null ? created.length() : 0;
		}

		@Override
		public void delete() {
			if (created != null) {
				try {
					created.delete();
				} catch (Exception ignored) {
				}
			}
		}

		/** A real filesystem path where the document id gives one — that is what 白い熊 restores from. */
		@NonNull
		@Override
		public String path() {
			if (created == null) {
				return name;
			}
			try {
				String documentId = DocumentsContract.getDocumentId(created.getUri());
				int colon = documentId.indexOf(':');
				if (colon > 0) {
					String volume = documentId.substring(0, colon);
					String rest = documentId.substring(colon + 1);
					if ("primary".equalsIgnoreCase(volume)) {
						return new File(Environment.getExternalStorageDirectory(), rest).getAbsolutePath();
					}
					return "/storage/" + volume + "/" + rest;
				}
			} catch (Exception ignored) {
			}
			return created.getUri().toString();
		}

		@NonNull
		public String fileName() {
			return name;
		}
	}

	/**
	 * The data door's destination: bytes go straight into a descriptor the caller opened.
	 *
	 * <p>Not a path and not a {@code content://} URI, because a backup is not a stable directory
	 * while it is being written. 応用管理 writes into a temporary path and renames on commit, and
	 * encrypts and checksums <b>per file it knows about</b> — so a file this app dropped in itself
	 * would be renamed out from under it, would sit in plaintext inside an otherwise encrypted
	 * backup, and would be unverified rather than verified-and-failing. A descriptor is also a
	 * capability that <b>expires when it is closed</b>, which is precisely the property a URI grant
	 * could not give us on the walk contract, where the revoke needed a five-minute floor.
	 *
	 * <p>{@link #length()} is counted on the way past rather than stat'ed afterwards: the caller
	 * owns the file and we may not be able to see it at all — it can be an anonymous pipe, or a
	 * descriptor into a directory this app cannot list.
	 */
	public static class FdDest implements Dest {

		private final ParcelFileDescriptor fd;
		@Nullable
		private CountingStream stream;

		public FdDest(@NonNull ParcelFileDescriptor fd) {
			this.fd = fd;
		}

		@NonNull
		@Override
		public OutputStream open() {
			CountingStream counting =
					new CountingStream(new ParcelFileDescriptor.AutoCloseOutputStream(fd));
			stream = counting;
			return counting;
		}

		@Override
		public long length() {
			return stream != null ? stream.written : 0;
		}

		/**
		 * Nothing to delete — the caller owns the file. A cancelled or failed export answers
		 * {@code ERROR:} and the caller discards what it opened; this app never had a name for it.
		 */
		@Override
		public void delete() {
		}

		@NonNull
		@Override
		public String path() {
			return "(descriptor)";
		}
	}

	/** Counts bytes on their way into the caller's descriptor. */
	private static class CountingStream extends OutputStream {

		private final OutputStream out;
		private long written;

		CountingStream(@NonNull OutputStream out) {
			this.out = out;
		}

		@Override
		public void write(int b) throws IOException {
			out.write(b);
			written++;
		}

		@Override
		public void write(@NonNull byte[] b, int off, int len) throws IOException {
			out.write(b, off, len);
			written += len;
		}

		@Override
		public void flush() throws IOException {
			out.flush();
		}

		@Override
		public void close() throws IOException {
			out.close();
		}
	}

	// ---------- progress + result ----------

	/** Real numbers, never a percentage: {@code text} is what a caller displays. */
	public interface Progress {
		void onProgress(long current, long total, @NonNull String unit, @NonNull String text);
	}

	public static class Result {

		public final boolean ok;
		@Nullable
		public final String error;
		@NonNull
		public final String path;
		public final long bytes;
		public final int categories;

		private Result(boolean ok, @Nullable String error, @NonNull String path, long bytes, int categories) {
			this.ok = ok;
			this.error = error;
			this.path = path;
			this.bytes = bytes;
			this.categories = categories;
		}

		static Result error(@NonNull String message) {
			return new Result(false, message, "", 0, 0);
		}

		static Result ok(@NonNull String path, long bytes, int categories) {
			return new Result(true, null, path, bytes, categories);
		}
	}

	// ---------- the running export (never two at once) ----------

	private static final Object RUN_LOCK = new Object();
	@Nullable
	private static String runId;
	@Nullable
	private static AtomicBoolean runCancelled;

	/**
	 * Raises the cancel flag of the export currently running, so it unwinds at the next entry
	 * boundary and deletes everything it had already written. A null/blank {@code id} means
	 * "whatever is running" — unambiguous, since two exports at once are forbidden; a named id
	 * must be the one running. A silent no-op when nothing is running: no error, no crash.
	 *
	 * This is the one way to unwind an export — the panel's Cancel button and the automation
	 * contract's CANCEL_EXPORT both end here, at the flag {@link #export} polls.
	 */
	public static void cancelRunning(@Nullable String id) {
		synchronized (RUN_LOCK) {
			if (runCancelled == null) {
				return;
			}
			if (id != null && !id.trim().isEmpty() && runId != null && !id.equals(runId)) {
				return;
			}
			runCancelled.set(true);
		}
	}

	/**
	 * Publishes the flag {@link #cancelRunning} raises. Call it around the <b>whole</b>
	 * request, not just around {@link #export} — a cold-started process spends its first
	 * seconds waiting for the app to initialize, and a cancel arriving then must still land.
	 * {@code id} is the automation request's reply_id, null for the in-app panel. Always
	 * paired with {@link #endRun} in a finally.
	 */
	public static void beginRun(@Nullable String id, @NonNull AtomicBoolean cancelled) {
		synchronized (RUN_LOCK) {
			runId = id;
			runCancelled = cancelled;
		}
	}

	public static void endRun(@NonNull AtomicBoolean cancelled) {
		synchronized (RUN_LOCK) {
			if (runCancelled == cancelled) {
				runId = null;
				runCancelled = null;
			}
		}
	}

	// ---------- the export core ----------

	/**
	 * Writes one backup archive. Blocking — never call it on the main thread; the stock
	 * export task it drives reports back there. {@code cancelled} is polled between entries
	 * and unwinds the run, deleting everything already written; callers publish it through
	 * {@link #beginRun} so a cancel can reach it from outside.
	 */
	@NonNull
	public static Result export(@NonNull OsmandApplication app, @NonNull Selection selection,
			@NonNull Dest dest, @Nullable Progress progress, @NonNull AtomicBoolean cancelled) {
		if (selection.count == 0) {
			return Result.error("no categories selected");
		}
		report(progress, 0, selection.count, UNIT_CATEGORIES,
				"Preparing 0/" + selection.count + " categories");

		List<SettingsItem> items;
		try {
			items = selection.types.isEmpty()
					? new ArrayList<>()
					: app.getFileSettingsHelper().getFilteredSettingsItems(selection.types, true, false, false);
		} catch (Exception e) {
			return Result.error("collect failed");
		}
		if (cancelled.get()) {
			return Result.error("cancelled");
		}

		if (items.isEmpty()) {
			// nothing but the 白い熊 地図 UI sidecar — write the archive directly
			try (OutputStream out = dest.open()) {
				ZipOutputStream zout = new ZipOutputStream(out);
				writeSidecarEntries(app, zout, cancelled);
				zout.finish();
			} catch (Exception e) {
				// a cancelled run leaves the backup directory exactly as it found it
				dest.delete();
				return Result.error(cancelled.get() ? "cancelled" : "write failed");
			}
			long written = dest.length();
			report(progress, written, written, UNIT_BYTES, formatSize(written) + " / " + formatSize(written));
			return Result.ok(dest.path(), written, selection.count);
		}

		long totalBytes = 0;
		for (SettingsItem item : items) {
			if (item instanceof FileSettingsItem) {
				totalBytes += ((FileSettingsItem) item).getSize();
			}
		}
		File tempDir = FileUtils.getTempDir(app);
		String base = "chizu_backup_" + System.currentTimeMillis();
		File temp = new File(tempDir, base + LEGACY_EXT);

		Result collected = collectToTempFile(app, items, tempDir, base, temp, totalBytes, progress, cancelled);
		if (collected != null) {
			return collected;
		}

		long tempLength = temp.length();
		long[] copied = {0, 0};
		// the plain copy moves archive bytes, the re-zip moves the entries' own (uncompressed) bytes
		long copyTotal = selection.withChizu && totalBytes > 0 ? totalBytes : tempLength;
		try (OutputStream raw = dest.open()) {
			if (!selection.withChizu) {
				try (InputStream in = new FileInputStream(temp)) {
					copy(in, raw, progress, copied, copyTotal, cancelled);
				}
			} else {
				// re-zip entry by entry so the sidecar rides inside the same archive
				try (ZipInputStream zin = new ZipInputStream(new FileInputStream(temp))) {
					ZipOutputStream zout = new ZipOutputStream(raw);
					zout.setLevel(Deflater.BEST_SPEED);
					ZipEntry entry;
					while ((entry = zin.getNextEntry()) != null) {
						zout.putNextEntry(new ZipEntry(entry.getName()));
						copy(zin, zout, progress, copied, copyTotal, cancelled);
						zout.closeEntry();
					}
					writeSidecarEntries(app, zout, cancelled);
					zout.finish();
				}
			}
		} catch (Exception e) {
			// a cancelled run leaves the backup directory exactly as it found it: the
			// half-written archive goes with the temp file, in the very same unwind
			//noinspection ResultOfMethodCallIgnored
			temp.delete();
			dest.delete();
			return Result.error(cancelled.get() ? "cancelled" : "write failed");
		}
		//noinspection ResultOfMethodCallIgnored
		temp.delete();

		long written = dest.length();
		report(progress, written, written, UNIT_BYTES, formatSize(written) + " / " + formatSize(written));
		return Result.ok(dest.path(), written, selection.count);
	}

	/** Runs the stock export task into the temp file; returns null on success, a failure otherwise. */
	@Nullable
	private static Result collectToTempFile(@NonNull OsmandApplication app, @NonNull List<SettingsItem> items,
			@NonNull File tempDir, @NonNull String base, @NonNull File temp, long totalBytes,
			@Nullable Progress progress, @NonNull AtomicBoolean cancelled) {
		boolean[] succeeded = {false};
		CountDownLatch latch = new CountDownLatch(1);
		SettingsExportListener listener = new SettingsExportListener() {
			@Override
			public void onSettingsExportFinished(@NonNull File file, boolean succeed) {
				succeeded[0] = succeed;
				latch.countDown();
			}

			@Override
			public void onSettingsExportProgressUpdate(int valueMb) {
				long done = Math.min(((long) valueMb) << 20, totalBytes);
				report(progress, done, totalBytes, UNIT_BYTES,
						formatSize(done) + " / " + formatSize(totalBytes));
			}
		};
		// the stock export task is an AsyncTask — start it from the main thread
		app.runInUIThread(() -> app.getFileSettingsHelper()
				.exportSettings(tempDir, base, listener, items, true));
		boolean cancelSent = false;
		long cancelDeadline = 0;
		try {
			while (!latch.await(200, TimeUnit.MILLISECONDS)) {
				if (!cancelled.get()) {
					continue;
				}
				if (!cancelSent) {
					cancelSent = true;
					app.getFileSettingsHelper().cancelExportForFile(temp);
					cancelDeadline = SystemClock.elapsedRealtime() + 3000;
				} else if (SystemClock.elapsedRealtime() > cancelDeadline) {
					// a cancelled task reports to onCancelled(), never to the listener
					break;
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			//noinspection ResultOfMethodCallIgnored
			temp.delete();
			return Result.error("interrupted");
		}
		if (cancelled.get()) {
			//noinspection ResultOfMethodCallIgnored
			temp.delete();
			return Result.error("cancelled");
		}
		if (!succeeded[0]) {
			//noinspection ResultOfMethodCallIgnored
			temp.delete();
			return Result.error("export failed");
		}
		return null;
	}

	private static void report(@Nullable Progress progress, long current, long total,
			@NonNull String unit, @NonNull String text) {
		if (progress != null) {
			progress.onProgress(current, total, unit, text);
		}
	}

	/** copied[0] = bytes so far, copied[1] = bytes at the last progress report. */
	private static void copy(@NonNull InputStream in, @NonNull OutputStream out,
			@Nullable Progress progress, @NonNull long[] copied, long total,
			@NonNull AtomicBoolean cancelled) throws IOException {
		byte[] buffer = new byte[8192];
		int read;
		while ((read = in.read(buffer)) != -1) {
			if (cancelled.get()) {
				throw new IOException("cancelled");
			}
			out.write(buffer, 0, read);
			copied[0] += read;
			if (total > 0 && copied[0] - copied[1] >= PROGRESS_STEP_BYTES) {
				copied[1] = copied[0];
				long done = Math.min(copied[0], total);
				report(progress, done, total, UNIT_BYTES,
						"Writing " + formatSize(done) + " / " + formatSize(total));
			}
		}
	}

	static void streamCopy(@NonNull InputStream in, @NonNull OutputStream out) throws IOException {
		byte[] buffer = new byte[8192];
		int read;
		while ((read = in.read(buffer)) != -1) {
			out.write(buffer, 0, read);
		}
	}

	// ---------- the headless import (the data door's restore half) ----------

	/** How long one headless import may take before it is called a failure rather than waited on. */
	private static final long IMPORT_TIMEOUT_MS = 15 * 60 * 1000L;

	/**
	 * Restores one archive written by {@link #export}, with no UI and no user interaction.
	 *
	 * <p>Reached only from {@link ChizuAutomationProvider}. An import overwrites this app's data,
	 * and the automation receivers are exported without a permission — an import there would let
	 * any app on the phone wipe 地図, which is why the contract puts it behind the door that knows
	 * who is calling.
	 *
	 * <p>Everything the archive carries is restored: the caller chose what to put in it, and a
	 * restore that silently dropped part of a backup is worse than one that refused. Items are
	 * marked {@code shouldReplace} because a restore is a restore, not a merge.
	 *
	 * <p>The 白い熊 地図 sidecar alone is a real restore — an archive of nothing but UI settings
	 * collects no stock items, and reporting that as a failure would fail every restore of an app
	 * whose only customisation is ours.
	 *
	 * <p>{@link Result#categories} is how many items were restored; {@link Result#path} is empty,
	 * since the archive belongs to the caller and has no path of ours.
	 */
	@NonNull
	public static Result importArchive(@NonNull OsmandApplication app, @NonNull File archive) {
		boolean chizuApplied = applySidecar(app, archive);

		CountDownLatch latch = new CountDownLatch(1);
		boolean[] ok = {false};
		int[] restored = {0};
		// both stock helpers are AsyncTasks — start them from the main thread, as the panel does
		app.runInUIThread(() -> {
			FileSettingsHelper helper = app.getFileSettingsHelper();
			CollectListener collectListener = (succeed, empty, items) -> {
				if (!succeed || empty || items == null || items.isEmpty()) {
					latch.countDown();
					return;
				}
				List<SettingsItem> selected = new ArrayList<>();
				for (SettingsItem item : items) {
					item.setShouldReplace(true);
					selected.add(item);
				}
				restored[0] = selected.size();
				helper.importSettings(archive, selected, "", 1, new ImportListener() {
					@Override
					public void onImportFinished(boolean importOk, boolean needRestart,
							@NonNull List<SettingsItem> finishedItems) {
						ok[0] = importOk;
						latch.countDown();
					}
				});
			};
			helper.collectSettings(archive, "", 1, collectListener);
		});
		try {
			// bounded: a heartbeat keeps the caller waiting, so a step that can block must have an
			// end — an import that hangs while still ticking holds its slot until the full timeout
			if (!latch.await(IMPORT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
				return Result.error("import timed out");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Result.error("interrupted");
		}
		if (!ok[0] && !chizuApplied) {
			return Result.error("import failed");
		}
		// Everything on disk BEFORE the caller is told it worked — see flushPreferences.
		flushPreferences(app);
		int count = (ok[0] ? restored[0] : 0) + (chizuApplied ? 1 : 0);
		return Result.ok("", archive.length(), count);
	}

	/**
	 * Force every preferences file this app writes out to disk, synchronously.
	 *
	 * <p>応用管理 force-stops this app with a <b>SIGKILL</b> the instant an import reports success.
	 * It has to: a live process writes its cached {@link SharedPreferences} back out at orderly
	 * shutdown and would silently undo the import that just happened. But a SIGKILL runs no
	 * shutdown hook, so anything the framework still has queued behind us dies with the process —
	 * and the restore would be half-applied with nobody the wiser.
	 *
	 * <p>An empty {@code commit()} per file blocks until that queue is on disk. It is done for
	 * every file this app writes, not only the ones we wrote ourselves: the stock import walks
	 * OsmAnd's own global and per-mode preferences, and those are queued by code we do not own.
	 */
	private static void flushPreferences(@NonNull OsmandApplication app) {
		Set<String> names = new LinkedHashSet<>();
		names.add(PREFS_NAME);
		names.add(ChizuTheme.PREFS_NAME);
		try {
			names.add(OsmandSettings.getSharedPreferencesName(null));
			for (ApplicationMode mode : ApplicationMode.allPossibleValues()) {
				names.add(OsmandSettings.getSharedPreferencesName(mode));
			}
		} catch (Exception ignored) {
			// a mode list we cannot read is not a reason to skip the files we can
		}
		for (String name : names) {
			try {
				//noinspection ApplySharedPref
				app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().commit();
			} catch (Exception ignored) {
			}
		}
	}

	// ---------- the 白い熊 地図 UI sidecar ----------

	private static void writeSidecarEntries(@NonNull OsmandApplication app,
			@NonNull ZipOutputStream zout, @NonNull AtomicBoolean cancelled) throws Exception {
		zout.putNextEntry(new ZipEntry(SIDECAR_ENTRY));
		zout.write(chizuPrefsJson(app).toString(2).getBytes("UTF-8"));
		zout.closeEntry();
		File[] fonts = ChizuFonts.getFontsDir(app).listFiles();
		if (fonts != null) {
			for (File font : fonts) {
				if (font.isFile()) {
					if (cancelled.get()) {
						throw new IOException("cancelled");
					}
					zout.putNextEntry(new ZipEntry(SIDECAR_FONTS_PREFIX + font.getName()));
					try (InputStream in = new FileInputStream(font)) {
						streamCopy(in, zout);
					}
					zout.closeEntry();
				}
			}
		}
	}

	/** Extracts and applies the 白い熊 地図 UI sidecar; true when it was found and applied. */
	static boolean applySidecar(@NonNull OsmandApplication app, @NonNull File archive) {
		boolean applied = false;
		try (ZipInputStream zin = new ZipInputStream(new FileInputStream(archive))) {
			ZipEntry entry;
			while ((entry = zin.getNextEntry()) != null) {
				String name = entry.getName();
				if (SIDECAR_ENTRY.equals(name)) {
					ByteArrayOutputStream buffer = new ByteArrayOutputStream();
					streamCopy(zin, buffer);
					applyChizuPrefsJson(app, new JSONObject(buffer.toString("UTF-8")));
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

	@NonNull
	private static JSONObject chizuPrefsJson(@NonNull OsmandApplication app) throws Exception {
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

	private static void applyChizuPrefsJson(@NonNull OsmandApplication app, @NonNull JSONObject json)
			throws Exception {
		SharedPreferences.Editor editor =
				app.getSharedPreferences(ChizuTheme.PREFS_NAME, Context.MODE_PRIVATE).edit();
		Iterator<String> keys = json.keys();
		while (keys.hasNext()) {
			String key = keys.next();
			JSONObject typed = json.getJSONObject(key);
			switch (typed.getString("t")) {
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
		// commit(), not apply(): the caller SIGKILLs this app the moment the import reports success
		//noinspection ApplySharedPref
		editor.commit();
	}

	// ---------- display ----------

	/** Human size for the reply line: {@code 4.6 MB}, {@code 1.20 GB}. */
	@NonNull
	public static String formatSize(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		double kb = bytes / 1024.0;
		if (kb < 1024) {
			return String.format(Locale.US, "%.1f KB", kb);
		}
		double mb = kb / 1024.0;
		if (mb < 1024) {
			return String.format(Locale.US, "%.1f MB", mb);
		}
		return String.format(Locale.US, "%.2f GB", mb / 1024.0);
	}
}
