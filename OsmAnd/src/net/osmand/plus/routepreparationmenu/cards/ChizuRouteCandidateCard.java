package net.osmand.plus.routepreparationmenu.cards;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.routing.ChizuRouteCandidates.Candidate;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.plus.utils.OsmAndFormatter;

/**
 * 白い熊 地図 渋滞回避: one route of the traffic chooser — its map colour, traffic-adjusted time,
 * distance, arrival, and how long it spends in traffic. With no candidate it is the
 * “Checking traffic…” placeholder.
 */
public class ChizuRouteCandidateCard extends MapBaseCard {

	/** Map line colours, by list position; kept clear of the traffic overlay's green, yellow and red. */
	@ColorInt
	public static final int[] COLORS = {0xFF2979FF, 0xFFE040FB, 0xFF00E5FF, 0xFFFFFFFF, 0xFF9E9E9E};

	@Nullable
	private final Candidate candidate;
	private final int index;
	private final boolean selected;

	public ChizuRouteCandidateCard(@NonNull MapActivity mapActivity, @Nullable Candidate candidate, int index,
	                               boolean selected) {
		super(mapActivity);
		this.candidate = candidate;
		this.index = index;
		this.selected = selected;
	}

	@ColorInt
	public static int colorFor(int index) {
		return COLORS[Math.min(index, COLORS.length - 1)];
	}

	public int getIndex() {
		return index;
	}

	@Override
	public int getCardLayoutId() {
		return R.layout.chizu_route_candidate_card;
	}

	@Override
	protected void updateContent() {
		TextView title = view.findViewById(R.id.chizu_candidate_title);
		TextView subtitle = view.findViewById(R.id.chizu_candidate_subtitle);
		TextView eta = view.findViewById(R.id.chizu_candidate_eta);
		View swatch = view.findViewById(R.id.chizu_candidate_swatch);
		View row = view.findViewById(R.id.chizu_candidate_row);
		if (candidate == null) {
			title.setText(R.string.chizu_traffic_checking);
			title.setTextSize(16);
			subtitle.setVisibility(View.GONE);
			eta.setVisibility(View.GONE);
			swatch.setVisibility(View.INVISIBLE);
			return;
		}
		int seconds = Math.round(candidate.adjustedTime);
		title.setText(OsmAndFormatter.getFormattedDuration(seconds, app)
				+ "  ·  " + OsmAndFormatter.getFormattedDistance(candidate.distance, app));
		title.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
		int jamSeconds = Math.round(candidate.jamDelay);
		String traffic = jamSeconds >= 60
				? app.getString(R.string.chizu_traffic_jam_on_route, OsmAndFormatter.getFormattedDuration(jamSeconds, app))
				: app.getString(R.string.chizu_traffic_no_jam);
		subtitle.setText(app.getString(candidate.kind.label) + "  ·  " + traffic);
		eta.setText(OsmAndFormatter.getFormattedTimeShort(seconds, true));

		GradientDrawable bar = new GradientDrawable();
		bar.setColor(colorFor(index));
		bar.setCornerRadius(AndroidUtils.dpToPx(app, 4));
		swatch.setBackground(bar);

		if (selected) {
			GradientDrawable frame = new GradientDrawable();
			frame.setColor(0x00000000);
			frame.setStroke(AndroidUtils.dpToPx(app, 2), colorFor(index));
			row.setForeground(frame);
		} else {
			row.setForeground(null);
		}
		row.setOnClickListener(v -> notifyCardPressed());
	}
}
