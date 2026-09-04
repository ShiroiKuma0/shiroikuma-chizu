package net.osmand.plus.chizu;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.utils.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * shiroikuma fork: where a data-door export or import actually runs. §2a of the 保存復元 contract.
 *
 * <h3>Why a foreground service and not the provider call</h3>
 *
 * The call returns in milliseconds; this can run for minutes.
 *
 * <ul>
 * <li><b>A binder call holds the caller.</b> 応用管理 is drawing a list — a multi-minute synchronous
 *     call would freeze its UI, report no progress and refuse cancellation.</li>
 * <li><b>A backgrounded app writing for minutes is frozen mid-stream on this phone</b>, which yields
 *     a truncated archive underneath a success reply: the worst possible failure, because it is
 *     indistinguishable from a good backup until the day it is restored.</li>
 * </ul>
 *
 * <h3>The descriptor</h3>
 *
 * Already duplicated by {@link ChizuAutomationProvider} before it got here, because the original
 * belongs to the binder transaction and is closed the moment {@code call()} returns. This service
 * owns the copy and closes it in a {@code finally} — leaking one would hold the caller's file open,
 * and a caller cannot checksum or encrypt a file that is still open.
 */
public class ChizuAutomationDataService extends Service {

	private static final String TAG = "ChizuAutomation";

	private static final String CHANNEL = "chizu_automation_data";
	private static final int NOTIFICATION_ID = 9714;

	private static final String EXTRA_JOB = "job";
	private static final String EXTRA_IMPORTING = "importing";

	/** How long a claimed job may sit undelivered before its descriptor is reclaimed. */
	private static final long UNDELIVERED_SECONDS = 60;

	/** How long a cold-started process may wait for the app to finish initializing. */
	private static final long INIT_TIMEOUT_MS = 180_000;

	private static final String IMPORT_TEMP_NAME = "chizu_automation_import.zip";
	private static final long SPOOL_REPORT_BYTES = 1L << 20;

	/**
	 * The descriptor's way across, because an Intent is the wrong vehicle for one.
	 *
	 * <p>A {@link ParcelFileDescriptor} in an Intent extra is duplicated by the system on delivery
	 * and the copy's lifetime stops being ours to reason about. Handing it through a map keyed by
	 * the job id keeps exactly one open descriptor with exactly one owner — this service, which
	 * closes it in a {@code finally}.
	 */
	private static final ConcurrentHashMap<String, ParcelFileDescriptor> HANDOVER =
			new ConcurrentHashMap<>();

	private static final ScheduledExecutorService REAPER =
			Executors.newSingleThreadScheduledExecutor(runnable -> {
				Thread thread = new Thread(runnable, "chizu-handover-reaper");
				thread.setDaemon(true);
				return thread;
			});

	/** The one terminal answer a job is allowed. */
	private interface Reply {
		void send(@NonNull String result);
	}

	@Nullable
	@Override
	public IBinder onBind(@Nullable Intent intent) {
		return null;
	}

	// ---------- claiming a job ----------

	/**
	 * Claim the job and hand the descriptor over.
	 *
	 * @return null when the service is on its way, otherwise the {@code ERROR:} line to answer the
	 *         caller with. <b>On a failure the descriptor is already closed here</b> — the provider
	 *         must not close it a second time.
	 */
	@Nullable
	static String start(@NonNull Context context, @NonNull String jobId,
			@NonNull ParcelFileDescriptor fd, boolean importing, @Nullable Bundle extras) {
		HANDOVER.put(jobId, fd);
		try {
			ContextCompat.startForegroundService(context, intentFor(context, jobId, importing, extras));
			// A start can also be ACCEPTED and never DELIVERED — the system drops it, the process is
			// killed between the two, EMUI decides otherwise. Nothing throws, so the catch below
			// never fires, and the caller's descriptor would sit here held open for the life of the
			// process while the caller waits for a reply that cannot come.
			REAPER.schedule(() -> abandon(jobId), UNDELIVERED_SECONDS, TimeUnit.SECONDS);
			return null;
		} catch (Exception e) {
			// A provider call() is a BACKGROUND start, and API 31+ refuses one with
			// ForegroundServiceStartNotAllowedException unless the app is exempt from battery
			// optimisation. Left unguarded this strands the caller's open descriptor with nothing
			// alive to close it, AND throws out of call() across the binder as a RuntimeException —
			// which the contract forbids: a refusal is returned, never thrown.
			Log.e(TAG, "cannot start the data service", e);
			HANDOVER.remove(jobId);
			close(fd);
			return "ERROR:cannot start data service: " + e.getClass().getSimpleName();
		}
	}

