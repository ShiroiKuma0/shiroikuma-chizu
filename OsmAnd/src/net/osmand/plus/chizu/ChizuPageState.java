package net.osmand.plus.chizu;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 白い熊 地図 +103: the order of the UI settings page's sections and which are unfolded (白い熊, 2026-11-19:
 * “long-tap-draggable-reorderable pills … preservable via export/import”).
 * <p>
 * Kept in the {@link ChizuTheme#PREFS_NAME} file, by <b>stable string keys</b> — never by resource ids,
 * which shift between builds and would scramble a saved order after an update. So the file travels in the
 * {@code chizu_prefs.json} sidecar with the colours and fonts, and a restore brings the arrangement with it.
 * Folded by default: a section is open only when its key is in the open set.
 */
public final class ChizuPageState {

	private static final String KEY_ORDER = "ui_section_order";
	private static final String KEY_OPEN = "ui_section_open";
	/** The section that leads the default order, and joins a stored one in first place. */
	public static final String FIRST_KEY = "language";

	private ChizuPageState() {
	}

	private static SharedPreferences prefs(@NonNull Context c) {
		return c.getApplicationContext().getSharedPreferences(ChizuTheme.PREFS_NAME, Context.MODE_PRIVATE);
	}

	/**
	 * The stored order, applied to {@code built}: stored keys first in their saved order (those still
	 * present), then any new sections in their built order, and unknown stored keys dropped. So adding a
	 * section in a later build makes it appear at its built place without losing the saved arrangement.
	 */
	@NonNull
	public static List<String> order(@NonNull Context c, @NonNull List<String> built) {
		String saved = prefs(c).getString(KEY_ORDER, null);
		List<String> result = new ArrayList<>();
		if (saved != null && !saved.isEmpty()) {
			for (String k : saved.split(",")) {
				if (built.contains(k) && !result.contains(k)) {
					result.add(k);
				}
			}
		}
		for (String k : built) {
			if (!result.contains(k)) {
				// 5.6.0+003: “App language” is the first section also in a stored order that predates it —
				// nothing dragged it anywhere yet (black-yellow skill, 2026-11-24)
				if (FIRST_KEY.equals(k)) {
					result.add(0, k);
				} else {
					result.add(k);
				}
			}
		}
		return result;
	}

	public static void setOrder(@NonNull Context c, @NonNull List<String> order) {
		prefs(c).edit().putString(KEY_ORDER, String.join(",", order)).apply();
	}

	public static void resetOrder(@NonNull Context c) {
		prefs(c).edit().remove(KEY_ORDER).apply();
	}

	public static boolean isOpen(@NonNull Context c, @NonNull String key) {
		return openSet(c).contains(key);
	}

	public static void setOpen(@NonNull Context c, @NonNull String key, boolean open) {
		Set<String> set = openSet(c);
		if (open) {
			set.add(key);
		} else {
			set.remove(key);
		}
		prefs(c).edit().putStringSet(KEY_OPEN, set).apply();
	}

	@NonNull
	private static Set<String> openSet(@NonNull Context c) {
		return new LinkedHashSet<>(prefs(c).getStringSet(KEY_OPEN, new HashSet<>()));
	}
}
