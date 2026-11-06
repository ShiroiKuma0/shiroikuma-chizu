package net.osmand.plus.chizu;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import net.osmand.plus.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * 白い熊 地図: UI → Diagnostics. Every diagnostic 地図 writes sits behind its own switch, all OFF by
 * default — off means the line is never written (and, on the hot paths, never formatted). The same
 * contract as 白い熊 Jami's and 白い熊 考直's Diagnostics.
 *
 * Everything goes to “/sdcard/Android/data/shiroikuma.chizu/files/diag/” (the app's own data
 * directory, readable with adb): the drive log “chizu-drive_<day>.txt” (not “.log”: EMUI’s nightly
 * clean-up deletes those as junk), whose lines are sorted into
 * the groups below by their first word, and the road-section log “chizu-roads_<day>.tsv”.
 */
public final class ChizuDiag {

	private static final String PREFS = "chizu_diag";
	public static final String DIR = "diag";
	static final String DRIVE_PREFIX = "chizu-drive_";
	static final String ROADS_PREFIX = "chizu-roads_";
	// EMUI's nightly clean-up (Phone Manager, while charging) deletes "*.log" under Android/data as junk
	static final String DRIVE_EXT = ".txt";
	static final String OLD_DRIVE_EXT = ".log";
	private static final String KEY_EXPORT_TREE = "export_tree";
	private static final String KEY_KEEP_DAYS = "keep_days";
	private static final String CHANNEL = "chizu_diag";
	private static final int NOTIFICATION_ID = 0x0C1D1A6;
	public static final String ACTION_MARK = "shiroikuma.chizu.action.DIAG_MARK";
	public static final String ACTION_ALL_OFF = "shiroikuma.chizu.action.DIAG_ALL_OFF";
	private static int marks;

	/** A switchable group of the drive log, or the road log. */
	public enum Group {
		LOCATION("loc", R.string.chizu_diag_location, R.string.chizu_diag_location_descr),
		GNSS("gnss", R.string.chizu_diag_gnss, R.string.chizu_diag_gnss_descr),
		OFF_ROUTE("chk", R.string.chizu_diag_off_route, R.string.chizu_diag_off_route_descr),
		ROUTING("routing", R.string.chizu_diag_routing, R.string.chizu_diag_routing_descr),
		TRAFFIC("traffic", R.string.chizu_diag_traffic, R.string.chizu_diag_traffic_descr),
		VOICE("voice", R.string.chizu_diag_voice, R.string.chizu_diag_voice_descr),
		MAP("map", R.string.chizu_diag_map, R.string.chizu_diag_map_descr),
		CAR("car", R.string.chizu_diag_car, R.string.chizu_diag_car_descr),
		UI("ui", R.string.chizu_diag_ui, R.string.chizu_diag_ui_descr),
		PERF("perf", R.string.chizu_diag_perf, R.string.chizu_diag_perf_descr),
		ROADS("roads", R.string.chizu_diag_roads, R.string.chizu_diag_roads_descr);

		public final String key;
		public final int titleRes;
		public final int descrRes;

		Group(String key, int titleRes, int descrRes) {
			this.key = key;
			this.titleRes = titleRes;
			this.descrRes = descrRes;
		}
	}

	private static final boolean[] on = new boolean[Group.values().length];
	private static volatile boolean anyOn;
	private static volatile Context app;
	/** Raised whenever files are moved in or purged: the writers reopen their file on the next line. */
	private static volatile int generation;

	private ChizuDiag() {
	}

	/** Load the switches and move old logs in. Call once, before the logs' own init. */
	public static void init(@NonNull Context context) {
		app = context.getApplicationContext();
		SharedPreferences p = prefs(app);
		for (Group g : Group.values()) {
			on[g.ordinal()] = p.getBoolean(g.key, false);
		}
		updateAny();
		updateNotification(app);
		Thread t = new Thread(() -> {
			migrate(app);
			cleanup(app);
		}, "chizu-diag-migrate");
		t.setDaemon(true);
		t.start();
	}

	public static boolean isOn(@NonNull Group g) {
		return on[g.ordinal()];
	}

	public static boolean isAnyOn() {
		return anyOn;
	}

	public static void set(@NonNull Context context, @NonNull Group g, boolean value) {
		on[g.ordinal()] = value;
		updateAny();
		prefs(context).edit().putBoolean(g.key, value).apply();
		updateNotification(context);
		if (value) {
			ChizuDriveLog.logAlways("diag " + g.key + " on, " + net.osmand.plus.Version.getFullVersion(
					(net.osmand.plus.OsmandApplication) context.getApplicationContext()));
		}
	}

