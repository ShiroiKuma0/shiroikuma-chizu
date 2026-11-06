package net.osmand.plus.chizu;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * 白い熊 地図 渋滞回避 settings. Kept in the {@link ChizuTheme#PREFS_NAME} file, so they travel in the
 * {@code chizu_prefs.json} sidecar of every export; the UI reset leaves them alone (it clears colour
 * slots only).
 */
public final class ChizuTrafficPrefs {

	private static final String ENABLED = "traffic_enabled";
	private static final String CHOOSER = "traffic_chooser";
	private static final String INTERVAL_MIN = "traffic_interval_min_v2"; // 白い熊 地図 +040: default 3 min (1 min ate the battery), old value dropped
	private static final String MIN_SAVING_MIN = "traffic_min_saving_min";
	private static final String LONG_YELLOW_M = "traffic_long_yellow_m";
	private static final String CHOSEN_DELAY_MIN = "traffic_chosen_delay_min";
	private static final String SHIMMER = "traffic_shimmer";
	private static final String SHIMMER_LOOP_S = "traffic_shimmer_loop_s";
	private static final String SHIMMER_BAND_PCT = "traffic_shimmer_band_pct";
	private static final String SHIMMER_LENGTH_PCT = "traffic_shimmer_length_pct";
	private static final String SHIMMER_SPARKLES_PCT = "traffic_shimmer_sparkles_pct";
	private static final String SHIMMER_SPARKLE_SIZE_PCT = "traffic_shimmer_sparkle_size_pct";

	/** The selected route's shimmer, read on every frame: cached here, refreshed by every setter. */
	public static final class Shimmer {
		public final boolean on;
		public final int loopSeconds;
		public final int bandPercent;
		public final int lengthPercent;
		public final int sparklesPercent;
		public final int sparkleSizePercent;

		Shimmer(@NonNull SharedPreferences p) {
			on = p.getBoolean(SHIMMER, true);
			loopSeconds = Math.max(1, p.getInt(SHIMMER_LOOP_S, 3));
			bandPercent = p.getInt(SHIMMER_BAND_PCT, 100);
			lengthPercent = Math.max(1, p.getInt(SHIMMER_LENGTH_PCT, 12));
			sparklesPercent = p.getInt(SHIMMER_SPARKLES_PCT, 100);
			sparkleSizePercent = Math.max(10, p.getInt(SHIMMER_SPARKLE_SIZE_PCT, 100));
		}
	}

	private static volatile Shimmer shimmer;

	private ChizuTrafficPrefs() {
	}

	private static SharedPreferences prefs(@NonNull Context context) {
		return context.getApplicationContext().getSharedPreferences(ChizuTheme.PREFS_NAME, Context.MODE_PRIVATE);
	}

	public static boolean isEnabled(@NonNull Context context) {
		return prefs(context).getBoolean(ENABLED, false);
	}

	public static void setEnabled(@NonNull Context context, boolean enabled) {
		prefs(context).edit().putBoolean(ENABLED, enabled).apply();
	}

	public static boolean isChooserEnabled(@NonNull Context context) {
		return prefs(context).getBoolean(CHOOSER, true);
	}

	public static void setChooserEnabled(@NonNull Context context, boolean enabled) {
		prefs(context).edit().putBoolean(CHOOSER, enabled).apply();
	}

	public static int getIntervalMinutes(@NonNull Context context) {
		return Math.max(1, prefs(context).getInt(INTERVAL_MIN, 3));
	}

	public static void setIntervalMinutes(@NonNull Context context, int minutes) {
		prefs(context).edit().putInt(INTERVAL_MIN, minutes).apply();
	}

	public static int getMinSavingMinutes(@NonNull Context context) {
		return Math.max(0, prefs(context).getInt(MIN_SAVING_MIN, 1));
	}

	public static void setMinSavingMinutes(@NonNull Context context, int minutes) {
		prefs(context).edit().putInt(MIN_SAVING_MIN, minutes).apply();
	}

	public static int getLongYellowMeters(@NonNull Context context) {
		return prefs(context).getInt(LONG_YELLOW_M, 300);
	}

	public static void setLongYellowMeters(@NonNull Context context, int meters) {
		prefs(context).edit().putInt(LONG_YELLOW_M, meters).apply();
	}

	/** A slower route picked on purpose is reconsidered only once it would arrive this much later. */
	public static int getChosenDelayMinutes(@NonNull Context context) {
		return Math.max(1, prefs(context).getInt(CHOSEN_DELAY_MIN, 5));
	}

	public static void setChosenDelayMinutes(@NonNull Context context, int minutes) {
		prefs(context).edit().putInt(CHOSEN_DELAY_MIN, minutes).apply();
	}

	@NonNull
	public static Shimmer getShimmer(@NonNull Context context) {
		Shimmer s = shimmer;
		if (s == null) {
			s = new Shimmer(prefs(context));
			shimmer = s;
		}
		return s;
	}

	public static void setShimmerOn(@NonNull Context context, boolean on) {
		prefs(context).edit().putBoolean(SHIMMER, on).commit();
		shimmer = null;
	}

	public static void setShimmerLoopSeconds(@NonNull Context context, int seconds) {
		putShimmer(context, SHIMMER_LOOP_S, seconds);
	}

	public static void setShimmerBandPercent(@NonNull Context context, int percent) {
		putShimmer(context, SHIMMER_BAND_PCT, percent);
	}

	public static void setShimmerLengthPercent(@NonNull Context context, int percent) {
		putShimmer(context, SHIMMER_LENGTH_PCT, percent);
	}

	public static void setShimmerSparklesPercent(@NonNull Context context, int percent) {
		putShimmer(context, SHIMMER_SPARKLES_PCT, percent);
	}

	public static void setShimmerSparkleSizePercent(@NonNull Context context, int percent) {
		putShimmer(context, SHIMMER_SPARKLE_SIZE_PCT, percent);
	}

	private static void putShimmer(@NonNull Context context, @NonNull String key, int value) {
		prefs(context).edit().putInt(key, value).commit();
		shimmer = null;
	}
}
