package net.osmand.plus.chizu;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.plus.settings.backend.preferences.CommonPreference;

/**
 * 白い熊 地図 渋滞回避: pale streets while routes are previewed. The map's “Road style” is switched
 * to Pale for the preview, so the streets recede and the routes are the only strong lines; the
 * previous value is put back on Go, cancel, or leaving the map. The saved value lives in the
 * fork's own prefs, so a crash mid-preview is undone on the next start.
 */
public final class ChizuPreviewStyle {

	private static final String ROAD_STYLE = "roadStyle";
	private static final String PALE = "pale";
	private static final String PREFS = "chizu_preview_style";
	private static final String ACTIVE = "active";
	private static final String SAVED = "saved";
	private static final String MODE = "mode";

	private ChizuPreviewStyle() {
	}

	private static SharedPreferences prefs(@NonNull Context context) {
		return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}

	/** Applies or undoes the pale preview; refreshes the map when anything changed. UI thread. */
	public static void sync(@NonNull MapActivity activity, boolean wanted) {
		OsmandApplication app = activity.getApp();
		boolean active = prefs(app).getBoolean(ACTIVE, false);
		if (wanted == active) {
			return;
		}
		if (wanted) {
			apply(app);
		} else {
			restore(app);
		}
		activity.refreshMapComplete();
	}

	/** Undoes a pale preview left behind by a crash. */
	public static void restoreIfLeft(@NonNull OsmandApplication app) {
		if (prefs(app).getBoolean(ACTIVE, false)) {
			restore(app);
		}
	}

	private static void apply(@NonNull OsmandApplication app) {
		OsmandSettings settings = app.getSettings();
		ApplicationMode mode = settings.getApplicationMode();
		CommonPreference<String> pref = settings.getCustomRenderProperty(ROAD_STYLE);
		String current = pref.getModeValue(mode);
		prefs(app).edit()
				.putBoolean(ACTIVE, true)
				.putString(SAVED, current == null ? "" : current)
				.putString(MODE, mode.getStringKey())
				.commit();
		pref.setModeValue(mode, PALE);
	}

	private static void restore(@NonNull OsmandApplication app) {
		SharedPreferences prefs = prefs(app);
		OsmandSettings settings = app.getSettings();
		ApplicationMode mode = ApplicationMode.valueOfStringKey(prefs.getString(MODE, null), null);
		String saved = prefs.getString(SAVED, "");
		if (mode != null) {
			CommonPreference<String> pref = settings.getCustomRenderProperty(ROAD_STYLE);
			// only undo our own change: a road style picked by hand during the preview stays
			if (PALE.equals(pref.getModeValue(mode))) {
				pref.setModeValue(mode, saved);
			}
		}
		prefs.edit().putBoolean(ACTIVE, false).commit();
	}
}
