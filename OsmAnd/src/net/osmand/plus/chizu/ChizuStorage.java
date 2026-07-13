package net.osmand.plus.chizu;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import androidx.annotation.NonNull;

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
}
