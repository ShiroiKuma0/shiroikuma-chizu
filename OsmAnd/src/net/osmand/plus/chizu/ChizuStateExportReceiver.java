package net.osmand.plus.chizu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * shiroikuma fork: the 保存復元 state-export contract — 白い熊 自由作業盤 fires a
 * token-gated broadcast, this app exports itself headlessly and replies with the written
 * path and size.
 *
 * <pre>
 * &lt;pkg&gt;.action.LIST_CATEGORIES  token → OK: + one "id\tlabel[\tparent]" line per category
 * &lt;pkg&gt;.action.EXPORT_STATE     token [path] [items] [progress_action]
 *                              → OK:&lt;path&gt;|&lt;bytes&gt;|&lt;human size&gt;|&lt;n&gt; categories
 * </pre>
 *
 * The reply is a fresh broadcast — never a Binder (ResultReceiver / PendingIntent /
 * Messenger), which EMUI will not reliably carry between third-party apps; the ordered
 * result is set too but never relied upon. Exactly one terminal reply per request.
 */
public class ChizuStateExportReceiver extends BroadcastReceiver {

	private static final String TAG = "ChizuAutomation";

	private static final String SUFFIX_EXPORT = ".action.EXPORT_STATE";
	private static final String SUFFIX_LIST = ".action.LIST_CATEGORIES";

	private static final String EXTRA_TOKEN = "token";
	private static final String EXTRA_PATH = "path";
	private static final String EXTRA_ITEMS = "items";
	private static final String EXTRA_PROGRESS_ACTION = "progress_action";
	private static final String EXTRA_REPLY_ACTION = "reply_action";
	private static final String EXTRA_REPLY_PACKAGE = "reply_package";
	private static final String EXTRA_REPLY_ID = "reply_id";
	private static final String EXTRA_RESULT = "result";

	/** How long a cold-started process may wait for the app to finish initializing. */
	private static final long INIT_TIMEOUT_MS = 180_000;
	private static final long PROGRESS_INTERVAL_MS = 500;

	@Override
	public void onReceive(@NonNull Context context, @NonNull Intent intent) {
		String action = intent.getAction();
		if (action == null) {
			return;
		}
		OsmandApplication app = (OsmandApplication) context.getApplicationContext();
		String replyAction = intent.getStringExtra(EXTRA_REPLY_ACTION);
		String replyPackage = intent.getStringExtra(EXTRA_REPLY_PACKAGE);
		String replyId = intent.getStringExtra(EXTRA_REPLY_ID);
		String progressAction = intent.getStringExtra(EXTRA_PROGRESS_ACTION);
		String token = intent.getStringExtra(EXTRA_TOKEN);
		String items = intent.getStringExtra(EXTRA_ITEMS);
		String path = intent.getStringExtra(EXTRA_PATH);
		boolean ordered = isOrderedBroadcast();

		PendingResult pending = goAsync();
		Replier replier = new Replier(app, pending, ordered, replyAction, replyPackage, replyId);

		if (!ChizuAutomation.isEnabled(app)) {
			replier.send("ERROR:automation disabled");
			return;
		}
		if (!ChizuAutomation.matches(app, token)) {
			replier.send("ERROR:bad token");
			return;
		}
		if (action.endsWith(SUFFIX_LIST)) {
			new Thread(() -> {
				awaitInit(app);
				replier.send(listCategories(app));
			}, "chizu-list-categories").start();
		} else if (action.endsWith(SUFFIX_EXPORT)) {
			new Thread(() -> runExport(app, replier, progressAction, replyPackage, replyId, items, path),
					"chizu-state-export").start();
		} else {
			replier.send("ERROR:unknown action");
		}
	}

	// ---------- LIST_CATEGORIES ----------

	@NonNull
	private String listCategories(@NonNull OsmandApplication app) {
		StringBuilder builder = new StringBuilder("OK:");
		boolean first = true;
		for (ChizuBackup.Cat cat : ChizuBackup.catalogue(app)) {
			if (!first) {
				builder.append('\n');
			}
			first = false;
			builder.append(cat.id).append('\t').append(oneLine(cat.label));
			if (cat.parent != null) {
				builder.append('\t').append(cat.parent);
			}
		}
		return builder.toString();
	}

	@NonNull
	private String oneLine(@NonNull String text) {
		return text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
	}

	// ---------- EXPORT_STATE ----------

	private void runExport(@NonNull OsmandApplication app, @NonNull Replier replier,
			@Nullable String progressAction, @Nullable String replyPackage, @Nullable String replyId,
			@Nullable String items, @Nullable String path) {
		try {
			awaitInit(app);
			ChizuBackup.Selection selection = ChizuBackup.select(app, items);
			if (selection == null) {
				replier.send("ERROR:unknown category in items: " + items);
				return;
			}
			if (selection.count == 0) {
				replier.send("ERROR:no categories selected");
				return;
			}
			ChizuBackup.Dest dest = resolveDest(app, path, replier);
			if (dest == null) {
				return; // resolveDest replied already
			}
			ChizuBackup.Progress progress = progressAction != null && replyPackage != null
					? new ProgressSender(app, progressAction, replyPackage, replyId)
					: null;
			ChizuBackup.Result result =
					ChizuBackup.export(app, selection, dest, progress, new AtomicBoolean());
			if (!result.ok) {
				replier.send("ERROR:" + (result.error != null ? result.error : "export failed"));
				return;
			}
			replier.send("OK:" + result.path + "|" + result.bytes + "|"
					+ ChizuBackup.formatSize(result.bytes) + "|" + result.categories + " categories");
		} catch (Throwable error) {
			Log.e(TAG, "export failed", error);
			replier.send("ERROR:" + error.getClass().getSimpleName());
		}
	}

