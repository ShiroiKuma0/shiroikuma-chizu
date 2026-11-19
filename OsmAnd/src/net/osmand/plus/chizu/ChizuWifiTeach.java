package net.osmand.plus.chizu;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import net.osmand.Location;
import net.osmand.core.android.MapRendererView;
import net.osmand.data.RotatedTileBox;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.utils.NativeUtilities;
import net.osmand.plus.views.OsmandMapTileView;

import java.lang.ref.WeakReference;

/**
 * 白い熊 地図 +088: teaching the Wi-Fi locator a position by hand, and the menu of everything to do with the
 * position (白い熊, 2026-11-18: “I could pinpoint my position on the map exactly … I'd position it exactly and
 * press some set-location button”).
 * <p>
 * <b>Teaching:</b> a tap on the pill when it says it can be taught (or the menu's first row) puts a crosshair
 * on the middle of the screen and two pills at the bottom; the map is moved under the crosshair, at any zoom
 * and on any layer, and “Set position” teaches {@link ChizuWifiLocator} that point. <b>The menu:</b> a long
 * press on the pill — teach, show what is learned on the map, what the locator knows, forget the taught place
 * here, and the settings. Its rows are the black-yellow menu rows: full-width outlined pills with their icon,
 * a › on those that open something, a plain row with a switch for the one that toggles.
 */
public final class ChizuWifiTeach {

	private static final int TEACH_ZOOM = 19;
	private static final double FORGET_WITHIN_M = 60;

	private static WeakReference<View> bar = new WeakReference<>(null);

	private ChizuWifiTeach() {
	}

	/** The crosshair is up. */
	public static boolean isActive() {
		View v = bar.get();
		return v != null && v.isAttachedToWindow();
	}

	public static void start(@NonNull MapActivity activity) {
		if (isActive()) {
			return;
		}
		OsmandApplication app = activity.getApp();
		ViewGroup content = activity.findViewById(android.R.id.content);
		if (content == null) {
			return;
		}
		float d = activity.getResources().getDisplayMetrics().density;
		int accent = ChizuTheme.getColor(activity, ChizuTheme.Slot.ACCENT);

		LinearLayout box = new LinearLayout(activity);
		box.setOrientation(LinearLayout.VERTICAL);
		box.setGravity(Gravity.CENTER_HORIZONTAL);

		TextView hint = new TextView(activity);
		hint.setText(R.string.chizu_wifi_teach_hint);
		hint.setTextColor(accent);
		hint.setTextSize(14);
		hint.setGravity(Gravity.CENTER);
		GradientDrawable hintBg = new GradientDrawable();
		hintBg.setColor(Color.BLACK);
		hintBg.setCornerRadius(12 * d);
		hint.setBackground(hintBg);
		hint.setPadding(Math.round(14 * d), Math.round(8 * d), Math.round(14 * d), Math.round(8 * d));
		LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		hintParams.bottomMargin = Math.round(10 * d);
		box.addView(hint, hintParams);

		LinearLayout buttons = new LinearLayout(activity);
		buttons.setOrientation(LinearLayout.HORIZONTAL);
		buttons.setGravity(Gravity.CENTER);
		TextView cancel = pill(activity, activity.getString(R.string.shared_string_cancel), false);
		TextView set = pill(activity, activity.getString(R.string.chizu_wifi_teach_set), true);
		LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		gap.setMarginEnd(Math.round(14 * d));
		buttons.addView(cancel, gap);
		buttons.addView(set);
		box.addView(buttons);

		FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
		params.bottomMargin = Math.round(96 * d);
		params.leftMargin = params.rightMargin = Math.round(12 * d);
		content.addView(box, params);
		bar = new WeakReference<>(box);

		// the map stops following, and comes in close on the last place known
		OsmandMapTileView map = activity.getMapView();
		app.getMapViewTrackingUtilities().setMapLinkedToLocation(false);
		Location last = app.getLocationProvider().getLastStaleKnownLocation();
		if (last != null) {
			map.setLatLon(last.getLatitude(), last.getLongitude());
		}
		if (map.getZoom() < TEACH_ZOOM - 1) {
			map.setIntZoom(Math.min(TEACH_ZOOM, map.getMaxZoom()));
		}
		map.refreshMap();
		ChizuDriveLog.log("wifi locator: teaching screen opened");

		cancel.setOnClickListener(v -> finish(activity));
		set.setOnClickListener(v -> {
			double[] at = underCrosshair(map);
			int count = ChizuWifiLocator.teach(app, at[0], at[1]);
			finish(activity);
			app.showToastMessage(count > 0 ? activity.getString(R.string.chizu_wifi_taught, count)
					: activity.getString(R.string.chizu_wifi_taught_wait));
		});
	}

	/** The point of the map under the middle of the screen, where the crosshair is drawn. */
	@NonNull
	private static double[] underCrosshair(@NonNull OsmandMapTileView map) {
		RotatedTileBox box = map.getRotatedTileBox();
		net.osmand.data.LatLon ll = NativeUtilities.getLatLonFromPixel(map.getMapRenderer(), box,
				box.getPixWidth() / 2, box.getPixHeight() / 2);
		return ll == null ? new double[] {map.getLatitude(), map.getLongitude()}
				: new double[] {ll.getLatitude(), ll.getLongitude()};
	}

