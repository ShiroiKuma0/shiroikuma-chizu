package net.osmand.plus.chizu;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.plus.OsmAndLocationProvider;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.simulation.SimulationProvider;

import java.util.Locale;

/**
 * 白い熊 地図 +084: what kind of position is shown (白い熊, 2026-11-18: “I need to see visually when we have
 * a sat fix”). That day a position appeared on the street and was taken for a satellite fix; it was the
 * network locator's, from Wi-Fi, and the receiver had none.
 * <p>
 * The marker says it by its shape — the arrow for a satellite fix, a hollow ring for a network position
 * (which knows no direction), the dimmed arrow for an estimate, a grey ring when nothing is current — and a
 * small pill by the marker says it in words whenever the position is not a plain satellite fix: “NET 20 m”,
 * “EST”, and while the receiver searches “SEARCH 12 heard · 40 dB · 0:25”, which tells whether waiting will
 * help. “SAT 14” with its accuracy stays for as long as the fix is a satellite one (+091).
 * <p>
 * +085: on the car screen the pill sits left of the speed box, as high as it, and a button on the phone's
 * screen switches it on and off with one tap; “CAR” stays in it for as long as the position is the car's own
 * (see {@link ChizuCarGps}). Japanese when the app is.
 */
public final class ChizuFix {

	public enum Kind {SAT, CAR, WIFI, NET, EST, NONE}

	private static final String PILL_ON_CAR = "fix_pill_on_car";

	private static final long SAT_SHOWN_MS = 5000;
	private static final long RECEIVER_FRESH_MS = 15_000;
	private static final int ACCENT = 0xFFFFFF00;

	private static Kind lastKind;
	private static long satSinceMs;
	private static long searchSinceMs;
	private static boolean tickPending;
	private static final RectF pill = new RectF();
	private static boolean pillShown;
	private static final String KEY_LAST_SAT = "last_sat_fix_ms";
	private static volatile long satSeenWallMs; // the last satellite fix, wall time, as far as this run knows
	private static long satSavedMs;
	private static boolean teachShown;

	private ChizuFix() {
	}

	/** +092: a satellite fix is here now. Written down at most once a minute. */
	public static void noteSat(@NonNull android.content.Context c) {
		long now = System.currentTimeMillis();
		satSeenWallMs = now;
		if (now - satSavedMs > 60_000) {
			satSavedMs = now;
			c.getApplicationContext().getSharedPreferences(ChizuTheme.PREFS_NAME, android.content.Context.MODE_PRIVATE)
					.edit().putLong(KEY_LAST_SAT, now).apply();
		}
	}

	/** A satellite fix was seen (and set aside for a taught place): remembered, written down with the next one drawn. */
	public static void noteSatSeen() {
		satSeenWallMs = System.currentTimeMillis();
	}

	/** When the receiver last had a satellite fix (wall time), 0 when never known. */
	public static long lastSatMs(@NonNull android.content.Context c) {
		long seen = satSeenWallMs;
		if (seen != 0) {
			return seen;
		}
		return c.getApplicationContext().getSharedPreferences(ChizuTheme.PREFS_NAME, android.content.Context.MODE_PRIVATE)
				.getLong(KEY_LAST_SAT, 0);
	}

	/** “12 min”, “2 h 10 min”, “3 d” — how long ago the last satellite fix was; null when never. */
	@Nullable
	public static String lastSatAge(@NonNull android.content.Context c) {
		long at = lastSatMs(c);
		if (at == 0) {
			return null;
		}
		long min = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
		return min < 60 ? min + " min" : min < 48 * 60 ? min / 60 + " h " + min % 60 + " min" : min / 1440 + " d";
	}

	@NonNull
	public static Kind kind(@NonNull OsmandApplication app) {
		OsmAndLocationProvider lp = app.getLocationProvider();
		if (lp.isChizuEstimating()) {
			return Kind.EST;
		}
		Location l = lp.getLastKnownLocation();
		if (l == null) {
			return Kind.NONE;
		}
		if (ChizuCarGps.active()) {
			return Kind.CAR;
		}
		if (ChizuWifiLocator.PROVIDER.equals(l.getProvider())) {
			return Kind.WIFI; // +088: 地図's own, from the networks in range
		}
		return satellite(l) ? Kind.SAT : Kind.NET;
	}