	/**
	 * Directory precedence: the {@code path} extra → the configured backup directory →
	 * {@code ERROR:no-directory}. Writing to an arbitrary absolute path needs All-files
	 * access; without it {@code path} is ignored in favour of the configured SAF directory.
	 */
	@Nullable
	private ChizuBackup.Dest resolveDest(@NonNull OsmandApplication app, @Nullable String path,
			@NonNull Replier replier) {
		String name = ChizuBackup.fileName();
		if (path != null && !path.trim().isEmpty()) {
			if (ChizuStorage.hasAllFilesAccess()) {
				File dir = new File(path.trim());
				if (!dir.exists() && !dir.mkdirs()) {
					replier.send("ERROR:cannot create directory " + dir.getAbsolutePath());
					return null;
				}
				if (!dir.isDirectory()) {
					replier.send("ERROR:not a directory: " + dir.getAbsolutePath());
					return null;
				}
				return new ChizuBackup.FileDest(new File(dir, name));
			}
			DocumentFile configured = ChizuBackup.getDir(app);
			if (configured == null) {
				replier.send("ERROR:no-storage-access");
				return null;
			}
			Log.w(TAG, "no All-files access — ignoring path, writing to the configured directory");
			return new ChizuBackup.SafDest(app, configured, name);
		}
		DocumentFile configured = ChizuBackup.getDir(app);
		if (configured == null) {
			replier.send("ERROR:no-directory");
			return null;
		}
		return new ChizuBackup.SafDest(app, configured, name);
	}

	/** A cold-started process must let the app finish loading before anything is collected. */
	private void awaitInit(@NonNull OsmandApplication app) {
		long deadline = SystemClock.elapsedRealtime() + INIT_TIMEOUT_MS;
		while (app.isApplicationInitializing() && SystemClock.elapsedRealtime() < deadline) {
			try {
				Thread.sleep(200);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	// ---------- the reply ----------

	/** Exactly one terminal reply per request — an async success and a sync error cannot both fire. */
	private static class Replier {

		private final OsmandApplication app;
		private final BroadcastReceiver.PendingResult pending;
		private final boolean ordered;
		private final String replyAction;
		private final String replyPackage;
		private final String replyId;
		private final AtomicBoolean sent = new AtomicBoolean();

		Replier(@NonNull OsmandApplication app, @NonNull BroadcastReceiver.PendingResult pending, boolean ordered,
				@Nullable String replyAction, @Nullable String replyPackage, @Nullable String replyId) {
			this.app = app;
			this.pending = pending;
			this.ordered = ordered;
			this.replyAction = replyAction;
			this.replyPackage = replyPackage;
			this.replyId = replyId;
		}

		void send(@NonNull String result) {
			if (!sent.compareAndSet(false, true)) {
				return;
			}
			Log.i(TAG, "reply " + replyId + ": " + result.split("\n")[0]);
			if (replyAction != null && replyPackage != null) {
				try {
					Intent reply = new Intent(replyAction);
					reply.setPackage(replyPackage);
					reply.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
					reply.putExtra(EXTRA_REPLY_ID, replyId != null ? replyId : "");
					reply.putExtra(EXTRA_RESULT, result);
					app.sendBroadcast(reply);
				} catch (Exception e) {
					Log.e(TAG, "reply broadcast failed", e);
				}
			} else {
				Log.w(TAG, "no reply_action/reply_package — nowhere to reply to");
			}
			// correct AOSP behaviour, but EMUI severs it between third-party apps: never the only reply
			if (ordered) {
				try {
					pending.setResultData(result);
				} catch (Exception ignored) {
				}
			}
			try {
				pending.finish();
			} catch (Exception ignored) {
			}
		}
	}

	// ---------- progress ----------

	/** Real numbers, never a percentage; at most one broadcast every 500 ms plus a final one. */
	private static class ProgressSender implements ChizuBackup.Progress {

		private final OsmandApplication app;
		private final String progressAction;
		private final String replyPackage;
		private final String replyId;
		private long lastSent;

		ProgressSender(@NonNull OsmandApplication app, @NonNull String progressAction,
				@NonNull String replyPackage, @Nullable String replyId) {
			this.app = app;
			this.progressAction = progressAction;
			this.replyPackage = replyPackage;
			this.replyId = replyId;
		}

		@Override
		public void onProgress(long current, long total, @NonNull String unit, @NonNull String text) {
			long now = SystemClock.elapsedRealtime();
			boolean last = total > 0 && current >= total;
			if (!last && now - lastSent < PROGRESS_INTERVAL_MS) {
				return;
			}
			lastSent = now;
			try {
				Intent intent = new Intent(progressAction);
				intent.setPackage(replyPackage);
				intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
				intent.putExtra(EXTRA_REPLY_ID, replyId != null ? replyId : "");
				intent.putExtra("app", app.getString(R.string.app_name));
				intent.putExtra("text", text);
				intent.putExtra("current", current);
				intent.putExtra("total", total);
				intent.putExtra("unit", unit);
				app.sendBroadcast(intent);
			} catch (Exception e) {
				Log.e(TAG, "progress broadcast failed", e);
			}
		}
	}
}