	private static void finish(@NonNull MapActivity activity) {
		View v = bar.get();
		if (v != null && v.getParent() instanceof ViewGroup) {
			((ViewGroup) v.getParent()).removeView(v);
		}
		bar = new WeakReference<>(null);
		activity.getMapView().refreshMap();
	}

	/** The crosshair, at the point the map is centred on — where “Set position” will teach. The canvas must not be rotated. */
	public static void drawCrosshair(@NonNull OsmandApplication app, @NonNull Canvas canvas, @NonNull RotatedTileBox box,
	                                 @Nullable MapRendererView renderer) {
		if (!isActive()) {
			return;
		}
		// +090: in the middle of the screen, clear of the buttons (白い熊, 2026-11-18) — a tilted map has its own
		// centre low down, under them
		PointF p = new PointF(box.getPixWidth() / 2f, box.getPixHeight() / 2f);
		float d = app.getResources().getDisplayMetrics().density;
		float arm = 30 * d;
		float hole = 5 * d;
		Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
		edge.setStyle(Paint.Style.STROKE);
		edge.setStrokeCap(Paint.Cap.ROUND);
		edge.setColor(Color.BLACK);
		edge.setStrokeWidth(5 * d);
		Paint line = new Paint(edge);
		line.setColor(0xFFFFFF00);
		line.setStrokeWidth(2 * d);
		for (Paint paint : new Paint[] {edge, line}) {
			canvas.drawLine(p.x - arm, p.y, p.x - hole, p.y, paint);
			canvas.drawLine(p.x + hole, p.y, p.x + arm, p.y, paint);
			canvas.drawLine(p.x, p.y - arm, p.x, p.y - hole, paint);
			canvas.drawLine(p.x, p.y + hole, p.x, p.y + arm, paint);
			canvas.drawCircle(p.x, p.y, 14 * d, paint);
		}
		Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
		dot.setColor(Color.BLACK);
		canvas.drawCircle(p.x, p.y, 2.2f * d, dot);
		dot.setColor(0xFFFFFF00);
		canvas.drawCircle(p.x, p.y, 1.2f * d, dot);
	}

	// ---------- the menu ----------

