package net.osmand.plus.chizu;

import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;

/**
 * shiroikuma fork: the §3 progress broadcast, shared by both halves of the automation contract.
 *
 * <p>Real numbers, never a percentage — 白い熊's explicit requirement. At most one broadcast every
 * 500 ms, plus a final one at completion.
 *
 * <p><b>One sender, parameterised — not two.</b> The data door needs exactly the shape the
 * broadcast receiver already sends, and a second copy is how the two drift apart. The only
 * difference is the correlation id: a §1 request is correlated by its {@code reply_id}, a data-door
 * job by its {@code job_id}, which travels in <i>both</i> extras so a caller keyed on either finds
 * it.
 *
 * <p>A caller that passed no {@code progress_action} — or no package to aim it at — gets no
 * broadcasts at all rather than a stream nobody can hear: since API 26 an implicit broadcast
 * reaches no manifest-declared receiver, so {@code setPackage(null)} is not a wider send, it is no
 * send. {@link #create} answers null in that case and the export runs without reporting.
 */
public class ChizuProgress implements ChizuBackup.Progress {

	private static final String TAG = "ChizuAutomation";
	private static final long PROGRESS_INTERVAL_MS = 500;

	private final OsmandApplication app;
	private final String progressAction;
	private final String replyPackage;
	private final String correlationId;
	@Nullable
	private final String jobId;
	private long lastSent;

	private ChizuProgress(@NonNull OsmandApplication app, @NonNull String progressAction,
			@NonNull String replyPackage, @NonNull String correlationId, @Nullable String jobId) {
		this.app = app;
		this.progressAction = progressAction;
		this.replyPackage = replyPackage;
		this.correlationId = correlationId;
		this.jobId = jobId;
	}

	/** The §1 broadcast half: correlated by the request's {@code reply_id}. */
	@Nullable
	public static ChizuProgress forRequest(@NonNull OsmandApplication app,
			@Nullable String progressAction, @Nullable String replyPackage, @Nullable String replyId) {
		return create(app, progressAction, replyPackage, replyId != null ? replyId : "", null);
	}

	/** The §2a data-door half: correlated by the job id, which travels in both extras. */
	@Nullable
	public static ChizuProgress forJob(@NonNull OsmandApplication app,
			@Nullable String progressAction, @Nullable String replyPackage, @NonNull String jobId) {
		return create(app, progressAction, replyPackage, jobId, jobId);
	}

	@Nullable
	private static ChizuProgress create(@NonNull OsmandApplication app, @Nullable String progressAction,
			@Nullable String replyPackage, @NonNull String correlationId, @Nullable String jobId) {
		if (progressAction == null || progressAction.isEmpty()
				|| replyPackage == null || replyPackage.isEmpty()) {
			return null;
		}
		return new ChizuProgress(app, progressAction, replyPackage, correlationId, jobId);
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
			// without this a backgrounded or freshly installed caller never hears a thing
			intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
			intent.putExtra(ChizuReplier.EXTRA_REPLY_ID, correlationId);
			if (jobId != null) {
				intent.putExtra(ChizuAutomationProvider.KEY_JOB_ID, jobId);
			}
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
