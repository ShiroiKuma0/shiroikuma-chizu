package net.osmand.plus.helpers;

import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.car.app.AppManager;
import androidx.car.app.CarToast;
import androidx.car.app.model.Alert;
import androidx.car.app.model.CarText;
import androidx.car.app.model.ForegroundCarColorSpan;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.auto.NavigationSession;
import net.osmand.plus.chizu.ChizuCar;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.plus.utils.ColorUtilities;
import net.osmand.util.Algorithms;

public class ToastHelper {

	// shiroikuma fork: fixed alert id so a new car flash replaces the previous one
	private static final int CAR_FLASH_ALERT_ID = 250714;

	public interface ToastDisplayHandler {
		void showSimpleToast(@NonNull String text, boolean isLong);

		void showCarToast(@NonNull String text, boolean isLong);

		void showSimpleToast(@StringRes int textId, boolean isLong, Object... args);

		void showCarToast(@StringRes int textId, boolean isLong, Object... args);
	}

	private final OsmandApplication app;
	private ToastDisplayHandler displayHandler;

	public ToastHelper(@NonNull OsmandApplication app) {
		this.app = app;
		this.displayHandler = getDefaultToastHandler();
	}

	public void setDisplayHandler(@Nullable ToastDisplayHandler handler) {
		this.displayHandler = handler != null ? handler : getDefaultToastHandler();
	}

	public void showToast(@Nullable String text, boolean isLong) {
		if (!Algorithms.isEmpty(text)) {
			app.runInUIThread(() -> {
				displayHandler.showSimpleToast(text, isLong);
				displayHandler.showCarToast(text, isLong);
			});
		}
	}

	public void showToast(@StringRes int textId, boolean isLong, Object... args) {
		if (textId > 0) {
			app.runInUIThread(() -> {
				displayHandler.showSimpleToast(textId, isLong, args);
				displayHandler.showCarToast(textId, isLong, args);
			});
		}
	}

	public void showSimpleToast(@Nullable String text, boolean isLong) {
		if (!Algorithms.isEmpty(text)) {
			app.runInUIThread(() -> displayHandler.showSimpleToast(text, isLong));
		}
	}

	public void showCarToast(@Nullable String text, boolean isLong) {
		if (!Algorithms.isEmpty(text)) {
			app.runInUIThread(() -> displayHandler.showCarToast(text, isLong));
		}
	}

	@NonNull
	private ToastDisplayHandler getDefaultToastHandler() {
		return new ToastDisplayHandler() {
			// shiroikuma fork: the system toast pill is unthemable (white on Android 12+);
			// replace it with a foreground custom view following the black-yellow theme and
			// the 白い熊 地図 UI runtime color overrides
			@SuppressWarnings("deprecation")
			@Override
			public void showSimpleToast(@NonNull String text, boolean isLong) {
				TextView view = new TextView(app);
				view.setText(text);
				view.setTextSize(16);
				view.setTextColor(ColorUtilities.getColor(app, R.color.text_color_primary_dark, 1.0f));
				view.setMaxWidth(AndroidUtils.dpToPx(app, 340));
				int padH = AndroidUtils.dpToPx(app, 20);
				int padV = AndroidUtils.dpToPx(app, 14);
				view.setPadding(padH, padV, padH, padV);
				GradientDrawable background = new GradientDrawable();
				background.setColor(ColorUtilities.getColor(app, R.color.card_and_list_background_dark, 1.0f));
				background.setCornerRadius(AndroidUtils.dpToPx(app, 22));
				background.setStroke(AndroidUtils.dpToPx(app, 2),
						ColorUtilities.getColor(app, R.color.stroked_buttons_and_links_outline_dark, 1.0f));
				view.setBackground(background);

				Toast toast = new Toast(app);
				toast.setDuration(isLong ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
				toast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, AndroidUtils.dpToPx(app, 96));
				toast.setView(view);
				toast.show();
			}

			@Override
			public void showCarToast(@NonNull String text, boolean isLong) {
				NavigationSession navigationSession = app.getCarNavigationSession();
				if (navigationSession != null && navigationSession.hasStarted()) {
					// shiroikuma fork: CarToast is host-styled (black on white); show a
					// navigation Alert with our yellow text instead, toast as fallback
					try {
						SpannableString spannable = new SpannableString(text);
						spannable.setSpan(ForegroundCarColorSpan.create(ChizuCar.accent(app)),
								0, spannable.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
						Alert alert = new Alert.Builder(CAR_FLASH_ALERT_ID,
								CarText.create(spannable), isLong ? 5000 : 2500).build();
						navigationSession.getCarContext().getCarService(AppManager.class).showAlert(alert);
					} catch (RuntimeException e) {
						int duration = isLong ? CarToast.LENGTH_LONG : CarToast.LENGTH_SHORT;
						CarToast.makeText(navigationSession.getCarContext(), text, duration).show();
					}
				}
			}

			@Override
			public void showSimpleToast(int textId, boolean isLong, Object... args) {
				showSimpleToast(app.getString(textId, args), isLong);
			}

			@Override
			public void showCarToast(int textId, boolean isLong, Object... args) {
				showCarToast(app.getString(textId, args), isLong);
			}
		};
	}
}