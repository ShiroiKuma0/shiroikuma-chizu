package net.osmand.plus.chizu;

import android.content.Context;

import androidx.annotation.NonNull;

/**
 * 白い熊 地図 +068: the car map's 3D tilt. OsmAnd tilts by zoom level (90° − 5° per zoom above 2), down to
 * a floor — Android Auto's 20°, which at driving zoom (≈ 17, asking for 15°) is what the car always got:
 * a camera looking nearly at the horizon, kilometres of map drawn in a thin band at the top. This setting is
 * that floor; a higher angle looks more from above (90° = flat).
 */
public final class ChizuCarView {

	public static final int DEFAULT_TILT = 20;

	private ChizuCarView() {
	}

	public static int getTilt(@NonNull Context c) {
		return c.getSharedPreferences("chizu_car_view", Context.MODE_PRIVATE).getInt("tilt", DEFAULT_TILT);
	}

	public static void setTilt(@NonNull Context c, int degrees) {
		c.getSharedPreferences("chizu_car_view", Context.MODE_PRIVATE).edit()
				.putInt("tilt", Math.max(10, Math.min(89, degrees))).apply();
	}
}
