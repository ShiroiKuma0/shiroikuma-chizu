package net.osmand.plus.chizu;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * shiroikuma fork: shared-folder storage support.
 *
 * The fork declares MANAGE_EXTERNAL_STORAGE (like the stock OsmAnd build) so Main storage
 * can point at a shared folder such as /storage/emulated/0/〇/[60] 地図. When that access
 * hasn't been granted yet, a directory write test fails — instead of a dead-end toast we
 * send the user straight to the system "All files access" toggle for this app.
 */
public class ChizuStorage {

	private ChizuStorage() {
	}

	/** True when full shared-storage access is already granted (or not needed pre-R). */
	public static boolean hasAllFilesAccess() {
		return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager();
	}

	/**
	 * True when Main storage is a folder <b>outside this app's own directories</b> — a shared one
	 * on the SD card, such as {@code /storage/emulated/0/〇/[60] 地図}.
	 *
	 * <p>This is the distinction the backup turns on. A folder like that is not this app's data: it
	 * is 白い熊's, it lives in a tree that is already carried between phones by other means, and
	 * this app merely points into it. What belongs in a 地図 backup then is the pointers — the
	 * settings, {@code selected_gpx}, the favourites — and not four gigabytes of tiles and maps that
	 * are sitting in that folder on the other phone already.
	 *
	 * <p>When Main storage <i>is</i> one of this app's private directories, none of that holds: the
	 * files are the app's own, nothing else backs them up, and they belong in the archive.
	 */
	public static boolean isStorageFolderShared(@NonNull OsmandApplication app) {
		try {
			File inUse = app.getAppPath(null);
			if (inUse == null) {
				return false;
			}
			String path = inUse.getCanonicalPath();
			for (File root : privateRoots(app)) {
				if (root == null) {
					continue;
				}
				String prefix = root.getCanonicalPath();
				if (path.equals(prefix) || path.startsWith(prefix + File.separator)) {
					return false;
				}
			}
			return true;
		} catch (Exception e) {
			// unknown means "treat it as the app's own", which keeps the old, fuller archive
			return false;
		}
	}

	/**
	 * Every directory the platform gives this app to itself.
	 *
	 * <p>Asked of the <b>path in use</b> rather than of the configured storage type, because those
	 * two disagree in exactly the case that matters. With Main storage set to a shared folder the
	 * type says SPECIFIED, but if All-files access is missing the app has already fallen back to a
	 * private directory and is genuinely working there — those files are then the only copy it can
	 * see, nothing else is preserving them, and they belong in a backup after all. Reading the type
	 * would have called them external and dropped them. 白い熊's rule, 2026-09-08: it depends on
	 * where the resources are <i>now</i>.
	 */
	@NonNull
	private static List<File> privateRoots(@NonNull OsmandApplication app) {
		List<File> roots = new ArrayList<>();
		roots.add(app.getFilesDir());
		roots.add(app.getCacheDir());
		Collections.addAll(roots, app.getExternalFilesDirs(null));
		Collections.addAll(roots, app.getObbDirs());
		try {
			roots.add(app.getSettings().getInternalAppPath());
		} catch (Exception ignored) {
			// one root we cannot ask for is not a reason to misjudge the rest
		}
		return roots;
	}

	/**
	 * The configured Main storage folder that this app is <b>not actually using</b>, or null when
	 * the two agree.
	 *
	 * <p>This is the silent failure behind a restore that looks complete and shows nothing. 白い熊's
	 * Main storage is a shared folder — {@code /storage/emulated/0/〇/[60] 地図} — which needs
	 * All-files access. Without it {@link OsmandApplication#onCreate} finds the folder unwritable
	 * and quietly swaps in a fallback, so the app keeps working against a <i>different</i>
	 * directory while the settings still name the configured one. Everything then lands in the
	 * wrong place: a restore writes a second copy of the whole tree into the fallback, and
	 * {@code selected_gpx} — which names every visible track by absolute path inside the configured
	 * folder — resolves to nothing. Measured on 白い熊's new phone, 2026-09-08: 6 GB duplicated,
	 * six tracks restored, none drawn.
	 *
	 * <p>{@link OsmandApplication#getAppPath} answers where writes actually go;
	 * {@code settings.getExternalStorageDirectory()} answers where they were configured to go.
	 * When those differ, the app is running on the fallback.
	 */
	@Nullable
	public static File unreachableStorageFolder(@NonNull OsmandApplication app) {
		try {
			File configured = app.getSettings().getExternalStorageDirectory();
			File inUse = app.getAppPath(null);
			if (configured == null || inUse == null) {
				return null;
			}
			return configured.getAbsolutePath().equals(inUse.getAbsolutePath()) ? null : configured;
		} catch (Exception e) {
			// a storage question we cannot answer is not a reason to block anything
			return null;
		}
	}

	/**
	 * When full storage access is missing on Android 11+, opens the All-files-access
	 * system toggle for this app. Returns true when the user was redirected there.
	 */
	public static boolean requestAllFilesAccessIfNeeded(@NonNull Context context) {
		if (hasAllFilesAccess()) {
			return false;
		}
		try {
			Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
					Uri.parse("package:" + context.getPackageName()));
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			context.startActivity(intent);
		} catch (ActivityNotFoundException e) {
			try {
				Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
				intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				context.startActivity(intent);
			} catch (ActivityNotFoundException ignored) {
				return false;
			}
		}
		return true;
	}

	/** Asked once per process, so a decline is not re-asked on every return to the map. */
	private static boolean askedThisRun;

	/**
	 * Ask for All-files access on start, when the Main storage folder needs it and does not have it.
	 *
	 * <p><b>Why on start and not only in the storage settings.</b> The grant is per-install: it does
	 * not travel in a backup, and it cannot — it is a special app-op, not a runtime permission, so
	 * the sister-app restore contract cannot grant it either. A freshly installed 地図 therefore
	 * comes up configured for a folder it may not open, and until now said nothing at all: it fell
	 * back to another directory and carried on looking healthy, which is exactly how 白い熊 ended up
	 * with a restored phone whose tracks were all present and none of them drawn.
	 *
	 * <p>Only asked when it would change something — the folder is genuinely out of reach — and only
	 * once per run.
	 *
	 * <p><b>Straight to the system toggle</b> (白い熊, 2026-09-08), not a dialog offering to go
	 * there: on a freshly restored phone this is the one thing standing between the app and its
	 * maps, and a confirmation step in front of it is a step to nowhere. The toast carries the
	 * reason, so the jump is not unexplained.
	 */
	public static void askForStorageAccessOnStart(@NonNull Activity activity) {
		if (askedThisRun || hasAllFilesAccess()) {
			return;
		}
		OsmandApplication app = (OsmandApplication) activity.getApplicationContext();
		File configured = unreachableStorageFolder(app);
		if (configured == null) {
			return;
		}
		askedThisRun = true;
		app.showToastMessage(activity.getString(R.string.chizu_storage_access_message,
				configured.getAbsolutePath()));
		requestAllFilesAccessIfNeeded(activity);
	}
}
