package net.osmand.plus.chizu;

import android.content.Context;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarColor;
import androidx.car.app.model.CarIcon;
import androidx.core.graphics.drawable.IconCompat;

import net.osmand.plus.R;
import net.osmand.plus.utils.ColorUtilities;

/**
 * 白い熊 地図 Android Auto look: black button backgrounds, yellow glyphs. Colors resolve
 * through {@link ColorUtilities}, so the in-app theming page overrides apply on the car
 * screen too. Hosts that don't honor custom action colors fall back to their own chrome.
 */
public class ChizuCar {

	private ChizuCar() {
	}

	@NonNull
	public static CarColor background(@NonNull Context ctx) {
		int color = ColorUtilities.getColor(ctx, R.color.map_button_background_color_dark, 1.0f);
		return CarColor.createCustom(color, color);
	}

	@NonNull
	public static CarColor accent(@NonNull Context ctx) {
		int color = ColorUtilities.getColor(ctx, R.color.map_button_icon_color_dark, 1.0f);
		return CarColor.createCustom(color, color);
	}

	@NonNull
	public static CarIcon icon(@NonNull CarContext carContext, @DrawableRes int iconRes) {
		return new CarIcon.Builder(IconCompat.createWithResource(carContext, iconRes))
				.setTint(accent(carContext))
				.build();
	}

	@NonNull
	public static Action.Builder action(@NonNull CarContext carContext, @DrawableRes int iconRes) {
		return new Action.Builder()
				.setIcon(icon(carContext, iconRes))
				.setBackgroundColor(background(carContext));
	}
}
