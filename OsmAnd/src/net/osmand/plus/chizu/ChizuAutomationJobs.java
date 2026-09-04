package net.osmand.plus.chizu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * shiroikuma fork: the jobs the data door has started, and the flag each of them watches to stop.
 *
 * <p>What this owns is the mapping from the id a caller was handed to a cancellation it can act
 * on, which must outlive the binder call that created it and be reachable from a service that
 * never saw the caller.
 *
 * <p>The flag is the very {@link AtomicBoolean} {@link ChizuBackup#export} polls between entries,
 * so a cancel arriving here unwinds the write at its next boundary rather than tearing it down
 * mid-{@code write()} — and the archive is never left half-written.
 */
public class ChizuAutomationJobs {

	private static final ConcurrentHashMap<String, AtomicBoolean> JOBS = new ConcurrentHashMap<>();

	private ChizuAutomationJobs() {
	}

	@NonNull
	public static String begin() {
		String id = UUID.randomUUID().toString();
		JOBS.put(id, new AtomicBoolean());
		return id;
	}

	/** The flag to hand the export core; null when the job is finished or was never real. */
	@Nullable
	public static AtomicBoolean flag(@NonNull String jobId) {
		return JOBS.get(jobId);
	}

	/**
	 * Ask a job to stop. A no-op for an id that is finished or was never real — deliberately
	 * silent: a cancel arriving after the work completed is the normal race, not an error, and
	 * answering it as one would make every well-behaved caller look broken.
	 */
	public static void cancel(@Nullable String jobId) {
		if (jobId == null) {
			return;
		}
		AtomicBoolean flag = JOBS.get(jobId);
		if (flag != null) {
			flag.set(true);
		}
	}

	public static boolean isCancelled(@NonNull String jobId) {
		AtomicBoolean flag = JOBS.get(jobId);
		return flag != null && flag.get();
	}

	public static void finish(@NonNull String jobId) {
		JOBS.remove(jobId);
	}
}
