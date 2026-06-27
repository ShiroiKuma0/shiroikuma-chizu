package net.osmand.plus.myplaces.favorites;

import static net.osmand.IndexConstants.BACKUP_INDEX_DIR;
import static net.osmand.IndexConstants.FAVORITES_INDEX_DIR;
import static net.osmand.IndexConstants.GPX_FILE_EXT;
import static net.osmand.IndexConstants.ZIP_EXT;
import static net.osmand.shared.IndexConstants.TMP_FILE_EXT;
import static net.osmand.shared.gpx.GpxFile.XML_COLON;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.CallbackWithObject;
import net.osmand.PlatformUtil;
import net.osmand.data.FavouritePoint;
import net.osmand.plus.OsmAndTaskManager;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.Version;
import net.osmand.plus.myplaces.favorites.SaveFavoritesTask.SaveFavoritesListener;
import net.osmand.plus.shared.SharedUtil;
import net.osmand.plus.track.helpers.GpxFileLoaderTask;
import net.osmand.plus.utils.OsmAndFormatter;
import net.osmand.shared.gpx.GpxFile;
import net.osmand.shared.gpx.GpxUtilities.PointsGroup;
import net.osmand.util.Algorithms;

import org.apache.commons.logging.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FavouritesFileHelper {

	private static final Log log = PlatformUtil.getLog(FavouritesFileHelper.class);

	private static final String TIME_PATTERN = "yyyy-MM-dd_HHmmss";

	private static final int BACKUP_MAX_COUNT = 10;
	private static final int BACKUP_MAX_PER_DAY = 2; // The third one is the current backup

	public static final String FAV_FILE_PREFIX = "favorites";
	public static final String FAV_GROUP_NAME_SEPARATOR = "-";
	public static final String LEGACY_FAV_FILE_PREFIX = "favourites";
	public static final String BAK_FILE_SUFFIX = "_bak";

	public static final String SUBFOLDER_PLACEHOLDER = "_%_";

	private static final String PENDING_DELETIONS_SUFFIX = "_deletions";

	private final OsmandApplication app;
	private final ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();

	// Lock object to synchronize access to the pending task state
	private final Object taskLock = new Object();
	@Nullable
	private SaveFavoritesTask runningSaveTask;

	protected FavouritesFileHelper(@NonNull OsmandApplication app) {
		this.app = app;
	}

	@NonNull
	protected File getInternalFile() {
		return app.getFileStreamPath(LEGACY_FAV_FILE_PREFIX + BAK_FILE_SUFFIX + GPX_FILE_EXT);
	}

	@NonNull
	public File getLegacyExternalFile() {
		return new File(app.getAppPath(null), LEGACY_FAV_FILE_PREFIX + GPX_FILE_EXT);
	}

	@NonNull
	public File getExternalFile(@NonNull FavoriteGroup group) {
		File favDir = getExternalDir();
		String fileName = group.getName().isEmpty() ? FAV_FILE_PREFIX
				: FAV_FILE_PREFIX + FAV_GROUP_NAME_SEPARATOR + getGroupFileName(group.getName());
		return new File(favDir, fileName + GPX_FILE_EXT);
	}

	@NonNull
	public File getExternalDir() {
		File favFolder = app.getAppPath(FAVORITES_INDEX_DIR);
		if (!favFolder.exists()) {
			favFolder.mkdir();
		}
		return favFolder;
	}

	@NonNull
	private File getPendingDeletionsFile() {
		return app.getFileStreamPath(FAV_FILE_PREFIX + PENDING_DELETIONS_SUFFIX + TMP_FILE_EXT);
	}

	public void savePendingDeletions(@Nullable Collection<FavouritePoint> points,
	                                 @Nullable Collection<FavoriteGroup> groups) {
		List<String> lines = new ArrayList<>();

		if (points != null) {
			for (FavouritePoint p : points) {
				lines.add(PendingFavoriteDeletions.serializePoint(p.getKey()));
			}
		}
		if (groups != null) {
			for (FavoriteGroup g : groups) {
				lines.add(PendingFavoriteDeletions.serializeGroup(g.getName()));
			}
		}

		if (!lines.isEmpty()) {
			appendPendingDeletionLines(lines);
		}
	}

	private void appendPendingDeletionLines(@NonNull Collection<String> lines) {
		File file = getPendingDeletionsFile();
		try (BufferedWriter bw = new BufferedWriter(new FileWriter(file, true))) {
			for (String line : lines) {
				bw.write(line);
				bw.newLine();
			}
			bw.flush();
		} catch (IOException e) {
			log.error("appendPendingDeletionLines failed", e);
		}
	}

	@NonNull
	public PendingFavoriteDeletions loadPendingDeletions() {
		PendingFavoriteDeletions result = new PendingFavoriteDeletions();
		File file = getPendingDeletionsFile();
		if (!file.exists()) {
			return result;
		}
		try (BufferedReader br = new BufferedReader(new FileReader(file))) {
			String line;
			while ((line = br.readLine()) != null) {
				String trimmed = line.trim();
				if (!trimmed.isEmpty()) {
					result.deserializeLine(trimmed);
				}
			}
		} catch (IOException e) {
			log.error("loadPendingDeletions failed", e);
		}
		return result;
	}

	public void clearPendingDeletions() {
		File file = getPendingDeletionsFile();
		if (file.exists() && !file.delete()) {
			log.warn("clearPendingDeletions: failed to delete " + file.getAbsolutePath());
		}
	}

	@NonNull
	public Map<String, FavoriteGroup> loadInternalGroups() {
		Map<String, FavoriteGroup> groups = new LinkedHashMap<>();
		File file = getInternalFile();
		if (file.exists()) {
			loadFileGroups(file, groups, false);
		}
		return groups;
	}

	@NonNull
	public Map<String, FavoriteGroup> loadExternalGroups() {
		Map<String, FavoriteGroup> groups = new LinkedHashMap<>();
		File[] files = getFavoritesFiles();
		if (!Algorithms.isEmpty(files)) {
			for (File file : files) {
				if (file.exists()) {
					loadFileGroups(file, groups, false);
				}
			}
		}
		return groups;
	}

	private void loadFileGroups(@NonNull File file, @NonNull Map<String, FavoriteGroup> groups, boolean async) {
		CallbackWithObject<GpxFile> callback = gpxFile -> {
			if (gpxFile.getError() == null) {
				collectFavoriteGroups(gpxFile, groups);
			}
			return true;
		};
		if (async) {
			loadGpxFile(file, callback);
		} else {
			loadGpxFileSync(file, callback);
		}
	}

	public void loadGpxFile(@NonNull File file, @NonNull CallbackWithObject<GpxFile> callback) {
		GpxFileLoaderTask loaderTask = new GpxFileLoaderTask(file, null, callback);
		OsmAndTaskManager.executeTask(loaderTask, singleThreadExecutor);
	}

	public void loadGpxFileSync(@NonNull File file, @NonNull CallbackWithObject<GpxFile> callback) {
		GpxFileLoaderTask loaderTask = new GpxFileLoaderTask(file, null, null);
		try {
			GpxFile gpxFile = OsmAndTaskManager.executeTask(loaderTask, singleThreadExecutor).get();
			callback.processResult(gpxFile);
		} catch (ExecutionException | InterruptedException e) {
			log.error(e);
		}
	}

	public void saveFavoritesIntoFile(@NonNull List<FavoriteGroup> groups, boolean saveAllGroups,
	                                  @Nullable SaveFavoritesListener listener) {
		SaveFavoritesParams newParams = new SaveFavoritesParams(groups, saveAllGroups, listener);

		synchronized (taskLock) {
			if (runningSaveTask != null) {
				newParams = runningSaveTask.getParams().merge(newParams);
				runningSaveTask.cancel(false);
			}

			SaveFavoritesTask task = new SaveFavoritesTask(this, newParams);
			runningSaveTask = task;
			OsmAndTaskManager.executeTask(task, singleThreadExecutor);
		}
	}

	public void saveFavoritesIntoFileSync(@NonNull List<FavoriteGroup> groups, boolean saveAllGroups,
	                                      @Nullable SaveFavoritesListener listener) {
		SaveFavoritesParams params = new SaveFavoritesParams(groups, saveAllGroups, listener);
		SaveFavoritesTask task = new SaveFavoritesTask(this, params);
		try {
			OsmAndTaskManager.executeTask(task, singleThreadExecutor).get();
		} catch (ExecutionException | InterruptedException e) {
			log.error(e);
		}
	}

	void onSaveTaskFinished(@NonNull SaveFavoritesTask task, boolean cancelled) {
		synchronized (taskLock) {
			if (runningSaveTask == task) {
				runningSaveTask = null;
				if (!cancelled) {
					clearPendingDeletions();
				}
			}
		}
	}

	public void collectFavoriteGroups(@NonNull GpxFile gpxFile, @NonNull Map<String, FavoriteGroup> favoriteGroups) {
		Map<String, PointsGroup> pointsGroups = gpxFile.getPointsGroups();
		boolean singleGroupFile = pointsGroups.size() == 1;
		File file = !Algorithms.isEmpty(gpxFile.getPath()) ? new File(gpxFile.getPath()) : null;
		boolean useFileMetadata = singleGroupFile && file != null && file.exists();
		for (Map.Entry<String, PointsGroup> entry : pointsGroups.entrySet()) {
			String key = entry.getKey();
			PointsGroup pointsGroup = entry.getValue();
			FavoriteGroup favoriteGroup = FavoriteGroup.fromPointsGroup(pointsGroup);
			if (useFileMetadata) {
				favoriteGroup.setSize(file.length());
				favoriteGroup.setTimeModified(gpxFile.getModifiedTime());
			} else {
				FavoriteGroup existingGroup = favoriteGroups.get(key);
				if (existingGroup != null) {
					favoriteGroup.copyFileMetadata(existingGroup);
				}
			}
			favoriteGroups.put(key, favoriteGroup);
		}
	}

	@Nullable
	public File[] getFavoritesFiles() {
		File dir = app.getAppPath(FAVORITES_INDEX_DIR);
		if (!dir.exists() || !dir.isDirectory()) {
			return null;
		}
		return dir.listFiles((d, name) ->
				name.startsWith(FAV_FILE_PREFIX + FAV_GROUP_NAME_SEPARATOR)
						|| name.equals(FAV_FILE_PREFIX + GPX_FILE_EXT)
						|| name.equals(LEGACY_FAV_FILE_PREFIX + GPX_FILE_EXT));
	}

	@NonNull
	public GpxFile asGpxFile(@NonNull List<FavoriteGroup> favoriteGroups) {
		GpxFile gpxFile = new GpxFile(Version.getFullVersion(app));
		for (FavoriteGroup group : favoriteGroups) {
			gpxFile.addPointsGroup(group.toPointsGroup(app));
		}
		return gpxFile;
	}

	@Nullable
	public Exception saveFile(@NonNull List<FavoriteGroup> favoriteGroups, @NonNull File file) {
		GpxFile gpx = asGpxFile(favoriteGroups);
		return SharedUtil.writeGpxFile(file, gpx);
	}

	@NonNull
	private File getBackupsFolder() {
		File folder = new File(app.getAppPath(null), BACKUP_INDEX_DIR);
		if (!folder.exists()) {
			folder.mkdirs();
		}
		return folder;
	}

	@NonNull
	protected File getBackupFile() {
		clearOldBackups(getBackupFilesForToday(), BACKUP_MAX_PER_DAY);
		String baseName = FAV_FILE_PREFIX + BAK_FILE_SUFFIX + "_" + formatTime(System.currentTimeMillis());
		return new File(getBackupsFolder(), baseName + GPX_FILE_EXT + ZIP_EXT);
	}

	@NonNull
	private List<File> getBackupFilesForToday() {
		List<File> result = new ArrayList<>();
		List<File> files = getBackupFiles();
		long now = System.currentTimeMillis();
		for (File file : files) {
			if (OsmAndFormatter.isSameDay(now, file.lastModified())) {
				result.add(file);
			}
		}
		return result;
	}

	@NonNull
	public List<File> getBackupFiles() {
		List<File> backupFiles = new ArrayList<>();
		File[] files = getBackupsFolder().listFiles();
		if (!Algorithms.isEmpty(files)) {
			for (File file : files) {
				if (file.getName().endsWith(GPX_FILE_EXT + ZIP_EXT)) {
					backupFiles.add(file);
				}
			}
		}
		return backupFiles;
	}

	protected void clearOldBackups() {
		clearOldBackups(getBackupFiles(), BACKUP_MAX_COUNT);
	}

	private void clearOldBackups(@NonNull List<File> files, int maxCount) {
		if (files.size() >= maxCount) {
			Collections.sort(files, (f1, f2) -> {
				return Long.compare(f2.lastModified(), f1.lastModified());
			});
			for (int i = files.size(); i > maxCount; --i) {
				File oldest = files.get(i - 1);
				oldest.delete();
			}
		}
	}

	@NonNull
	private static String formatTime(long time) {
		SimpleDateFormat format = getTimeFormatter();
		return format.format(new Date(time));
	}

	@NonNull
	private static SimpleDateFormat getTimeFormatter() {
		SimpleDateFormat format = new SimpleDateFormat(TIME_PATTERN, Locale.US);
		format.setTimeZone(TimeZone.getTimeZone("UTC"));
		return format;
	}

	@NonNull
	public static String getGroupFileName(@NonNull String groupName) {
		if (groupName.contains("/")) {
			return groupName.replaceAll("/", SUBFOLDER_PLACEHOLDER);
		}
		if (groupName.contains(":")) {
			return groupName.replaceAll(":", XML_COLON);
		}
		return groupName;
	}

	@NonNull
	public static String getGroupName(@NonNull String fileName) {
		if (fileName.contains(SUBFOLDER_PLACEHOLDER)) {
			return fileName.replaceAll(SUBFOLDER_PLACEHOLDER, "/");
		}
		if (fileName.contains(XML_COLON)) {
			return fileName.replaceAll(XML_COLON, ":");
		}
		return fileName;
	}
}
