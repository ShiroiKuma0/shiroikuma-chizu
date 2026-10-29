package net.osmand.plus.chizu;

import android.app.Dialog;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.routing.ChizuRouteCandidates;

/**
 * 白い熊 地図 渋滞回避: “Avoid jam on Jižní spojka: −6 min?” while driving. No answer within
 * twenty seconds counts as “Stay” — the driver is busy, and the same jam is not offered again
 * for ten minutes.
 */
public final class ChizuTrafficOffer {

	private static final long TIMEOUT_MS = 20_000;

	@Nullable
	private static Dialog showing;

	private ChizuTrafficOffer() {
	}

	public static void show(@NonNull MapActivity activity, @NonNull ChizuRouteCandidates chooser,
	                        int savingSeconds, @Nullable String street) {
		if (activity.isFinishing() || activity.isDestroyed()) {
			chooser.declineOffer();
			return;
		}
		if (showing != null && showing.isShowing()) {
			showing.dismiss();
		}
		int minutes = Math.max(1, Math.round(savingSeconds / 60f));
		String message = street != null
				? activity.getString(R.string.chizu_traffic_offer, street, minutes)
				: activity.getString(R.string.chizu_traffic_offer_unnamed, minutes);
		boolean[] answered = {false};
		AlertDialog dialog = new AlertDialog.Builder(activity)
				.setTitle(R.string.chizu_traffic_offer_title)
				.setMessage(message)
				.setPositiveButton(R.string.chizu_traffic_offer_accept, (d, w) -> {
					answered[0] = true;
					chooser.acceptOffer();
				})
				.setNegativeButton(R.string.chizu_traffic_offer_decline, (d, w) -> {
					answered[0] = true;
					chooser.declineOffer();
				})
				.create();
		dialog.setOnDismissListener(d -> {
			if (!answered[0]) {
				answered[0] = true;
				chooser.declineOffer();
			}
			if (showing == d) {
				showing = null;
			}
		});
		showing = dialog;
		dialog.show();
		new Handler(Looper.getMainLooper()).postDelayed(() -> {
			if (dialog.isShowing()) {
				dialog.dismiss();
			}
		}, TIMEOUT_MS);
	}
}