	public static void showMenu(@NonNull MapActivity activity) {
		OsmandApplication app = activity.getApp();
		float d = activity.getResources().getDisplayMetrics().density;
		int accent = ChizuTheme.getColor(activity, ChizuTheme.Slot.ACCENT);
		int background = ChizuTheme.getColor(activity, ChizuTheme.Slot.BACKGROUND);

		LinearLayout list = new LinearLayout(activity);
		list.setOrientation(LinearLayout.VERTICAL);
		// +094: every menu has a yellow border (白い熊, 2026-11-19) — on the list itself, where nothing covers it
		GradientDrawable frame = new GradientDrawable();
		frame.setColor(background);
		frame.setCornerRadius(18 * d);
		frame.setStroke(Math.max(1, Math.round(ChizuPills.REGULAR_DP * d)), accent);
		list.setBackground(frame);
		int pad = Math.round(14 * d);
		list.setPadding(pad, pad, pad, pad);

		AlertDialog dialog = new AlertDialog.Builder(activity).setView(list).create();

		boolean teachable = ChizuWifiLocator.teachable(activity);
		View teach = row(activity, R.drawable.ic_action_marker_dark, activity.getString(R.string.chizu_wifi_menu_teach), true, teachable);
		teach.setOnClickListener(v -> {
			dialog.dismiss();
			if (ChizuWifiLocator.teachable(activity)) {
				start(activity);
			} else {
				app.showToastMessage(R.string.chizu_wifi_not_teachable);
			}
		});
		list.addView(teach, rowParams(d));

		list.addView(row(activity, R.drawable.ic_action_info_dark, activity.getString(R.string.chizu_wifi_menu_details), false, true,
				v -> {
					dialog.dismiss();
					AlertDialog details = new AlertDialog.Builder(activity)
							.setMessage(ChizuFix.details(app) + "\n\n" + ChizuWifiLocator.describe(app))
							.setPositiveButton(R.string.shared_string_ok, null).show();
					ChizuPills.styleDialog(details);
				}), rowParams(d));

		list.addView(row(activity, R.drawable.ic_action_delete_dark, activity.getString(R.string.chizu_wifi_menu_forget), false, true,
				v -> {
					dialog.dismiss();
					OsmandMapTileView map = activity.getMapView();
					AlertDialog confirm = new AlertDialog.Builder(activity)
							.setMessage(R.string.chizu_wifi_forget_confirm)
							.setNegativeButton(R.string.shared_string_cancel, null)
							.setPositiveButton(R.string.chizu_wifi_forget_do, (x, y) -> app.showToastMessage(
									ChizuWifiLocator.forgetPlaceNear(app, underCrosshair(map)[0], underCrosshair(map)[1], FORGET_WITHIN_M)
											? R.string.chizu_wifi_forgotten : R.string.chizu_wifi_forget_none))
							.show();
					ChizuPills.styleDialog(confirm);
				}), rowParams(d));

		// a clear divider, as wide as the pills, before the row that toggles
		View rule = new View(activity);
		rule.setBackgroundColor(ChizuMenuPills.divider(activity));
		LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(d)));
		ruleParams.topMargin = Math.round(4 * d);
		ruleParams.bottomMargin = Math.round(6 * d);
		list.addView(rule, ruleParams);

		LinearLayout toggleRow = new LinearLayout(activity);
		toggleRow.setOrientation(LinearLayout.HORIZONTAL);
		toggleRow.setGravity(Gravity.CENTER_VERTICAL);
		toggleRow.setMinimumHeight(Math.round(36 * d));
		toggleRow.setPadding(Math.round(16 * d), 0, Math.round(8 * d), 0);
		TextView toggleLabel = new TextView(activity);
		toggleLabel.setText(R.string.chizu_wifi_coverage);
		toggleLabel.setTextColor(accent);
		toggleLabel.setTextSize(16);
		toggleRow.addView(toggleLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		SwitchCompat toggle = ChizuMenuPills.newSwitch(activity, true);
		toggle.setChecked(ChizuWifiLocator.isCoverageShown(activity));
		toggle.setOnCheckedChangeListener((b, checked) -> ChizuWifiLocator.setCoverageShown(app, checked));
		toggleRow.addView(toggle);
		toggleRow.setOnClickListener(v -> toggle.toggle());
		list.addView(toggleRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		View rule2 = new View(activity);
		rule2.setBackgroundColor(ChizuMenuPills.divider(activity));
		LinearLayout.LayoutParams rule2Params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(d)));
		rule2Params.topMargin = Math.round(6 * d);
		rule2Params.bottomMargin = Math.round(10 * d);
		list.addView(rule2, rule2Params);

		list.addView(row(activity, R.drawable.ic_action_settings, activity.getString(R.string.chizu_wifi_menu_settings), true, true,
				v -> {
					dialog.dismiss();
					ChizuUiFragment.showWifiSection(activity);
				}), rowParams(d));

		list.addView(row(activity, R.drawable.ic_action_settings, activity.getString(R.string.chizu_gnss_menu_settings), true, true,
				v -> {
					dialog.dismiss();
					ChizuUiFragment.showGnssSection(activity);
				}), rowParams(d));

		dialog.show();
		if (dialog.getWindow() != null) {
			dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
		}
	}

	private static LinearLayout.LayoutParams rowParams(float d) {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		params.bottomMargin = Math.round(8 * d);
		return params;
	}

	@NonNull
	private static View row(@NonNull Context c, int iconRes, @NonNull String title, boolean opens, boolean enabled,
	                        @NonNull View.OnClickListener listener) {
		View row = row(c, iconRes, title, opens, enabled);
		row.setOnClickListener(listener);
		return row;
	}

	/** A menu row: a full-width outlined pill, its icon inside at the left, a › at the right when it opens something. */
	@NonNull
	private static View row(@NonNull Context c, int iconRes, @NonNull String title, boolean opens, boolean enabled) {
		float d = c.getResources().getDisplayMetrics().density;
		boolean destructive = iconRes == R.drawable.ic_action_delete_dark;
		int accent = destructive ? ChizuMenuPills.warning(c) : ChizuTheme.getColor(c, ChizuTheme.Slot.ACCENT);
		TextView row = new TextView(c);
		row.setText(opens ? title + "  ›" : title);
		row.setTextColor(accent);
		row.setTextSize(16);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setBackground(ChizuPills.drawable(c, accent, ChizuTheme.getColor(c, ChizuTheme.Slot.BACKGROUND), false));
		row.setPadding(Math.round(14 * d), Math.round(10 * d), Math.round(16 * d), Math.round(10 * d));
		Drawable icon = ContextCompat.getDrawable(c, iconRes);
		if (icon != null) {
			icon = icon.mutate();
			icon.setTint(accent);
			int size = Math.round(22 * d);
			icon.setBounds(0, 0, size, size);
			row.setCompoundDrawablesRelative(icon, null, null, null);
			row.setCompoundDrawablePadding(Math.round(12 * d));
		}
		row.setClickable(true);
		row.setFocusable(true);
		if (!enabled) {
			row.setAlpha(0.38f);
		}
		return row;
	}

	@NonNull
	private static TextView pill(@NonNull Context c, @NonNull String title, boolean main) {
		float d = c.getResources().getDisplayMetrics().density;
		int accent = ChizuTheme.getColor(c, ChizuTheme.Slot.ACCENT);
		TextView pill = new TextView(c);
		pill.setText(title);
		pill.setTextColor(accent);
		pill.setTextSize(17);
		pill.setTypeface(Typeface.DEFAULT, main ? Typeface.BOLD : Typeface.NORMAL);
		pill.setBackground(ChizuPills.drawable(c, accent, Color.BLACK, main));
		pill.setPadding(Math.round(22 * d), Math.round(11 * d), Math.round(22 * d), Math.round(11 * d));
		pill.setClickable(true);
		pill.setFocusable(true);
		return pill;
	}
}