	/**
	 * Reclaim a descriptor whose service never arrived. A no-op in the normal case, where
	 * {@link #onStartCommand} drained the entry within milliseconds.
	 */
	private static void abandon(@NonNull String jobId) {
		ParcelFileDescriptor stranded = HANDOVER.remove(jobId);
		if (stranded == null) {
			return;
		}
		Log.w(TAG, "reclaiming an undelivered descriptor for job " + jobId);
		close(stranded);
		ChizuAutomationJobs.finish(jobId);
	}

	@NonNull
	private static Intent intentFor(@NonNull Context context, @NonNull String jobId, boolean importing,
			@Nullable Bundle extras) {
		Intent intent = new Intent(context, ChizuAutomationDataService.class);
		intent.putExtra(EXTRA_JOB, jobId);
		intent.putExtra(EXTRA_IMPORTING, importing);
		if (extras != null) {
			intent.putExtra(ChizuAutomationProvider.KEY_ITEMS,
					extras.getString(ChizuAutomationProvider.KEY_ITEMS));
			intent.putExtra(ChizuAutomationProvider.KEY_REPLY_ACTION,
					extras.getString(ChizuAutomationProvider.KEY_REPLY_ACTION));
			intent.putExtra(ChizuAutomationProvider.KEY_REPLY_PACKAGE,
					extras.getString(ChizuAutomationProvider.KEY_REPLY_PACKAGE));
			intent.putExtra(ChizuAutomationProvider.KEY_PROGRESS_ACTION,
					extras.getString(ChizuAutomationProvider.KEY_PROGRESS_ACTION));
		}
		return intent;
	}

	// ---------- running it ----------

	@Override
	public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
		// `importing` is read first, for the notification's sake. Hoisting the job-id read above it
		// is the natural way to write this and is exactly how a null intent turns into a crash on
		// the branch that has nothing to do.
		boolean importing = intent != null && intent.getBooleanExtra(EXTRA_IMPORTING, false);
		String jobId = intent != null ? intent.getStringExtra(EXTRA_JOB) : null;

		// FOREGROUND FIRST, before any decision that can return — including the decision to do
		// nothing. startForegroundService has already promised the platform we go foreground within
		// its window, and that promise is not conditional on our finding work to do: skipping it
		// kills the process with ForegroundServiceDidNotStartInTimeException, so a caller retrying
		// with a stale job id would CRASH the app it is backing up rather than being ignored.
		// Guarded, because the start may itself be refused — and a throw here would be that crash.
		//
		// The handover is drained in the finally rather than after the try: if startForeground
		// throws, the throw lands before the descriptor is taken out of the map and it stays held
		// open with nothing alive to close it.
		boolean wentForeground = false;
		ParcelFileDescriptor claimed = null;
		try {
			ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(importing),
					ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
			wentForeground = true;
		} catch (Exception e) {
			Log.e(TAG, "cannot go foreground", e);
		} finally {
			if (jobId != null) {
				claimed = HANDOVER.remove(jobId);
			}
		}

		if (jobId == null || claimed == null) {
			// nothing to run — a stale or already-drained job id. We still had to go foreground.
			return stop(startId);
		}

		String replyAction = intent.getStringExtra(ChizuAutomationProvider.KEY_REPLY_ACTION);
		String replyPackage = intent.getStringExtra(ChizuAutomationProvider.KEY_REPLY_PACKAGE);
		String progressAction = intent.getStringExtra(ChizuAutomationProvider.KEY_PROGRESS_ACTION);
		String items = intent.getStringExtra(ChizuAutomationProvider.KEY_ITEMS);

