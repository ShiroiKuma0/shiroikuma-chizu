package net.osmand.plus.chizu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.Version;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 白い熊 地図: a drive log of its own. EMUI drops info lines from logcat and rolls the buffer within
 * the hour, so what happens on a drive — every fix, every off-route check that fires, every route
 * recalculation and how it was announced, every audio focus taken and given back, and every change
 * of the map's link, tilt and orientation — is appended to “chizu-drive_<day>.txt” in the diag
 * directory ({@link ChizuDiag}). Each line belongs to a Diagnostics group by its first word and is
 * written only while that group's switch is on. One line per event, flushed at once, so a killed
 * process loses nothing that was written.
 */
public final class ChizuDriveLog {

	private static final String PREFIX = ChizuDiag.DRIVE_PREFIX;
	private static final long MAX_BYTES_PER_DAY = 64L * 1024 * 1024;

	private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "chizu-drive-log");
		t.setDaemon(true);
		return t;
	});

	private static volatile OsmandApplication app;

	// writer thread only
	private static Writer out;
	private static File outFile;
	private static String outDay;
	private static int outGeneration;
	private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
	private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

	private ChizuDriveLog() {
	}

	public static void init(@NonNull OsmandApplication application) {
		app = application;
		if (ChizuDiag.isAnyOn()) {
			logAlways("start " + Version.getFullVersion(application));
		}
	}

	/** Whether lines of this group are being written: check before formatting a line on a hot path. */
	public static boolean on(@NonNull ChizuDiag.Group group) {
		return ChizuDiag.isOn(group);
	}

	public static void log(@NonNull String event) {
		if (app == null || !ChizuDiag.isOn(ChizuDiag.groupOf(event))) {
			return;
		}
		logAlways(event);
	}

	/** Written whatever the switches say: the diagnostics' own housekeeping lines. */
	static void logAlways(@NonNull String event) {
		if (app == null) {
			return;
		}
		long now = System.currentTimeMillis();
		String thread = Thread.currentThread().getName();
		WRITER.execute(() -> write(now, thread, event));
	}

	/** A fix as it reaches 地図, with where it came from (“aa” the car session, “ph” the phone). */
	public static void location(@NonNull String source, @Nullable Location l) {
		if (!ChizuDiag.isOn(ChizuDiag.Group.LOCATION)) {
			return;
		}
		if (l == null) {
			log("loc " + source + " null");
			return;
		}
		// age: by the uptime clock when the fix carries it (true delay), else by the wall clock, which
		// on this phone runs up to ~1.4 s off between Android's corrections and looked like GPS delay
		long uptimeAge = l.chizuElapsedMs > 0 ? android.os.SystemClock.elapsedRealtime() - l.chizuElapsedMs : -1;
		log(String.format(Locale.US, "loc %s %s %.6f,%.6f acc=%.1f spd=%.1f brg=%s age=%dms" + (uptimeAge >= 0 ? " clock_off=%dms" : ""),
				source, l.getProvider(), l.getLatitude(), l.getLongitude(),
				l.hasAccuracy() ? l.getAccuracy() : -1f,
				l.hasSpeed() ? l.getSpeed() : -1f,
				l.hasBearing() ? String.format(Locale.US, "%.0f", l.getBearing()) : "-",
				uptimeAge >= 0 ? uptimeAge : System.currentTimeMillis() - l.getTime(),
				uptimeAge >= 0 ? (System.currentTimeMillis() - l.getTime()) - uptimeAge : 0));
	}

	/** The two methods that called the caller, for events whose trigger is the question. */
	@NonNull
	public static String caller() {
		StackTraceElement[] st = new Throwable().getStackTrace();
		StringBuilder sb = new StringBuilder();
		for (int i = 2; i < Math.min(st.length, 5); i++) {
			if (sb.length() > 0) {
				sb.append(" < ");
			}
			String cls = st[i].getClassName();
			sb.append(cls.substring(cls.lastIndexOf('.') + 1)).append('.').append(st[i].getMethodName());
		}
		return sb.toString();
	}

	private static void write(long now, @NonNull String thread, @NonNull String event) {
		try {
			Date date = new Date(now);
			String day = DAY.format(date);
			// reopened also when the file was deleted under us (a system clean-up), or the lines go nowhere
			if (out == null || !day.equals(outDay) || outGeneration != ChizuDiag.generation() || !outFile.exists()) {
				open(day);
			}
			if (out == null || outFile.length() > MAX_BYTES_PER_DAY) {
				return;
			}
			out.write(TIME.format(date) + " [" + thread + "] " + event + "\n");
			out.flush();
		} catch (Exception e) {
			closeQuietly();
		}
	}

	private static void open(@NonNull String day) throws Exception {
		closeQuietly();
		outGeneration = ChizuDiag.generation();
		File dir = ChizuDiag.dir(app);
		if (dir == null) {
			return;
		}
		outFile = new File(dir, PREFIX + day + ChizuDiag.DRIVE_EXT);
		out = new OutputStreamWriter(new FileOutputStream(outFile, true), StandardCharsets.UTF_8);
		outDay = day;
	}

	private static void closeQuietly() {
		if (out != null) {
			try {
				out.close();
			} catch (Exception ignored) {
			}
		}
		out = null;
	}
}
