package net.osmand.plus.views.controls.maphudbuttons;

import static net.osmand.plus.dashboard.DashboardType.DASHBOARD;

import android.content.Context;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.plus.views.mapwidgets.configure.buttons.DrawerMenuButtonState;
import net.osmand.plus.views.mapwidgets.configure.buttons.MapButtonState;

public class DrawerMenuButton extends MapButton {

	private final DrawerMenuButtonState buttonState;

	// shiroikuma fork: own long-press detection so the 白い熊 地図 UI page opens the
	// moment the timeout elapses (the stock path reacted only on finger-lift here)
	private boolean chizuLongPressFired;
	private float chizuDownX;
	private float chizuDownY;
	private final Runnable chizuLongPress = () -> {
		if (mapActivity != null) {
			chizuLongPressFired = true;
			performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
			net.osmand.plus.chizu.ChizuUiFragment.showInstance(mapActivity);
		}
	};

	public DrawerMenuButton(@NonNull Context context) {
		this(context, null);
	}

	public DrawerMenuButton(@NonNull Context context, @Nullable AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public DrawerMenuButton(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		buttonState = app.getMapButtonsHelper().getDrawerMenuButtonState();

		setOnClickListener(v -> {
			MapActivity.clearPrevActivityIntent();
			if (settings.SHOW_DASHBOARD_ON_MAP_SCREEN.get()) {
				mapActivity.getDashboard().setDashboardVisibility(true, DASHBOARD, AndroidUtils.getCenterViewCoordinates(v));
			} else {
				mapActivity.openDrawer();
			}
		});
	}

	// shiroikuma fork: prompt long-press — fires while the finger is still down
	@Override
	public boolean dispatchTouchEvent(MotionEvent event) {
		switch (event.getActionMasked()) {
			case MotionEvent.ACTION_DOWN:
				chizuLongPressFired = false;
				chizuDownX = event.getX();
				chizuDownY = event.getY();
				postDelayed(chizuLongPress, ViewConfiguration.getLongPressTimeout());
				break;
			case MotionEvent.ACTION_MOVE:
				float slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
				if (Math.abs(event.getX() - chizuDownX) > slop
						|| Math.abs(event.getY() - chizuDownY) > slop) {
					removeCallbacks(chizuLongPress);
				}
				break;
			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_CANCEL:
				removeCallbacks(chizuLongPress);
				break;
		}
		if (chizuLongPressFired) {
			// the page is already opening — swallow the rest of the gesture so no click fires
			if (event.getActionMasked() == MotionEvent.ACTION_UP
					|| event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
				MotionEvent cancel = MotionEvent.obtain(event);
				cancel.setAction(MotionEvent.ACTION_CANCEL);
				super.dispatchTouchEvent(cancel);
				cancel.recycle();
			}
			return true;
		}
		return super.dispatchTouchEvent(event);
	}

	@Nullable
	@Override
	public MapButtonState getButtonState() {
		return buttonState;
	}

	@Override
	protected boolean shouldShow() {
		return showBottomButtons && mapActivity.isDrawerAvailable();
	}
}