	private static void updateAny() {
		boolean any = false;
		for (boolean b : on) {
			any |= b;
		}
		anyOn = any;
	}

	static int generation() {
		return generation;
	}

	/** The drive-log group a line belongs to, by its first word. */
	@NonNull
	public static Group groupOf(@NonNull String event) {
		int space = event.indexOf(' ');
		String w = space < 0 ? event : event.substring(0, space);
		switch (w) {
			case "loc":
			case "tunnel":
				return Group.LOCATION;
			case "gnss":
				return Group.GNSS;
			case "perf":
				return Group.PERF;
			case "chk":
				return Group.OFF_ROUTE;
			case "traffic":
				return Group.TRAFFIC;
			case "voice":
			case "focus":
				return Group.VOICE;
			case "map":
				return Group.MAP;
			case "car":
				return Group.CAR;
			case "ui":
			case "drawer":
				return Group.UI;
			case "road":
				return Group.ROADS;
			default: // route, recalc, calc, predict, arrived, candidate(s), start
				return Group.ROUTING;
		}
	}

	@Nullable
	public static File dir(@Nullable Context context) {
		Context c = context != null ? context : app;
		File base = c == null ? null : c.getExternalFilesDir(null);
		if (base == null) {
			return null;
		}
		File d = new File(base, DIR);
		return d.isDirectory() || d.mkdirs() ? d : null;
	}

	private static SharedPreferences prefs(@NonNull Context c) {
		return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}

	// ---------- moving the old logs in ----------

	/** The logs used to go to “/sdcard/tmp” (or the files directory's root): move every one in. */
	private static void migrate(@NonNull Context c) {
		File target = dir(c);
		if (target == null) {
			return;
		}
		List<File> sources = new ArrayList<>();
		sources.add(new File(Environment.getExternalStorageDirectory(), "tmp"));
		sources.add(c.getExternalFilesDir(null));
		sources.add(target); // its own old "*.log" drive logs become "*.txt"
		int moved = 0;
		for (File src : sources) {
			File[] files = src == null ? null : src.listFiles();
			if (files == null) {
				continue;
			}
			for (File f : files) {
				String name = f.getName();
				if (isDiagFile(name) && f.isFile()) {
					if (name.endsWith(OLD_DRIVE_EXT)) {
						name = name.substring(0, name.length() - OLD_DRIVE_EXT.length()) + DRIVE_EXT;
					}
					File to = new File(target, name);
					if (to.equals(f)) {
						continue;
					}
					try {
						moveInto(f, to);
						moved++;
					} catch (Exception e) {
						// left where it was; the next start tries again
					}
				}
			}
		}
		if (moved > 0) {
			generation++;
		}
		if (moved > 0 && anyOn) {
			ChizuDriveLog.logAlways("diag moved " + moved + " old log files into " + target);
		}
	}

	/** Moves {@code f} to {@code to}; when {@code to} already exists, {@code f} goes in front of it. */
	private static void moveInto(@NonNull File f, @NonNull File to) throws Exception {
		if (!to.exists() && f.renameTo(to)) {
			return;
		}
		File tmp = new File(to.getParentFile(), to.getName() + ".moving");
		try (OutputStream out = new FileOutputStream(tmp)) {
			copy(f, out);
			if (to.exists()) {
				copy(to, out);
			}
		}
		if (!tmp.renameTo(to)) {
			throw new IllegalStateException("rename " + tmp);
		}
		if (!f.delete()) {
			throw new IllegalStateException("delete " + f);
		}
	}

	static boolean isDiagFile(@NonNull String name) {
		return (name.startsWith(DRIVE_PREFIX) && (name.endsWith(DRIVE_EXT) || name.endsWith(OLD_DRIVE_EXT)))
				|| (name.startsWith(ROADS_PREFIX) && name.endsWith(".tsv"));
	}

	private static void copy(@NonNull File f, @NonNull OutputStream out) throws Exception {
		try (InputStream in = new FileInputStream(f)) {
			byte[] buf = new byte[64 * 1024];
			int n;
			while ((n = in.read(buf)) > 0) {
				out.write(buf, 0, n);
			}
		}
	}

	// ---------- what is stored ----------

