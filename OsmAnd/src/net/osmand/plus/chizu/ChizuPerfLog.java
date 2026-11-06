package net.osmand.plus.chizu;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Debug;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.activities.MapActivity;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 白い熊 地図 CPU, memory and battery log (Diagnostics → “CPU, memory and battery”, lines “perf …” in
 * the drive log). Once a minute: the processor time 地図 used in that minute, in total and by thread
 * (the five busiest, by name — “route-check”, “route-reroute”, “traffic-check”, the GL renderer, main,
 * …), the native, graphics and Java memory, where the map is drawn (phone, car screen), and the
 * battery: level, current (negative = discharging), temperature, charger, and the thermal state.
 * <p>
 * Written after a drive on 2026-11-05 that drained the battery on a fast charger, heated the phone
 * past its thermal limit and left 3.4 GB of native memory behind. Read from /proc/self, off the main
 * thread; only the map/car state is asked on the main thread.
 */
public final class ChizuPerfLog {

	private static final long EVERY_MS = 60_000;
	private static final int TOP = 5;
	private static final long MB = 1 << 20;

	private static ScheduledExecutorService timer;
	private static final Map<String, Long> lastTicks = new HashMap<>(); // tid → utime+stime
	private static long lastProcTicks = -1;
	private static long lastSampleMs;
	private static long lastNativeMb = -1;
	// 白い熊 地図 +041: raster tiles and car frames, counted where they happen, read and reset each minute
	private static final java.util.concurrent.atomic.AtomicLong tilesServed = new java.util.concurrent.atomic.AtomicLong();
	private static final java.util.concurrent.atomic.AtomicLong tilesMissing = new java.util.concurrent.atomic.AtomicLong();
	private static final java.util.concurrent.atomic.AtomicLong tilesMs = new java.util.concurrent.atomic.AtomicLong();
	private static final java.util.concurrent.atomic.AtomicLong tileDownloads = new java.util.concurrent.atomic.AtomicLong();
	private static final java.util.concurrent.atomic.AtomicLong carFrames = new java.util.concurrent.atomic.AtomicLong();
	private static final java.util.concurrent.atomic.AtomicLong carFrameMs = new java.util.concurrent.atomic.AtomicLong();

	/** 白い熊 地図 +061: what the phone charges from — the adapter (AC, Huawei SuperCharge included), USB, a pad. */
	@NonNull
	private static String chizuPlugged(int plugged) {
		switch (plugged) {
			case 0:
				return "unplugged";
			case BatteryManager.BATTERY_PLUGGED_AC:
				return "charging-AC";
			case BatteryManager.BATTERY_PLUGGED_USB:
				return "charging-USB";
			case BatteryManager.BATTERY_PLUGGED_WIRELESS:
				return "charging-wireless";
			default:
				return "charging-" + plugged;
		}
	}

	/** A raster tile handed to the map engine (or not), and how long decoding and copying it took. */
	public static void tile(boolean served, long ms) {
		(served ? tilesServed : tilesMissing).incrementAndGet();
		tilesMs.addAndGet(ms);
	}

	private static final java.util.concurrent.atomic.AtomicLong tilesReused = new java.util.concurrent.atomic.AtomicLong();

	/** 白い熊 地図 +048: a tile handed over from the decoded cache, no decoding. */
	public static void tileReused() {
		tilesReused.incrementAndGet();
	}

	public static void tileDownload() {
		tileDownloads.incrementAndGet();
	}

	/** A frame drawn onto the car screen, and how long the copy took. */
	/** 白い熊 地図 +046: what asked for car frames — the map engine (gl), a map refresh (by whom), held back. */
	private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> carAsks =
			new java.util.concurrent.ConcurrentHashMap<>();

	public static void carAsk(@NonNull String source) {
		carAsks.computeIfAbsent(source, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
	}

	@NonNull
	private static String carAsksLine() {
		List<Map.Entry<String, Long>> list = new ArrayList<>();
		for (Map.Entry<String, java.util.concurrent.atomic.AtomicLong> e : carAsks.entrySet()) {
			long n = e.getValue().getAndSet(0);
			if (n > 0) {
				list.add(new java.util.AbstractMap.SimpleEntry<>(e.getKey(), n));
			}
		}
		Collections.sort(list, (a, b) -> Long.compare(b.getValue(), a.getValue()));
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < Math.min(6, list.size()); i++) {
			sb.append(i == 0 ? "" : " | ").append(list.get(i).getKey()).append(' ').append(list.get(i).getValue());
		}
		return sb.toString();
	}

