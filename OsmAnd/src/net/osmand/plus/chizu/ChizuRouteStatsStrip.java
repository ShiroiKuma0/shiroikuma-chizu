package net.osmand.plus.chizu;

import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.routepreparationmenu.cards.ChizuRouteCandidateCard;
import net.osmand.plus.routing.ChizuRouteCandidates;
import net.osmand.plus.routing.RouteCalculationResult;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.plus.utils.OsmAndFormatter;

import java.util.List;

/**
 * 白い熊 地図 渋滞回避: a thin shaded strip under the status bar with the active route's stats —
 * time, length, arrival, time in traffic and which variant it is — shown in route preview whether
 * or not the bottom sheet is pulled up.
 */
public class ChizuRouteStatsStrip {

	private static final long REFRESH_MS = 1000;

	private final MapActivity activity;
	private final OsmandApplication app;
	private final Handler handler = new Handler(Looper.getMainLooper());
	@Nullable
	private TextView strip;
	private boolean running;

	private final Runnable tick = new Runnable() {
		@Override
		public void run() {
			if (running) {
				update();
				handler.postDelayed(this, REFRESH_MS);
			}
		}
	};

	public ChizuRouteStatsStrip(@NonNull MapActivity activity) {
		this.activity = activity;
		this.app = activity.getApp();
	}

	public void start() {
		if (!running) {
			running = true;
			handler.post(tick);
		}
	}

	public void stop() {
		running = false;
		handler.removeCallbacks(tick);
		ChizuPreviewStyle.sync(activity, false);
		if (strip != null) {
			strip.setVisibility(View.GONE);
		}
	}

	private void update() {
		RoutingHelper helper = app.getRoutingHelper();
		ChizuPreviewStyle.sync(activity, ChizuTrafficPrefs.isEnabled(app) && helper.isRoutePlanningMode()
				&& helper.getRoute() != null && helper.getRoute().isCalculated() && !helper.isPublicTransportMode());
		CharSequence text = buildText();
		if (text == null) {
			if (strip != null) {
				strip.setVisibility(View.GONE);
			}
			return;
		}
		TextView view = ensureView();
		if (view == null) {
			return;
		}
		if (!text.toString().equals(view.getText().toString())) {
			view.setText(text);
		}
		view.setVisibility(View.VISIBLE);
		view.bringToFront();
	}

	@Nullable
	private CharSequence buildText() {
		if (!ChizuTrafficPrefs.isEnabled(app)) {
			return null;
		}
		RoutingHelper helper = app.getRoutingHelper();
		RouteCalculationResult route = helper.getRoute();
		if (!helper.isRoutePlanningMode() || route == null || !route.isCalculated() || helper.isPublicTransportMode()) {
			return null;
		}
		ChizuRouteCandidates chooser = helper.getChizuCandidates();
		int seconds;
		int meters;
		int jamSeconds = -1;
		String label = null;
		int color = 0;
		List<ChizuRouteCandidates.Candidate> list = chooser.getCandidates();
		int selected = chooser.getSelectedIndex();
		if (chooser.hasChoice() && selected >= 0 && selected < list.size()) {
			ChizuRouteCandidates.Candidate c = list.get(selected);
			seconds = Math.round(c.adjustedTime);
			meters = c.distance;
			jamSeconds = Math.round(c.jamDelay);
			label = app.getString(c.kind.label);
			color = ChizuRouteCandidateCard.colorFor(selected);
		} else {
			seconds = helper.getLeftTime();
			meters = helper.getLeftDistance();
		}
		SpannableStringBuilder sb = new SpannableStringBuilder();
		if (color != 0) {
			sb.append("●  ");
			sb.setSpan(new ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
		int boldStart = sb.length();
		sb.append(OsmAndFormatter.getFormattedDuration(seconds, app));
		sb.setSpan(new StyleSpan(Typeface.BOLD), boldStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		sb.append("  ·  ").append(OsmAndFormatter.getFormattedDistance(meters, app));
		sb.append("  ·  → ").append(OsmAndFormatter.getFormattedTimeShort(seconds, true));
		if (jamSeconds >= 0) {
			sb.append("  ·  ").append(jamSeconds >= 60
					? app.getString(R.string.chizu_traffic_jam_on_route, OsmAndFormatter.getFormattedDuration(jamSeconds, app))
					: app.getString(R.string.chizu_traffic_no_jam));
		} else if (chooser.isWorking()) {
			sb.append("  ·  ").append(app.getString(R.string.chizu_traffic_checking));
		}
		if (label != null) {
			sb.append("\n").append(label);
		}
		return sb;
	}

	@Nullable
	private TextView ensureView() {
		if (strip != null) {
			return strip;
		}
		View content = activity.findViewById(android.R.id.content);
		if (!(content instanceof FrameLayout)) {
			return null;
		}
		TextView view = new TextView(activity);
		int pad = AndroidUtils.dpToPx(activity, 10);
		view.setPadding(pad, AndroidUtils.dpToPx(activity, 5), pad, AndroidUtils.dpToPx(activity, 6));
		view.setBackgroundColor(0xD9000000);
		view.setTextColor(0xFFFFFF00);
		view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
		view.setGravity(Gravity.CENTER);
		view.setMaxLines(2);
		view.setClickable(false);
		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
		lp.topMargin = AndroidUtils.getStatusBarHeight(activity);
		((FrameLayout) content).addView(view, lp);
		strip = view;
		return view;
	}
}
