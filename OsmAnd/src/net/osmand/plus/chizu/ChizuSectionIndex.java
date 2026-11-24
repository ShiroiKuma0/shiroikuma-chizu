package net.osmand.plus.chizu;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 白い熊 地図 +103: the jump index of the UI settings page — a wrapping grid of small outlined chips, one per
 * section (白い熊, 2026-11-19). A <b>tap</b> jumps to a section; a <b>long-press</b> lifts a chip into a drag,
 * and dropping it among the others sets a new order, which reorders the sections below. The chip grid is far
 * more compact than the folded headers, so it earns its place as the fast path (black-yellow skill, “Long
 * settings pages”).
 */
public final class ChizuSectionIndex {

	/** One section: its stable key and its heading. */
	public static final class Item {
		final String key;
		final String label;
		final int icon;
		final int color;

		/** {@code icon} and {@code color} are the section's own: the same pair its heading shows. */
		public Item(@NonNull String key, @NonNull String label, int icon, int color) {
			this.key = key;
			this.label = label;
			this.icon = icon;
			this.color = color;
		}
	}

	public interface Listener {
		void onJump(@NonNull String key);

		void onReordered(@NonNull List<String> order);
	}

	private ChizuSectionIndex() {
	}

	/** Builds the chip flow. {@code items} are already in display order. */
	@NonNull
	public static View build(@NonNull Context c, @NonNull List<Item> items, int accent, int dim, @NonNull Listener listener) {
		float d = c.getResources().getDisplayMetrics().density;
		Flow flow = new Flow(c, Math.round(6 * d));
		flow.setPadding(0, Math.round(2 * d), 0, Math.round(6 * d));
		final List<View> chips = new ArrayList<>();
		for (Item it : items) {
			TextView chip = chip(c, it.label, accent, d);
			// 5.6.0+003: the section's icon in the section's colour, sized by the chip's label
			ChizuRowIcons.set(chip, it.icon, it.color, false);
			chip.setTag(it.key);
			chip.setOnClickListener(v -> listener.onJump((String) v.getTag()));
			chip.setOnLongClickListener(v -> {
				v.setAlpha(0.3f);
				ClipData data = ClipData.newPlainText("chizu_section", (String) v.getTag());
				v.startDragAndDrop(data, new View.DragShadowBuilder(v), v, 0);
				return true;
			});
			chips.add(chip);
			flow.addView(chip);
		}
		flow.setOnDragListener((host, event) -> onDrag(flow, event, accent, listener));
		return flow;
	}

	private static boolean onDrag(@NonNull Flow flow, @NonNull DragEvent event, int accent, @NonNull Listener listener) {
		View dragged = event.getLocalState() instanceof View ? (View) event.getLocalState() : null;
		switch (event.getAction()) {
			case DragEvent.ACTION_DRAG_STARTED:
				return true;
			case DragEvent.ACTION_DRAG_LOCATION:
				// +104: the chips reorder LIVE as the finger moves (白い熊, 2026-11-19) — the drag is driven by the
				// shadow surface, so moving the chip in the tree does not cancel it; the sections below reorder
				// only on drop
				if (dragged != null) {
					int to = insertionIndex(flow, event.getX(), event.getY());
					int from = flow.indexOfChild(dragged);
					if (to >= 0 && to != from) {
						try {
							flow.removeView(dragged);
							flow.addView(dragged, Math.min(to, flow.getChildCount()));
						} catch (Exception ignored) {
							// the tree was mid-change; the next location event retries
						}
					}
				}
				return true;
			case DragEvent.ACTION_DROP:
				return true;
			case DragEvent.ACTION_DRAG_ENDED:
				if (dragged != null) {
					dragged.setAlpha(1f);
				}
				// the chips are in their final order; reorder the sections below to match
				List<String> order = new ArrayList<>();
				for (int i = 0; i < flow.getChildCount(); i++) {
					order.add((String) flow.getChildAt(i).getTag());
				}
				listener.onReordered(order);
				return true;
			default:
				return true;
		}
	}

	/** The child index the drag point sits over, or the nearest by centre. */
	private static int insertionIndex(@NonNull Flow flow, float x, float y) {
		int best = -1;
		double bestDist = Double.MAX_VALUE;
		for (int i = 0; i < flow.getChildCount(); i++) {
			View ch = flow.getChildAt(i);
			if (x >= ch.getLeft() && x <= ch.getRight() && y >= ch.getTop() && y <= ch.getBottom()) {
				return i;
			}
			double cx = (ch.getLeft() + ch.getRight()) / 2.0;
			double cy = (ch.getTop() + ch.getBottom()) / 2.0;
			double dist = Math.hypot(x - cx, y - cy);
			if (dist < bestDist) {
				bestDist = dist;
				best = i;
			}
		}
		return best;
	}

	@NonNull
	private static TextView chip(@NonNull Context c, @NonNull String label, int accent, float d) {
		TextView t = new TextView(c);
		t.setText(label);
		t.setTextColor(accent);
		t.setTextSize(12);
		t.setSingleLine(true);
		t.setGravity(Gravity.CENTER);
		t.setPadding(Math.round(12 * d), Math.round(5 * d), Math.round(12 * d), Math.round(5 * d));
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(Color.TRANSPARENT);
		bg.setCornerRadius(100 * d);
		bg.setStroke(Math.max(1, Math.round(ChizuPills.REGULAR_DP * d)), accent);
		t.setBackground(bg);
		t.setClickable(true);
		t.setFocusable(true);
		return t;
	}

	/** A minimal wrapping row layout. */
	@SuppressLint("ViewConstructor")
	static final class Flow extends ViewGroup {
		private final int gap;

		Flow(@NonNull Context c, int gap) {
			super(c);
			this.gap = gap;
		}

		@Override
		protected void onMeasure(int widthSpec, int heightSpec) {
			int width = MeasureSpec.getSize(widthSpec);
			int inner = width - getPaddingLeft() - getPaddingRight();
			int x = 0, y = getPaddingTop(), rowH = 0;
			for (int i = 0; i < getChildCount(); i++) {
				View ch = getChildAt(i);
				measureChild(ch, MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST), heightSpec);
				int cw = ch.getMeasuredWidth(), chh = ch.getMeasuredHeight();
				if (x > 0 && x + cw > inner) {
					x = 0;
					y += rowH + gap;
					rowH = 0;
				}
				x += cw + gap;
				rowH = Math.max(rowH, chh);
			}
			setMeasuredDimension(width, y + rowH + getPaddingBottom());
		}

		@Override
		protected void onLayout(boolean changed, int l, int t, int r, int b) {
			int inner = getWidth() - getPaddingLeft() - getPaddingRight();
			int x = getPaddingLeft(), y = getPaddingTop(), rowH = 0;
			for (int i = 0; i < getChildCount(); i++) {
				View ch = getChildAt(i);
				int cw = ch.getMeasuredWidth(), chh = ch.getMeasuredHeight();
				if (x > getPaddingLeft() && x + cw > getPaddingLeft() + inner) {
					x = getPaddingLeft();
					y += rowH + gap;
					rowH = 0;
				}
				ch.layout(x, y, x + cw, y + chh);
				x += cw + gap;
				rowH = Math.max(rowH, chh);
			}
		}
	}
}
