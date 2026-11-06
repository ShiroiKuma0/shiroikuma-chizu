package net.osmand.plus.chizu;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 白い熊 地図: a small spinning ring in the top-right corner of the next-turn box while the route is
 * being recalculated. Stock OsmAnd swaps the whole box for a grey off-route sign meanwhile, so the
 * next turn vanishes exactly when it is most wanted; here the turn stays and this ring says a new
 * route is on its way. Drawn in the box's {@link android.view.ViewOverlay}, whatever its layout.
 */
public final class ChizuRecalcSpinner extends Drawable {

	private static final long TURN_MS = 900;
	private static final int SIZE_DP = 22;
	private static final int MARGIN_DP = 5;

	private final View host;
	private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF oval = new RectF();
	private final float density;
	private boolean shown;

	private ChizuRecalcSpinner(@NonNull View host) {
		this.host = host;
		density = host.getResources().getDisplayMetrics().density;
		float stroke = 3f * density;
		track.setStyle(Paint.Style.STROKE);
		track.setStrokeWidth(stroke + 2f * density);
		track.setColor(0xCC000000);
		arc.setStyle(Paint.Style.STROKE);
		arc.setStrokeWidth(stroke);
		arc.setStrokeCap(Paint.Cap.ROUND);
		arc.setColor(0xFFFFFF00);
		host.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> place());
		place();
	}

	/** The spinner of this box, created on first use. */
	@NonNull
	public static ChizuRecalcSpinner of(@NonNull View host) {
		Object tag = host.getTag(net.osmand.plus.R.id.chizu_recalc_spinner);
		if (tag instanceof ChizuRecalcSpinner) {
			return (ChizuRecalcSpinner) tag;
		}
		ChizuRecalcSpinner s = new ChizuRecalcSpinner(host);
		host.setTag(net.osmand.plus.R.id.chizu_recalc_spinner, s);
		return s;
	}

	public void show(boolean show) {
		if (show == shown) {
			return;
		}
		shown = show;
		if (show) {
			host.getOverlay().add(this);
			place();
		} else {
			host.getOverlay().remove(this);
		}
		host.invalidate();
	}

	private void place() {
		int size = Math.round(SIZE_DP * density);
		int margin = Math.round(MARGIN_DP * density);
		int right = host.getWidth() - margin;
		setBounds(right - size, margin, right, margin + size);
	}

	@Override
	public void draw(@NonNull Canvas canvas) {
		if (!shown) {
			return;
		}
		float inset = 3f * density;
		oval.set(getBounds());
		oval.inset(inset, inset);
		float angle = (SystemClock.uptimeMillis() % TURN_MS) * 360f / TURN_MS;
		canvas.drawOval(oval, track);
		canvas.drawArc(oval, angle, 270f, false, arc);
		host.postInvalidateOnAnimation();
	}

	@Override
	public void setAlpha(int alpha) {
	}

	@Override
	public void setColorFilter(@Nullable ColorFilter colorFilter) {
	}

	@Override
	public int getOpacity() {
		return PixelFormat.TRANSLUCENT;
	}
}
