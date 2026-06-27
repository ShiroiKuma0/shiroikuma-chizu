package net.osmand.plus.myplaces.favorites;

import static net.osmand.IndexConstants.ZIP_EXT;

import android.os.AsyncTask;

import androidx.annotation.NonNull;

import net.osmand.PlatformUtil;
import net.osmand.data.FavouritePoint;
import net.osmand.plus.shared.SharedUtil;
import net.osmand.shared.gpx.GpxFile;
import net.osmand.util.Algorithms;

import org.apache.commons.logging.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class SaveFavoritesTask extends AsyncTask<Void, String, Void> {

	private static final Log log = PlatformUtil.getLog(SaveFavoritesTask.class);

	private final FavouritesFileHelper helper;
	private final SaveFavoritesParams params;

	public SaveFavoritesTask(@NonNull FavouritesFileHelper helper,
	                         @NonNull SaveFavoritesParams params) {
		this.helper = helper;
		this.params = params;
	}

	@NonNull
	public SaveFavoritesParams getParams() {
		return params;
	}

	@Override
	protected Void doInBackground(Void... voids) {
		if (params.getSaveAllGroups()) {
			saveAllGroups(params.getGroups());
		} else {
			saveSelectedGroupsOnly(params.getGroups());
		}
		return null;
	}

	private void saveAllGroups(@NonNull List<FavoriteGroup> groups) {
		try {
			PendingFavoriteDeletions pendingDeletions = helper.loadPendingDeletions();
			Set<String> deletedPointKeys = pendingDeletions.getPointKeys();

			if (isCancelled()) {
				return;
			}
			saveExternalFiles(groups, deletedPointKeys);

			if (isCancelled()) {
				return;
			}
			// Internal file is a monolithic snapshot of all groups
			File internalFile = helper.getInternalFile();
			helper.saveFile(groups, internalFile);

			if (isCancelled()) {
				return;
			}
			backup(helper.getBackupFile(), internalFile);

		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
	}

	private void saveSelectedGroupsOnly(@NonNull List<FavoriteGroup> groupsToSave) {
		try {
			// No need to touch internal file or backup
			// Changes will be picked up during next loadFavorites()
			for (FavoriteGroup group : groupsToSave) {
				if (isCancelled()) {
					return;
				}
				saveFavoriteGroup(group);
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
	}

	private void loadGPXFiles(@NonNull Map<String, FavoriteGroup> favoriteGroups) {
		File[] files = helper.getFavoritesFiles();
		if (!Algorithms.isEmpty(files)) {
			for (File file : files) {
				if (isCancelled()) {
					return;
				}
				GpxFile gpxFile = SharedUtil.loadGpxFile(file);
				if (gpxFile.getError() == null) {
					helper.collectFavoriteGroups(gpxFile, favoriteGroups);
				}
			}
		}
	}

	private void saveExternalFiles(@NonNull List<FavoriteGroup> localGroups,
	                               @NonNull Set<String> deletedPointKeys) {
		Map<String, FavoriteGroup> fileGroups = new LinkedHashMap<>();
		loadGPXFiles(fileGroups);
		if (isCancelled()) {
			return;
		}
		cleanupOrphanedGroupFiles(localGroups, fileGroups);
		saveLocalGroups(localGroups, fileGroups, deletedPointKeys);
	}

	private void cleanupOrphanedGroupFiles(@NonNull List<FavoriteGroup> localGroups,
	                                       @NonNull Map<String, FavoriteGroup> fileGroups) {
		for (FavoriteGroup fileGroup : fileGroups.values()) {
			boolean hasLocalGroup = false;
			for (FavoriteGroup group : localGroups) {
				if (Algorithms.stringsEqual(group.getName(), fileGroup.getName())) {
					hasLocalGroup = true;
					break;
				}
			}
			if (!hasLocalGroup) {
				helper.getExternalFile(fileGroup).delete();
			}
		}
	}

	private void saveLocalGroups(@NonNull List<FavoriteGroup> localGroups,
	                             @NonNull Map<String, FavoriteGroup> fileGroups,
	                             @NonNull Set<String> deleted) {
		for (FavoriteGroup localGroup : localGroups) {
			if (isCancelled()) {
				return;
			}
			FavoriteGroup fileGroup = fileGroups.get(localGroup.getName());
			Map<String, FavouritePoint> all = new LinkedHashMap<>();
			if (fileGroup != null) {
				for (FavouritePoint point : fileGroup.getPoints()) {
					String key = point.getKey();
					if (!deleted.contains(key)) {
						all.put(key, point);
					}
				}
			}
			// Copy to avoid mutating localGroup.getPoints() while iterating
			List<FavouritePoint> localPoints = new ArrayList<>(localGroup.getPoints());
			for (FavouritePoint point : localPoints) {
				all.remove(point.getKey());
			}
			if (!all.isEmpty()) {
				localGroup.getPoints().addAll(all.values());
			}
			if (!localGroup.equals(fileGroup)) {
				saveFavoriteGroup(localGroup);
			}
		}
	}

	private void saveFavoriteGroup(@NonNull FavoriteGroup group) {
		File externalFile = helper.getExternalFile(group);
		Exception exception = helper.saveFile(Collections.singletonList(group), externalFile);
		if (exception != null) {
			log.error(exception);
		} else if (externalFile.exists()) {
			group.setSize(externalFile.length());
			group.setTimeModified(externalFile.lastModified());
		}
	}

	private void backup(@NonNull File backupFile, @NonNull File externalFile) {
		String name = backupFile.getName();
		String nameNoExt = name.substring(0, name.lastIndexOf(ZIP_EXT));
		InputStream fis = null;
		ZipOutputStream zos = null;
		try {
			File file = new File(backupFile.getParentFile(), backupFile.getName());
			zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(file)));
			fis = new BufferedInputStream(new FileInputStream(externalFile));
			zos.putNextEntry(new ZipEntry(nameNoExt));
			Algorithms.streamCopy(fis, zos);
			zos.closeEntry();
			zos.flush();
			zos.finish();
		} catch (Exception e) {
			log.warn("Backup failed", e);
		} finally {
			Algorithms.closeStream(zos);
			Algorithms.closeStream(fis);
		}
		helper.clearOldBackups();
	}

	@Override
	protected void onPostExecute(Void result) {
		helper.onSaveTaskFinished(this, false);
		for (SaveFavoritesListener listener : params.getListeners()) {
			listener.onSavingFavoritesFinished();
		}
	}

	@Override
	protected void onCancelled() {
		helper.onSaveTaskFinished(this, true);
	}

	public interface SaveFavoritesListener {
		void onSavingFavoritesFinished();
	}
}