		ParcelFileDescriptor fd = claimed;
		String job = jobId;
		AtomicBoolean replied = new AtomicBoolean();
		Reply reply = result -> {
			// Exactly one terminal answer per job, whatever path got here — a synchronous failure
			// and an asynchronous success must never both fire.
			if (!replied.compareAndSet(false, true)) {
				return;
			}
			Log.i(TAG, "data door " + job + ": " + result);
			// No package to aim at means nobody can hear it: since API 26 an implicit broadcast
			// reaches no manifest-declared receiver, so setPackage(null) is not a wider send, it is
			// no send. Skip it rather than pretending.
			if (replyAction == null || replyAction.isEmpty()
					|| replyPackage == null || replyPackage.isEmpty()) {
				return;
			}
			try {
				Intent answer = new Intent(replyAction);
				answer.setPackage(replyPackage);
				// without this a backgrounded caller — or one never launched on a clean phone —
				// never hears the answer
				answer.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
				answer.putExtra(ChizuAutomationProvider.KEY_JOB_ID, job);
				answer.putExtra(ChizuReplier.EXTRA_REPLY_ID, job);
				answer.putExtra(ChizuReplier.EXTRA_RESULT, result);
				sendBroadcast(answer);
			} catch (Exception e) {
				Log.e(TAG, "reply broadcast failed", e);
			}
		};

		if (!wentForeground) {
			// The descriptor has left the handover by now, so nothing else would ever close it —
			// and the caller is holding an OK:<job_id> for work that cannot run. Answer rather than
			// die quietly: a silent death here shows up only on a phone without the
			// battery-optimisation exemption, which is precisely the clean-phone case.
			close(fd);
			reply.send("ERROR:cannot go foreground");
			return stop(startId);
		}

