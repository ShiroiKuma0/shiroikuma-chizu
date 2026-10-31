package net.osmand.plus.chizu;

import android.os.Environment;

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
 * of the map's link, tilt and orientation — is appended to “/sdcard/tmp/chizu-drive_<day>.log”
 * (the app's own files directory when All files access is missing). One line per event, flushed at
 * once, so a killed process loses nothing that was written.
 */
public final class ChizuDriveLog {

	private static final String DIR = "tmp";
	private static final String PREFIX = "chizu-drive_";
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
	private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
	private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

	private ChizuDriveLog() {
	}

	public static void init(@NonNull OsmandApplication application) {
		app = application;
		log("start " + Version.getFullVersion(application));
	}

	public static void log(@NonNull String event) {
		if (app == null) {
			return;
		}
		long now = System.currentTimeMillis();
		String thread = Thread.currentThread().getName();
		WRITER.execute(() -> write(now, thread, event));
	}

	/** A fix as it reaches 地図, with where it came from (“aa” the car session, “ph” the phone). */
	public static void location(@NonNull String source, @Nullable Location l) {
		if (l == null) {
			log("loc " + source + " null");
			return;
		}
		log(String.format(Locale.US, "loc %s %s %.6f,%.6f acc=%.1f spd=%.1f brg=%s age=%dms",
				source, l.getProvider(), l.getLatitude(), l.getLongitude(),
				l.hasAccuracy() ? l.getAccuracy() : -1f,
				l.hasSpeed() ? l.getSpeed() : -1f,
				l.hasBearing() ? String.format(Locale.US, "%.0f", l.getBearing()) : "-",
				System.currentTimeMillis() - l.getTime()));
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
			if (out == null || !day.equals(outDay)) {
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
		File dir = new File(Environment.getExternalStorageDirectory(), DIR);
		if (!ChizuStorage.hasAllFilesAccess() || (!dir.isDirectory() && !dir.mkdirs())) {
			OsmandApplication a = app;
			dir = a == null ? null : a.getExternalFilesDir(null);
		}
		if (dir == null) {
			return;
		}
		outFile = new File(dir, PREFIX + day + ".log");
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
