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
	private static final String INTERVAL_MIN = "traffic_interval_min";
	private static final String MIN_SAVING_MIN = "traffic_min_saving_min";
	private static final String LONG_YELLOW_M = "traffic_long_yellow_m";

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
}