		OsmandApplication app = (OsmandApplication) getApplicationContext();
		new Thread(() -> {
			try {
				if (importing) {
					runImport(app, job, fd, replyPackage, progressAction, reply);
				} else {
					runExport(app, job, fd, items, replyPackage, progressAction, reply);
				}
			} catch (Throwable error) {
				Log.e(TAG, "data door failed", error);
				reply.send("ERROR:" + error.getClass().getSimpleName());
			} finally {
				close(fd);
				ChizuAutomationJobs.finish(job);
				stop(startId);
			}
		}, importing ? "chizu-automation-import" : "chizu-automation-export").start();
		return START_NOT_STICKY;
	}

	// ---------- export ----------

	private void runExport(@NonNull OsmandApplication app, @NonNull String jobId,
			@NonNull ParcelFileDescriptor fd, @Nullable String items, @Nullable String replyPackage,
			@Nullable String progressAction, @NonNull Reply reply) {
		AtomicBoolean cancelled = flagFor(jobId);
		// published for the whole request, not just the write: a cold-started process spends its
		// first seconds waiting for the app to initialize, and a cancel arriving then must land
		ChizuBackup.beginRun(jobId, cancelled);
		try {
			awaitInit(app);
			if (cancelled.get()) {
				reply.send("ERROR:cancelled");
				return;
			}
			ChizuBackup.Selection selection = ChizuBackup.select(app, items);
			if (selection == null) {
				reply.send("ERROR:unknown category in items: " + items);
				return;
			}
			if (selection.count == 0) {
				reply.send("ERROR:no categories selected");
				return;
			}
			ChizuProgress progress = ChizuProgress.forJob(app, progressAction, replyPackage, jobId);
			ChizuBackup.FdDest dest = new ChizuBackup.FdDest(fd);
			ChizuBackup.Result result = ChizuBackup.export(app, selection, dest, progress, cancelled);
			if (!result.ok) {
				reply.send("ERROR:" + (result.error != null ? result.error : "export failed"));
				return;
			}
			// no path in the reply: the caller owns the file and this app never had a name for it
			reply.send("OK:" + result.bytes + "|" + ChizuBackup.formatSize(result.bytes)
					+ "|" + result.categories + " categories");
		} finally {
			ChizuBackup.endRun(cancelled);
		}
	}

	// ---------- import ----------

	/**
	 * Spooled to disk, never into a byte array.
	 *
	 * <p>A 地図 archive is not a settings dump: it can carry downloaded regions and map cutouts, and
	 * reading one into memory would take the very app the caller is restoring. The stock import
	 * wants a file anyway, so the temporary file is the honest shape rather than a concession.
	 */
	private void runImport(@NonNull OsmandApplication app, @NonNull String jobId,
			@NonNull ParcelFileDescriptor fd, @Nullable String replyPackage,
			@Nullable String progressAction, @NonNull Reply reply) {
		AtomicBoolean cancelled = flagFor(jobId);
		awaitInit(app);
		if (cancelled.get()) {
			reply.send("ERROR:cancelled");
			return;
		}
		ChizuProgress progress = ChizuProgress.forJob(app, progressAction, replyPackage, jobId);
		File temp = new File(FileUtils.getTempDir(app), IMPORT_TEMP_NAME);
		long spooled = 0;
		try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd);
				OutputStream out = new FileOutputStream(temp)) {
			byte[] buffer = new byte[8192];
			long lastReport = 0;
			int read;
			while ((read = in.read(buffer)) != -1) {
				if (cancelled.get()) {
					throw new IOException("cancelled");
				}
				out.write(buffer, 0, read);
				spooled += read;
				if (progress != null && spooled - lastReport >= SPOOL_REPORT_BYTES) {
					lastReport = spooled;
					progress.onProgress(spooled, 0, "bytes",
							"Reading " + ChizuBackup.formatSize(spooled));
				}
			}
		} catch (Exception e) {
			//noinspection ResultOfMethodCallIgnored
			temp.delete();
			reply.send(cancelled.get() ? "ERROR:cancelled" : "ERROR:cannot read the archive");
			return;
		}
		if (spooled == 0) {
			//noinspection ResultOfMethodCallIgnored
			temp.delete();
			reply.send("ERROR:empty archive");
			return;
		}
		try {
			if (progress != null) {
				progress.onProgress(spooled, spooled, "bytes",
						"Restoring " + ChizuBackup.formatSize(spooled));
			}
			ChizuBackup.Result result = ChizuBackup.importArchive(app, temp);
			if (!result.ok) {
				reply.send("ERROR:" + (result.error != null ? result.error : "import failed"));
				return;
			}
			// 応用管理 force-stops this app the instant it hears success — deliberately, because a
			// running process writes its cached SharedPreferences back out at orderly shutdown and
			// would silently undo the import that just happened. That guarantee lives on its side.
			reply.send("OK:" + result.categories + " restored");
		} finally {
			//noinspection ResultOfMethodCallIgnored
			temp.delete();
		}
	}

	// ---------- plumbing ----------

	@NonNull
	private AtomicBoolean flagFor(@NonNull String jobId) {
		AtomicBoolean flag = ChizuAutomationJobs.flag(jobId);
		return flag != null ? flag : new AtomicBoolean();
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

	@NonNull
	private Notification notification(boolean importing) {
		NotificationManager manager =
				(NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
			manager.createNotificationChannel(new NotificationChannel(CHANNEL,
					getString(R.string.chizu_automation_data_channel),
					NotificationManager.IMPORTANCE_LOW));
		}
		return new NotificationCompat.Builder(this, CHANNEL)
				.setContentTitle(getString(importing
						? R.string.chizu_automation_data_importing
						: R.string.chizu_automation_data_exporting))
				.setSmallIcon(importing
						? android.R.drawable.stat_sys_download
						: android.R.drawable.stat_sys_upload)
				.setPriority(NotificationCompat.PRIORITY_LOW)
				.setOngoing(true)
				.build();
	}

	/**
	 * Leave, having satisfied the promise {@code startForegroundService} made on our behalf.
	 *
	 * <p><b>Every exit goes through here</b>, including the ones with nothing to do: once a caller
	 * has invoked {@code startForegroundService}, the platform requires this service to call
	 * {@code startForeground} within its window whatever it then decides, and it enforces that by
	 * killing the process. So the early returns above are exactly the dangerous ones.
	 */
	private int stop(int startId) {
		ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
		stopSelf(startId);
		return START_NOT_STICKY;
	}

	private static void close(@NonNull ParcelFileDescriptor fd) {
		try {
			fd.close();
		} catch (Exception ignored) {
		}
	}
}