	/**
	 * A satellite fix: the receiver is using four satellites or more right now. Until +095 a position that
	 * carried a speed was taken for one — and on 2026-11-19 10:54 the “fused” locator delivered a network
	 * position with a speed of 2.5 m/s and a bearing while the receiver used no satellite at all (3 heard at
	 * 17 dB-Hz); the pill said “SAT” and 白い熊 took it for a fix.
	 */
	public static boolean satellite(@NonNull Location l) {
		if (!SimulationProvider.isNotSimulatedLocation(l)) {
			return true; // a simulated drive
		}
		return l.hasSpeed() && !"network".equals(l.getProvider()) && ChizuGnssLog.fixWithin(10_000);
	}

	/** The marker is the hollow ring: a network position, or none that is current. */
	public static boolean ring(@NonNull Kind kind) {
		return kind == Kind.NET || kind == Kind.NONE || kind == Kind.WIFI;
	}

	/** The ring marker, as a one-layer drawable the marker code can scale: black edges, the colour between. */
	@NonNull
	public static LayerDrawable ringIcon(@NonNull OsmandApplication app, int color) {
		float d = app.getResources().getDisplayMetrics().density;
		int size = Math.round(30 * d);
		Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
		Canvas c = new Canvas(bmp);
		Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
		p.setStyle(Paint.Style.STROKE);
		float r = size / 2f;
		p.setColor(Color.BLACK);
		p.setStrokeWidth(8 * d);
		c.drawCircle(r, r, r - 4.5f * d, p);
		p.setColor(color);
		p.setStrokeWidth(5 * d);
		c.drawCircle(r, r, r - 4.5f * d, p);
		return new LayerDrawable(new Drawable[] {new BitmapDrawable(app.getResources(), bmp)});
	}

	/** The pill's two lines — the kind of position, and what the searching receiver hears — either may be null. */
	@NonNull
	private static synchronized String[] lines(@NonNull OsmandApplication app) {
		long now = SystemClock.elapsedRealtime();
		Kind kind = kind(app);
		if (kind != lastKind) {
			if (kind == Kind.SAT) {
				satSinceMs = now;
			}
			ChizuDriveLog.log("position kind " + lastKind + " → " + kind + ", " + ChizuGnssLog.orbits());
			lastKind = kind;
			if (kind == Kind.SAT) {
				ChizuWifiLocator.onKindChanged(app); // +094: a taught place beside the fix is judged at once
			}
		}
		boolean searching = kind != Kind.SAT && kind != Kind.CAR && kind != Kind.EST && ChizuGnssLog.statusWithin(RECEIVER_FRESH_MS);
		if (!searching) {
			searchSinceMs = 0;
		} else if (searchSinceMs == 0) {
			searchSinceMs = now;
		}
		String line1 = null;
		String line2 = null;
		if (kind == Kind.SAT) {
			// +091: always shown (白い熊, 2026-11-18: “we need to keep the pill”) — a satellite fix is told from
			// every other kind by reading it, not by the pill's absence
			line1 = app.getString(R.string.chizu_fix_sat, ChizuGnssLog.usedCount());
			noteSat(app);
			Location l = app.getLocationProvider().getLastKnownLocation();
			if (l != null && l.hasAccuracy()) {
				line2 = app.getString(R.string.chizu_fix_accuracy, Math.round(l.getAccuracy()));
			}
		} else if (kind == Kind.CAR) {
			line1 = app.getString(R.string.chizu_fix_car);
		} else if (kind == Kind.WIFI) {
			Location l = app.getLocationProvider().getLastKnownLocation();
			line1 = app.getString(R.string.chizu_fix_wifi, l != null && l.hasAccuracy() ? Math.round(l.getAccuracy()) : 0);
		} else if (kind == Kind.EST) {
			line1 = app.getString(R.string.chizu_fix_est);
		} else if (kind == Kind.NET) {
			Location l = app.getLocationProvider().getLastKnownLocation();
			line1 = l != null && l.hasAccuracy() ? app.getString(R.string.chizu_fix_net, Math.round(l.getAccuracy()))
					: app.getString(R.string.chizu_fix_net_plain);
		} else {
			line1 = app.getString(R.string.chizu_fix_none);
		}
		if (kind == Kind.WIFI && ChizuWifiLocator.holdsAgainstSat()) {
			// +094: the receiver has a fix; the taught place is shown in its stead — said so, not “SEARCH”
			searching = false;
			searchSinceMs = 0;
			line2 = app.getString(R.string.chizu_fix_sat_beside, ChizuGnssLog.usedCount());
		}
		if (searching) {
			long s = (now - searchSinceMs) / 1000;
			line2 = app.getString(R.string.chizu_fix_search, ChizuGnssLog.heardCount(), Math.round(ChizuGnssLog.top4()),
					String.format(Locale.US, "%d:%02d", s / 60, s % 60));
		}
		// +088: the pill says when it can be taught — no position of 地図's own, and networks in range to go by
		String teach = (kind == Kind.NONE || kind == Kind.NET) && ChizuWifiLocator.teachable(app)
				? app.getString(R.string.chizu_fix_teach) : null;
		return new String[] {line1, line2, teach};
	}

