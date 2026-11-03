package net.osmand.plus.chizu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import net.osmand.plus.R;

/** 白い熊 地図: the Diagnostics notification's Mark and Turn all off buttons. */
public class ChizuDiagReceiver extends BroadcastReceiver {

	@Override
	public void onReceive(Context context, Intent intent) {
		String action = intent.getAction();
		if (ChizuDiag.ACTION_MARK.equals(action)) {
			int n = ChizuDiag.mark("notification");
			if (n > 0) {
				Toast.makeText(context, context.getString(R.string.chizu_diag_marked, n), Toast.LENGTH_SHORT).show();
			}
		} else if (ChizuDiag.ACTION_ALL_OFF.equals(action)) {
			ChizuDiag.allOff(context);
			Toast.makeText(context, R.string.chizu_diag_all_off_done, Toast.LENGTH_SHORT).show();
		}
	}
}
