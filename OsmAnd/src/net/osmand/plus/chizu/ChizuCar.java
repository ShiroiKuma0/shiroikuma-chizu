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
 * 白い熊 地図 Android Auto look: yellow glyphs on the host's buttons. Colors resolve
 * through {@link ColorUtilities}, so the in-app theming page overrides apply on the car
 * screen too. Icon tint only — the car API forbids a background color on non-primary
 * actions (ActionsConstraints restricts it to primary actions and template validation
 * throws, crashing the car session).
 */
public class ChizuCar {

	private ChizuCar() {
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
				.setIcon(icon(carContext, iconRes));
	}
}
