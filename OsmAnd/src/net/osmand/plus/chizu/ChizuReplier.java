package net.osmand.plus.chizu;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.OsmandApplication;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * shiroikuma fork: the reply half of the automation contract, shared by every action a
 * sister app can fire at this app.
 *
 * Exactly one terminal reply per request — an async success and a sync error cannot both
 * fire. The reply is a fresh plain broadcast at {@code reply_package} / {@code reply_action},
 * never a Binder (ResultReceiver / PendingIntent / Messenger): EMUI either drops the whole
 * broadcast or delivers it and never fires the callback. The ordered result is set too, but
 * is never the only reply.
 *
 * Values travel twice — as their own string extras, and packed into {@code result} behind the
 * {@code OK:} — so a bridge that surfaces only {@code result} still receives everything.
 */
public class ChizuReplier {

	private static final String TAG = "ChizuAutomation";

	public static final String EXTRA_REPLY_ID = "reply_id";
	public static final String EXTRA_RESULT = "result";

	private final OsmandApplication app;
	private final BroadcastReceiver.PendingResult pending;
	private final boolean ordered;
	private final String replyAction;
	private final String replyPackage;
	private final String replyId;
	private final AtomicBoolean sent = new AtomicBoolean();

	public ChizuReplier(@NonNull OsmandApplication app, @NonNull BroadcastReceiver.PendingResult pending,
			boolean ordered, @Nullable String replyAction, @Nullable String replyPackage,
			@Nullable String replyId) {
		this.app = app;
		this.pending = pending;
		this.ordered = ordered;
		this.replyAction = replyAction;
		this.replyPackage = replyPackage;
		this.replyId = replyId;
	}

	/** Ends the broadcast without answering it — what a fire-and-forget action gets. */
	public void finishSilently() {
		if (!sent.compareAndSet(false, true)) {
			return;
		}
		try {
			pending.finish();
		} catch (Exception ignored) {
		}
	}

	public void send(@NonNull String result) {
		send(result, null);
	}

	/** @param extras named values carried alongside {@code result}; strings only. */
	public void send(@NonNull String result, @Nullable Bundle extras) {
		if (!sent.compareAndSet(false, true)) {
			return;
		}
		Log.i(TAG, "reply " + replyId + ": " + result.split("\n")[0]);
		if (replyAction != null && replyPackage != null) {
			try {
				Intent reply = new Intent(replyAction);
				reply.setPackage(replyPackage);
				reply.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
				if (extras != null) {
					reply.putExtras(extras);
				}
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