	/** All files in the diag directory, oldest name first. */
	@NonNull
	public static List<File> files(@NonNull Context c) {
		File d = dir(c);
		File[] all = d == null ? null : d.listFiles(f -> f.isFile() && !f.getName().endsWith(".moving"));
		List<File> list = all == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(all));
		list.sort((a, b) -> a.getName().compareTo(b.getName()));
		return list;
	}

	public static final class Summary {
		public int files;
		public long bytes;
		public long first = Long.MAX_VALUE;
		public long last;
		/** Day → {first, last} on that day. */
		public final TreeMap<String, long[]> days = new TreeMap<>();
		public final TreeMap<String, long[]> kinds = new TreeMap<>(); // kind → {files, bytes, first, last}
	}

	@NonNull
	public static Summary summarize(@NonNull Context c) {
		Summary s = new Summary();
		for (File f : files(c)) {
			long start = firstTime(f);
			long end = f.lastModified();
			if (start <= 0 || start > end) {
				start = end;
			}
			s.files++;
			s.bytes += f.length();
			s.first = Math.min(s.first, start);
			s.last = Math.max(s.last, end);
			String day = dayOf(f.getName());
			if (day != null) {
				long[] span = s.days.get(day);
				if (span == null) {
					s.days.put(day, new long[] {start, end});
				} else {
					span[0] = Math.min(span[0], start);
					span[1] = Math.max(span[1], end);
				}
			}
			String kind = f.getName().startsWith(DRIVE_PREFIX) ? "drive" : f.getName().startsWith(ROADS_PREFIX) ? "roads" : "other";
			long[] k = s.kinds.get(kind);
			if (k == null) {
				s.kinds.put(kind, new long[] {1, f.length(), start, end});
			} else {
				k[0]++;
				k[1] += f.length();
				k[2] = Math.min(k[2], start);
				k[3] = Math.max(k[3], end);
			}
		}
		return s;
	}

	@Nullable
	private static String dayOf(@NonNull String name) {
		int us = name.indexOf('_');
		return us >= 0 && name.length() >= us + 11 ? name.substring(us + 1, us + 11) : null;
	}

	/** The time of a file's first line: the day from its name, the clock from the line. */
	private static long firstTime(@NonNull File f) {
		String day = dayOf(f.getName());
		if (day == null) {
			return 0;
		}
		try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
			String line = r.readLine();
			String clock = null;
			if (f.getName().endsWith(".tsv")) {
				line = r.readLine(); // past the header: day, dow, start, …
				String[] cols = line == null ? null : line.split("\t", 4);
				clock = cols != null && cols.length > 2 ? cols[2] : null;
			} else if (line != null && line.length() >= 8) {
				clock = line.substring(0, 8);
			}
			if (clock == null) {
				return 0;
			}
			Date d = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(day + " " + clock);
			return d == null ? 0 : d.getTime();
		} catch (Exception e) {
			return 0;
		}
	}

	// ---------- keeping, marking, the recording notification ----------

	/** Days of data kept; 0 keeps everything (the default). */
	public static int getKeepDays(@NonNull Context c) {
		return prefs(c).getInt(KEY_KEEP_DAYS, 0);
	}

	public static void setKeepDays(@NonNull Context c, int days) {
		prefs(c).edit().putInt(KEY_KEEP_DAYS, Math.max(0, days)).apply();
	}

	/** Deletes the files of days older than the kept ones; returns how many went. */
	public static int cleanup(@NonNull Context c) {
		int keep = getKeepDays(c);
		if (keep <= 0) {
			return 0;
		}
		java.util.Calendar cal = java.util.Calendar.getInstance();
		cal.add(java.util.Calendar.DAY_OF_MONTH, -(keep - 1));
		String oldestKept = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
		int n = 0;
		for (File f : files(c)) {
			String day = dayOf(f.getName());
			if (day != null && day.compareTo(oldestKept) < 0 && f.delete()) {
				n++;
			}
		}
		if (n > 0) {
			generation++;
		}
		return n;
	}

	/** A numbered marker in the drive log: “something happened here”. Returns its number, 0 when nothing records. */
	public static int mark(@NonNull String from) {
		if (!anyOn) {
			return 0;
		}
		int n = ++marks;
		ChizuDriveLog.logAlways("mark #" + n + " from " + from);
		return n;
	}

	/** While any switch is on, an ongoing notification says so, with Mark and Turn all off. */
	public static void updateNotification(@NonNull Context context) {
		Context c = context.getApplicationContext();
		try {
			androidx.core.app.NotificationManagerCompat nm = androidx.core.app.NotificationManagerCompat.from(c);
			if (!anyOn) {
				nm.cancel(NOTIFICATION_ID);
				return;
			}
			if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
				nm.createNotificationChannel(new android.app.NotificationChannel(CHANNEL,
						c.getString(R.string.chizu_diag_channel), android.app.NotificationManager.IMPORTANCE_LOW));
			}
			StringBuilder which = new StringBuilder();
			for (Group g : Group.values()) {
				if (on[g.ordinal()]) {
					which.append(which.length() > 0 ? ", " : "").append(c.getString(g.titleRes));
				}
			}
			int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE;
			android.content.Intent open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
			androidx.core.app.NotificationCompat.Builder b = new androidx.core.app.NotificationCompat.Builder(c, CHANNEL)
					.setSmallIcon(android.R.drawable.ic_menu_edit)
					.setContentTitle(c.getString(R.string.chizu_diag_recording))
					.setContentText(which)
					.setStyle(new androidx.core.app.NotificationCompat.BigTextStyle().bigText(which))
					.setOngoing(true)
					.setOnlyAlertOnce(true)
					.setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
					.addAction(0, c.getString(R.string.chizu_diag_mark), android.app.PendingIntent.getBroadcast(c, 1,
							new android.content.Intent(ACTION_MARK).setClass(c, ChizuDiagReceiver.class), flags))
					.addAction(0, c.getString(R.string.chizu_diag_all_off), android.app.PendingIntent.getBroadcast(c, 2,
							new android.content.Intent(ACTION_ALL_OFF).setClass(c, ChizuDiagReceiver.class), flags));
			if (open != null) {
				b.setContentIntent(android.app.PendingIntent.getActivity(c, 3, open, flags));
			}
			nm.notify(NOTIFICATION_ID, b.build());
		} catch (Exception ignored) {
			// no notification permission: recording goes on regardless
		}
	}

	public static void allOff(@NonNull Context c) {
		for (Group g : Group.values()) {
			if (on[g.ordinal()]) {
				ChizuDriveLog.logAlways("diag " + g.key + " off from the notification");
			}
		}
		SharedPreferences.Editor e = prefs(c).edit();
		for (Group g : Group.values()) {
			on[g.ordinal()] = false;
			e.putBoolean(g.key, false);
		}
		e.apply();
		updateAny();
		updateNotification(c);
	}

	/** Every diag file, offered to another app (a chat, mail, a file manager). */
	@Nullable
	public static android.content.Intent shareIntent(@NonNull Context c) {
		ArrayList<Uri> uris = new ArrayList<>();
		for (File f : files(c)) {
			uris.add(androidx.core.content.FileProvider.getUriForFile(c, c.getPackageName() + ".fileprovider", f));
		}
		if (uris.isEmpty()) {
			return null;
		}
		android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE)
				.setType("text/plain")
				.putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
				.putExtra(android.content.Intent.EXTRA_SUBJECT, "白い熊 地図 diagnostics")
				.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
		return android.content.Intent.createChooser(send, null);
	}

	// ---------- export and purge ----------

	@Nullable
	public static Uri getExportTree(@NonNull Context c) {
		String s = prefs(c).getString(KEY_EXPORT_TREE, null);
		return s == null ? null : Uri.parse(s);
	}

	public static void setExportTree(@NonNull Context c, @NonNull Uri tree) {
		prefs(c).edit().putString(KEY_EXPORT_TREE, tree.toString()).apply();
	}

	/**
	 * Copies every diag file into a new “<yyyy-MM-dd_HH-mm-ss>_chizu-diag” subdirectory of the chosen
	 * directory. Returns the subdirectory's name; throws when nothing could be written.
	 */
	@NonNull
	public static String export(@NonNull Context c, @NonNull Uri tree) throws Exception {
		DocumentFile root = DocumentFile.fromTreeUri(c, tree);
		if (root == null || !root.canWrite()) {
			throw new IllegalStateException(c.getString(R.string.chizu_diag_export_not_writable));
		}
		String name = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date()) + "_chizu-diag";
		DocumentFile sub = root.createDirectory(name);
		if (sub == null) {
			throw new IllegalStateException(c.getString(R.string.chizu_diag_export_not_writable));
		}
		for (File f : files(c)) {
			DocumentFile out = sub.createFile("application/octet-stream", f.getName());
			if (out == null) {
				throw new IllegalStateException(f.getName());
			}
			try (OutputStream os = c.getContentResolver().openOutputStream(out.getUri())) {
				if (os == null) {
					throw new IllegalStateException(f.getName());
				}
				copy(f, os);
			}
		}
		return name;
	}

	/** Deletes every diag file; returns how many went. */
	public static int purge(@NonNull Context c) {
		int n = 0;
		for (File f : files(c)) {
			if (f.delete()) {
				n++;
			}
		}
		generation++;
		return n;
	}
}