	/**
	 * The phone's pill (白い熊, 2026-11-18): under the right info panel and exactly as wide — its left edge at
	 * {@code left}, its top at {@code top}, {@code width} wide, as high as its lines need. The search line is
	 * broken at its dots, and a line too wide for the panel is set smaller. The canvas must not be rotated.
	 */
	public static void draw(@NonNull OsmandApplication app, @NonNull Canvas canvas, float left, float top, float width) {
		String[] lines = lines(app);
		Kind kind = lastKind;
		boolean searching = lines[1] != null;
		pillShown = lines[0] != null;
		tick(app, searching && kind != Kind.SAT);
		if (lines[0] == null) {
			return;
		}
		java.util.List<String> rows = new java.util.ArrayList<>();
		rows.add(lines[0]);
		if (searching) {
			// +087: what is heard on one row, strength and time together on the next
			int cut = lines[1].indexOf(" · ");
			if (cut < 0) {
				rows.add(lines[1]);
			} else {
				rows.add(lines[1].substring(0, cut).trim());
				rows.add(lines[1].substring(cut + 3).trim());
			}
		}
		// +092: with any other kind of position, how old the receiver's last own fix is — fresh orbits last a few hours
		if (kind != Kind.SAT && kind != Kind.CAR && !(kind == Kind.WIFI && ChizuWifiLocator.holdsAgainstSat())) {
			String age = lastSatAge(app);
			if (age != null) {
				rows.add(app.getString(R.string.chizu_fix_last_sat, age));
			}
		}
		// +090: the favourite the Wi-Fi position lands on
		String name = kind == Kind.WIFI ? ChizuWifiLocator.placeName() : null;
		if (name != null) {
			rows.add(1, name);
		}
		teachShown = lines[2] != null;
		if (teachShown) {
			rows.add(lines[2]);
		}
		float d = app.getResources().getDisplayMetrics().density;
		float pad = 6 * d;
		float inner = Math.max(10 * d, width - 2 * pad);
		Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
		text.setColor(ACCENT);
		text.setTextAlign(Paint.Align.CENTER);
		float[] sizes = new float[rows.size()];
		float h = pad;
		for (int n = 0; n < rows.size(); n++) {
			text.setTypeface(n == 0 || (teachShown && n == rows.size() - 1) ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
			float size = (n == 0 ? 15 : 13) * d;
			text.setTextSize(size);
			float w = text.measureText(rows.get(n));
			if (w > inner) {
				size *= inner / w;
			}
			sizes[n] = size;
			h += size * 1.3f;
		}
		h += pad * 0.6f;
		pill.set(left, top, left + width, top + h);
		Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
		bg.setColor(Color.BLACK);
		float radius = 8 * d;
		canvas.drawRoundRect(pill, radius, radius, bg);
		Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
		frame.setStyle(Paint.Style.STROKE);
		frame.setStrokeWidth(1.25f * d);
		frame.setColor(ACCENT);
		RectF edge = new RectF(pill);
		edge.inset(frame.getStrokeWidth() / 2, frame.getStrokeWidth() / 2);
		canvas.drawRoundRect(edge, radius, radius, frame);
		float y = top + pad;
		for (int n = 0; n < rows.size(); n++) {
			text.setTypeface(n == 0 || (teachShown && n == rows.size() - 1) ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
			text.setTextSize(sizes[n]);
			y += sizes[n] * 1.3f;
			canvas.drawText(rows.get(n), left + width / 2, y - sizes[n] * 0.3f, text);
		}
	}

	private static void tick(@NonNull OsmandApplication app, boolean wanted) {
		if (!tickPending && wanted) {
			tickPending = true;
			app.runInUIThread(() -> {
				tickPending = false;
				app.getOsmandMap().refreshMap();
			}, 1000);
		}
	}

	/**
	 * The car screen's pill: its right edge at {@code right}, its top at {@code top}, exactly {@code height}
	 * high — the speed box's place and size (白い熊, 2026-11-18). Returns nothing drawn when switched off.
	 */
	public static void drawOnCar(@NonNull OsmandApplication app, @NonNull Canvas canvas, float right, float top, float height) {
		String[] lines = lines(app);
		tick(app, lines[1] != null && lastKind != Kind.SAT);
		if (lines[0] == null || !isPillOnCar(app)) {
			return;
		}
		boolean two = lines[1] != null;
		Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
		text.setColor(ACCENT);
		text.setTypeface(Typeface.DEFAULT_BOLD);
		text.setTextAlign(Paint.Align.CENTER);
		text.setTextSize(height * (two ? 0.34f : 0.42f));
		Paint small = new Paint(text);
		small.setTypeface(Typeface.DEFAULT);
		small.setTextSize(height * 0.28f);
		float pad = height * 0.3f;
		float w = Math.max(text.measureText(lines[0]), two ? small.measureText(lines[1]) : 0) + 2 * pad;
		RectF r = new RectF(right - w, top, right, top + height);
		float radius = height * 0.25f;
		Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
		bg.setColor(Color.BLACK);
		canvas.drawRoundRect(r, radius, radius, bg);
		Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
		frame.setStyle(Paint.Style.STROKE);
		frame.setStrokeWidth(Math.max(1.5f, height * 0.035f));
		frame.setColor(ACCENT);
		float half = frame.getStrokeWidth() / 2;
		r.inset(half, half);
		canvas.drawRoundRect(r, radius, radius, frame);
		float cx = right - w / 2;
		if (two) {
			canvas.drawText(lines[0], cx, top + height * 0.44f, text);
			canvas.drawText(lines[1], cx, top + height * 0.82f, small);
		} else {
			canvas.drawText(lines[0], cx, top + height * 0.5f - (text.ascent() + text.descent()) / 2, text);
		}
	}

	public static boolean isPillOnCar(@NonNull OsmandApplication app) {
		return app.getSharedPreferences(ChizuTheme.PREFS_NAME, android.content.Context.MODE_PRIVATE).getBoolean(PILL_ON_CAR, true);
	}

	public static void setPillOnCar(@NonNull OsmandApplication app, boolean on) {
		app.getSharedPreferences(ChizuTheme.PREFS_NAME, android.content.Context.MODE_PRIVATE).edit().putBoolean(PILL_ON_CAR, on).apply();
		ChizuDriveLog.log("position pill on the car screen " + (on ? "on" : "off"));
		app.getOsmandMap().refreshMap();
	}

	/** A tap on the pill: teaching when it offers it, else the full numbers. */
	public static boolean onTap(@NonNull OsmandApplication app, @Nullable net.osmand.plus.activities.MapActivity activity,
	                            float x, float y) {
		if (!pillShown || !pill.contains(x, y)) {
			return false;
		}
		if (teachShown && activity != null) {
			ChizuWifiTeach.start(activity);
		} else {
			app.showToastMessage(details(app));
		}
		return true;
	}

	/** A long press on the pill: the menu of everything to do with the position. */
	public static boolean onLongPress(@Nullable net.osmand.plus.activities.MapActivity activity, float x, float y) {
		if (!pillShown || activity == null || !pill.contains(x, y)) {
			return false;
		}
		ChizuWifiTeach.showMenu(activity);
		return true;
	}

	@NonNull
	static String details(@NonNull OsmandApplication app) {
		Location l = app.getLocationProvider().getLastKnownLocation();
		String pos = l == null ? app.getString(R.string.chizu_fix_none)
				: String.format(Locale.US, "%s, %s%s", kind(app), l.getProvider(),
				l.hasAccuracy() ? String.format(Locale.US, ", ±%.0f m", l.getAccuracy()) : "");
		String age = lastSatAge(app);
		return pos + "\n" + ChizuGnssLog.orbits() + String.format(Locale.US, ", strongest four %.0f dB-Hz", ChizuGnssLog.top4())
				+ (age == null ? "" : "\n" + app.getString(R.string.chizu_fix_last_sat, age));
	}

	@Nullable
	public static Kind lastKind() {
		return lastKind;
	}
}
