package net.osmand.plus.chizu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.OsmandApplication;

import java.io.File;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * shiroikuma fork: how much of an import has actually landed on disk, for the progress a caller
 * watches.
 *
 * <h3>Why this exists</h3>
 *
 * The stock import reports nothing. {@code FileSettingsHelper.importSettings} fires
 * {@code onImportFinished} and not one event before it — the per-item callbacks on
 * {@code SettingsHelper.ImportListener} belong to OsmAnd's <i>cloud</i> importer, which is not the
 * path a data-door restore takes. So there is no hook to subscribe to, and an import that runs for
 * twenty minutes has nothing of its own to say.
 *
 * <p>Something must still speak, because 応用管理 kills a transfer that is both silent and burning
 * no CPU. A heartbeat repeating one standing figure satisfies that and nothing else: 白い熊 watched
 * {@code 4,328,639,284/4,328,639,284} repeat every five seconds for a whole import and read it,
 * reasonably, as a hang.
 *
 * <h3>What it measures</h3>
 *
 * The archive is a ZIP and it is on disk, so its entry table already says which files the import
 * will write and how big each one is uncompressed. Each beat sums what those destinations hold
 * right now. The number climbs as files appear, and the file being written can be named.
 *
 * <p><b>It is a proxy, not a measurement.</b> Some settings items are preferences rather than files
 * and never appear on disk at all; a file may be written by a path other than the one predicted
 * here. For the archives this matters on — where the mass is {@code .obf} maps and cached tiles —
 * it tracks the real work closely, but it is an estimate and is not to be described as anything
 * else.
 *
 * <h3>Two rules it must not break</h3>
 *
 * <ul>
 * <li><b>Never go backwards.</b> 応用管理's {@code RestoreOp} reads a falling byte count as a
 *     deliberate marker and prints "second pass"; a poll that dips because a file is being replaced
 *     would invent one. Hence the high-water mark.</li>
 * <li><b>Never throw.</b> The caller's beat is the liveness signal; an exception in here would
 *     silence it. Every answer is best-effort and falls back rather than failing.</li>
 * </ul>
 */
class ChizuLanded {

	/** One file the archive says will be written, and where it should appear. */
	private static class Target {

		final File destination;
		final String name;
		final long size;

		Target(@NonNull File destination, @NonNull String name, long size) {
			this.destination = destination;
			this.name = name;
			this.size = size;
		}
	}

	private final List<Target> targets;

	/** Total uncompressed bytes of everything the archive will unpack. */
	final long total;

	private long highWater;
	@Nullable
	private String current;

	private ChizuLanded(@NonNull List<Target> targets, long total) {
		this.targets = targets;
		this.total = total;
	}

	/**
	 * Reads the archive's entry table once, up front.
	 *
	 * <p>Entries with no directory component — {@code items.json}, {@code favorites-*.gpx} and the
	 * rest of the settings payload — are left out: they are unpacked into the app's own settings
	 * rather than written to a path we could predict, and counting them would only add a total that
	 * never fills.
	 *
	 * @return null when the table cannot be read, which simply means the caller keeps its old
	 *         standing figure.
	 */
	@Nullable
	static ChizuLanded forArchive(@NonNull OsmandApplication app, @NonNull File archive) {
		try (ZipFile zip = new ZipFile(archive)) {
			List<Target> targets = new ArrayList<>();
			long total = 0;
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String name = entry.getName();
				long size = entry.getSize();
				if (entry.isDirectory() || size <= 0 || !name.contains("/")) {
					continue;
				}
				targets.add(new Target(app.getAppPath(name), shortName(name), size));
				total += size;
			}
			return targets.isEmpty() ? null : new ChizuLanded(targets, total);
		} catch (Exception e) {
			return null;
		}
	}

	/** Bytes on disk for the archive's files, never less than the last answer. */
	long landedBytes() {
		long sum = 0;
		String writing = null;
		for (Target target : targets) {
			long length;
			try {
				length = target.destination.length();
			} catch (Exception e) {
				length = 0;
			}
			if (length > 0) {
				sum += Math.min(length, target.size);
				if (length < target.size) {
					writing = target.name;
				} else if (writing == null) {
					writing = target.name;
				}
			}
		}
		if (sum > highWater) {
			highWater = sum;
			current = writing;
		}
		return Math.min(highWater, total);
	}

	@NonNull
	String describe() {
		String name = current;
		return name == null ? "Restoring" : "Restoring " + name;
	}

	@NonNull
	private static String shortName(@NonNull String entryName) {
		int slash = entryName.lastIndexOf('/');
		return slash >= 0 && slash + 1 < entryName.length() ? entryName.substring(slash + 1) : entryName;
	}
}