	public static void carFrame(long ms) {
		carFrames.incrementAndGet();
		carFrameMs.addAndGet(ms);
	}

	private ChizuPerfLog() {
	}

	public static synchronized void init(@NonNull OsmandApplication app) {
		if (timer != null) {
			return;
		}
		timer = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "chizu-perf");
			t.setPriority(Thread.MIN_PRIORITY);
			return t;
		});
		timer.scheduleWithFixedDelay(() -> {
			try {
				if (ChizuDiag.isOn(ChizuDiag.Group.PERF)) {
					sample(app);
				} else {
					lastProcTicks = -1; // a fresh baseline when switched on again
					lastTicks.clear();
				}
			} catch (Throwable e) {
				ChizuDriveLog.log("perf failed: " + e);
			}
		}, EVERY_MS, EVERY_MS, TimeUnit.MILLISECONDS);
	}

	private static void sample(@NonNull OsmandApplication app) {
		long now = SystemClock.elapsedRealtime();
		// --- CPU, per thread ---
		Map<String, Long> byName = new HashMap<>();
		Map<String, Long> ticksNow = new HashMap<>();
		Map<String, Long> unnamedByThread = new HashMap<>(); // “Thread-N”: Java threads with no name of their own
		File[] tasks = new File("/proc/self/task").listFiles();
		if (tasks != null) {
			for (File task : tasks) {
				long[] t = new long[1];
				String name = readStat(new File(task, "stat"), t);
				if (name == null) {
					continue;
				}
				String tid = task.getName();
				ticksNow.put(tid, t[0]);
				Long before = lastTicks.get(tid);
				long delta = before == null ? (lastProcTicks < 0 ? 0 : t[0]) : t[0] - before;
				if (delta > 0) {
					String key = name.replaceAll("[-_ ]?\\d+$", "");
					byName.merge(key, delta, Long::sum);
					if (name.matches("Thread-\\d+")) {
						unnamedByThread.put(name, delta);
					}
				}
			}
		}
		lastTicks.clear();
		lastTicks.putAll(ticksNow);
		long[] proc = new long[1];
		readStat(new File("/proc/self/stat"), proc);
		long procDelta = lastProcTicks < 0 ? -1 : proc[0] - lastProcTicks;
		lastProcTicks = proc[0];
		long wallMs = lastSampleMs == 0 ? EVERY_MS : now - lastSampleMs;
		lastSampleMs = now;
		// --- memory ---
		Debug.MemoryInfo mi = new Debug.MemoryInfo();
		Debug.getMemoryInfo(mi);
		String graphicsKb = mi.getMemoryStat("summary.graphics");
		long nativeMb = Debug.getNativeHeapAllocatedSize() / MB;
		Runtime rt = Runtime.getRuntime();
		long javaMb = (rt.totalMemory() - rt.freeMemory()) / MB;
		// --- battery ---
		BatteryManager bm = (BatteryManager) app.getSystemService(Context.BATTERY_SERVICE);
		int level = bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
		long currentRaw = bm == null ? 0 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
		// µA by the documentation; some phones report mA — anything this small is taken as mA
		long currentMa = Math.abs(currentRaw) > 20_000 ? currentRaw / 1000 : currentRaw;
		Intent battery = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
		float tempC = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -10) / 10f;
		int plugged = battery == null ? 0 : battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
		PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
		String thermal = pm == null || Build.VERSION.SDK_INT < 29 ? "?" : thermal(pm.getCurrentThermalStatus());
		boolean screen = pm != null && pm.isInteractive();

		List<Map.Entry<String, Long>> top = new ArrayList<>(byName.entrySet());
		Collections.sort(top, (a, b) -> Long.compare(b.getValue(), a.getValue()));
		StringBuilder threads = new StringBuilder();
		for (int i = 0; i < Math.min(TOP, top.size()); i++) {
			// ticks are 10 ms: % of one core over the minute
			threads.append(i == 0 ? "" : ", ").append(top.get(i).getKey()).append(' ')
					.append(Math.round(top.get(i).getValue() * 10 * 100.0 / wallMs)).append('%');
		}
		String cpu = procDelta < 0 ? "-" : Math.round(procDelta * 10 * 100.0 / wallMs) + "%";
		long growth = lastNativeMb < 0 ? 0 : nativeMb - lastNativeMb;
		lastNativeMb = nativeMb;
		long served = tilesServed.getAndSet(0);
		long missing = tilesMissing.getAndSet(0);
		long tileMs = tilesMs.getAndSet(0);
		long frames = carFrames.getAndSet(0);
		long frameMs = carFrameMs.getAndSet(0);
		net.osmand.plus.settings.backend.OsmandSettings st = app.getSettings();
		String line = String.format(Locale.US,
				"perf cpu=%s [%s] native=%dMB (%+dMB) graphics=%sMB java=%dMB battery=%d%% %+dmA %.1f°C %s thermal=%s screen=%s"
						+ " tiles=%d+%dmiss %dms reused=%d dl=%d car=%dfr %dms/fr underlay=%s overlay=%s vector=%d/255",
				cpu, threads, nativeMb, growth, graphicsKb == null ? "?" : String.valueOf(Long.parseLong(graphicsKb) / 1024),
				javaMb, level, currentMa, tempC, chizuPlugged(plugged), thermal, screen ? "on" : "off",
				served, missing, tileMs, tilesReused.getAndSet(0), tileDownloads.getAndSet(0), frames, frames == 0 ? 0 : frameMs / frames,
				st.MAP_UNDERLAY.get(), st.MAP_OVERLAY.get(), st.MAP_TRANSPARENCY.get());
		String stacks = unnamedStacks(unnamedByThread, wallMs);
		String asks = carAsksLine();
		app.runInUIThread(() -> {
			MapActivity activity = app.getOsmandMap().getMapView().getMapActivity();
			boolean phoneMap = activity != null && activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED);
			boolean carMap = app.getCarNavigationSession() != null;
			ChizuDriveLog.log(line + " map=" + (phoneMap ? "phone" : "-") + (carMap ? "+car" : "")
					+ " following=" + app.getRoutingHelper().isFollowingMode());
			if (!stacks.isEmpty()) {
				ChizuDriveLog.log("perf unnamed threads: " + stacks);
			}
			if (!asks.isEmpty()) {
				ChizuDriveLog.log("perf car frames asked by: " + asks);
			}
		});
	}

	/**
	 * The two busiest “Thread-N” threads of the minute (over 5 % of a core), each with the top of its
	 * Java stack — which code they run. Threads the map engine attaches to Java carry such names.
	 */
	@NonNull
	private static String unnamedStacks(@NonNull Map<String, Long> unnamed, long wallMs) {
		if (unnamed.isEmpty()) {
			return "";
		}
		List<Map.Entry<String, Long>> busy = new ArrayList<>(unnamed.entrySet());
		Collections.sort(busy, (a, b) -> Long.compare(b.getValue(), a.getValue()));
		Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < Math.min(2, busy.size()); i++) {
			long pct = Math.round(busy.get(i).getValue() * 10 * 100.0 / wallMs);
			if (pct < 5) {
				break;
			}
			String name = busy.get(i).getKey();
			sb.append(sb.length() == 0 ? "" : " | ").append(name).append(' ').append(pct).append("%:");
			for (Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
				if (!name.equals(e.getKey().getName())) {
					continue;
				}
				StackTraceElement[] st = e.getValue();
				for (int k = 0; k < Math.min(3, st.length); k++) {
					sb.append(' ').append(st[k].getClassName().replaceAll(".*\\.", "")).append('.').append(st[k].getMethodName());
				}
				break;
			}
		}
		return sb.toString();
	}

	/** The thread or process name of a /proc stat file, and utime+stime (ticks) into {@code ticks[0]}. */
	private static String readStat(@NonNull File stat, @NonNull long[] ticks) {
		try {
			String s = new String(Files.readAllBytes(stat.toPath()), StandardCharsets.US_ASCII);
			int open = s.indexOf('(');
			int close = s.lastIndexOf(')');
			if (open < 0 || close < open) {
				return null;
			}
			String[] f = s.substring(close + 2).split(" ");
			// after the name: f[0] is field 3 (state); utime is field 14, stime field 15
			ticks[0] = Long.parseLong(f[11]) + Long.parseLong(f[12]);
			return s.substring(open + 1, close);
		} catch (Exception e) {
			return null;
		}
	}

	@NonNull
	private static String thermal(int status) {
		switch (status) {
			case PowerManager.THERMAL_STATUS_NONE:
				return "none";
			case PowerManager.THERMAL_STATUS_LIGHT:
				return "light";
			case PowerManager.THERMAL_STATUS_MODERATE:
				return "moderate";
			case PowerManager.THERMAL_STATUS_SEVERE:
				return "severe";
			case PowerManager.THERMAL_STATUS_CRITICAL:
				return "critical";
			case PowerManager.THERMAL_STATUS_EMERGENCY:
				return "emergency";
			case PowerManager.THERMAL_STATUS_SHUTDOWN:
				return "shutdown";
			default:
				return String.valueOf(status);
		}
	}
}
