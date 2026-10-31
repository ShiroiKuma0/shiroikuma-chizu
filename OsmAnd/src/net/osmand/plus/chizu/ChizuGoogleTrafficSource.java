package net.osmand.plus.chizu;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;
import net.osmand.binary.RouteDataObject;
import net.osmand.map.ChizuTileIdentity;
import net.osmand.router.RouteSegmentResult;
import net.osmand.util.Algorithms;
import net.osmand.util.MapUtils;

import org.apache.commons.logging.Log;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 白い熊 地図: reads traffic from Google traffic tiles along a route.
 * <p>
 * The tiles are the traffic layer alone, every map feature styled off
 * ({@code lyrs=m,traffic&apistyle=s.t:0|p.v:off}): transparent except the green / yellow / red /
 * dark-red traffic lines, so roofs, labels and parks cannot be mistaken for jams. Only the tiles
 * the route crosses are fetched, through {@link ChizuTileIdentity} (https, generic User-Agent, no
 * Referer, no cookies). Each tile is reduced to one class byte per pixel and kept for two minutes,
 * so the candidates of one planning share their tiles.
 * <p>
 * Every ~15 m along a segment the pixel under the road is read; Google draws two-way streets as
 * two lines offset to the right of each direction (right-hand traffic), so the search walks from
 * the centreline to the right of travel first, then a little to the left.
 */
public class ChizuGoogleTrafficSource implements ChizuTraffic.Source {

	private static final Log LOG = PlatformUtil.getLog(ChizuGoogleTrafficSource.class);

	private static final int ZOOM = 15;
	private static final int TILE = 256;
	private static final String URL = "https://mts%d.google.com/vt/lyrs=m,traffic&x=%d&y=%d&z=%d"
			+ "&apistyle=s.t%%3A0%%7Cp.v%%3Aoff";
	private static final long TTL_MS = 2 * 60 * 1000;
	private static final int MAX_TILES = 250;

	private static final float STEP_M = 15f;
	private static final float END_SKIP_M = 20f;
	private static final int SEARCH_RIGHT_PX = 8;
	private static final int SEARCH_LEFT_PX = 4;

	// per-pixel classes
	private static final byte NONE = 0;
	private static final byte GREEN = 1;
	private static final byte YELLOW = 2;
	private static final byte RED = 3;
	private static final byte DARK_RED = 4;
	private static final byte OTHER = 5;

	private static final byte[] EMPTY_TILE = new byte[TILE * TILE];

	private static final class Tile {
		final byte[] classes;
		final long time;

		Tile(byte[] classes, long time) {
			this.classes = classes;
			this.time = time;
		}
	}

	private final Map<Long, Tile> cache = new LinkedHashMap<Long, Tile>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, Tile> eldest) {
			return size() > MAX_TILES;
		}
	};

	private final ExecutorService fetchers = Executors.newFixedThreadPool(6);

	private static final class Sample {
		final int segment;
		final double px; // global pixel coordinates at ZOOM
		final double py;
		final double dx; // unit direction of travel in pixel space
		final double dy;

		Sample(int segment, double px, double py, double dx, double dy) {
			this.segment = segment;
			this.px = px;
			this.py = py;
			this.dx = dx;
			this.dy = dy;
		}
	}

	@NonNull
	@Override
	public List<ChizuTraffic.Level> sample(@NonNull List<RouteSegmentResult> segments) throws InterruptedException {
		List<Sample> samples = new ArrayList<>();
		for (int i = 0; i < segments.size(); i++) {
			addSamples(i, segments.get(i), samples);
		}
		Map<Long, byte[]> tiles = loadTiles(samples);

		int[][] counts = new int[segments.size()][6];
		int[] taken = new int[segments.size()];
		for (Sample s : samples) {
			counts[s.segment][classify(s, tiles)]++;
			taken[s.segment]++;
		}
		List<ChizuTraffic.Level> levels = new ArrayList<>(segments.size());
		for (int i = 0; i < segments.size(); i++) {
			levels.add(grade(counts[i], taken[i]));
		}
		return levels;
	}

	/**
	 * 白い熊 地図 road log: the colour under each point, read across the direction of travel as for
	 * route segments. Each point is {lat, lon, bearing in degrees}.
	 */
	@NonNull
	public List<ChizuTraffic.Level> pointLevels(@NonNull List<double[]> points) throws InterruptedException {
		List<Sample> samples = new ArrayList<>(points.size());
		for (int i = 0; i < points.size(); i++) {
			double[] p = points.get(i);
			double b = Math.toRadians(p[2]);
			samples.add(new Sample(i, MapUtils.getTileNumberX(ZOOM, p[1]) * TILE, MapUtils.getTileNumberY(ZOOM, p[0]) * TILE,
					Math.sin(b), -Math.cos(b)));
		}
		Map<Long, byte[]> tiles = loadTiles(samples);
		List<ChizuTraffic.Level> levels = new ArrayList<>(samples.size());
		for (Sample s : samples) {
			switch (classify(s, tiles)) {
				case GREEN:
					levels.add(ChizuTraffic.Level.FREE);
					break;
				case YELLOW:
					levels.add(ChizuTraffic.Level.SLOW);
					break;
				case RED:
					levels.add(ChizuTraffic.Level.JAM);
					break;
				case DARK_RED:
					levels.add(ChizuTraffic.Level.STANDSTILL);
					break;
				default:
					levels.add(ChizuTraffic.Level.UNKNOWN);
			}
		}
		return levels;
	}

	public void shutdown() {
		fetchers.shutdownNow();
	}

	@NonNull
	private static ChizuTraffic.Level grade(int[] c, int taken) {
		int classified = c[GREEN] + c[YELLOW] + c[RED] + c[DARK_RED];
		if (classified == 0 || classified < Math.min(3, taken)) {
			return ChizuTraffic.Level.UNKNOWN;
		}
		float jam = (c[RED] + c[DARK_RED]) / (float) classified;
		float slow = (c[YELLOW] + c[RED] + c[DARK_RED]) / (float) classified;
		if (jam >= 0.4f) {
			return c[DARK_RED] > c[RED] ? ChizuTraffic.Level.STANDSTILL : ChizuTraffic.Level.JAM;
		} else if (slow >= 0.4f) {
			return ChizuTraffic.Level.SLOW;
		}
		return ChizuTraffic.Level.FREE;
	}

	private static void addSamples(int index, @NonNull RouteSegmentResult segment, @NonNull List<Sample> out) {
		RouteDataObject o = segment.getObject();
		int start = segment.getStartPointIndex();
		int end = segment.getEndPointIndex();
		if (start == end || o.getPointsLength() == 0) {
			return;
		}
		int step = start < end ? 1 : -1;
		// lengths of the pieces, to place samples by distance
		List<double[]> pts = new ArrayList<>();
		for (int k = start; ; k += step) {
			double lat = MapUtils.get31LatitudeY(o.getPoint31YTile(k));
			double lon = MapUtils.get31LongitudeX(o.getPoint31XTile(k));
			pts.add(new double[] {lat, lon});
			if (k == end) {
				break;
			}
		}
		double total = 0;
		double[] lens = new double[pts.size() - 1];
		for (int k = 0; k < lens.length; k++) {
			lens[k] = MapUtils.getDistance(pts.get(k)[0], pts.get(k)[1], pts.get(k + 1)[0], pts.get(k + 1)[1]);
			total += lens[k];
		}
		if (total <= 0) {
			return;
		}
		List<Double> at = new ArrayList<>();
		if (total < 3 * END_SKIP_M) {
			at.add(total / 2);
		} else {
			for (double d = END_SKIP_M; d <= total - END_SKIP_M; d += STEP_M) {
				at.add(d);
			}
		}
		int piece = 0;
		double pieceStart = 0;
		for (double d : at) {
			while (piece < lens.length - 1 && pieceStart + lens[piece] < d) {
				pieceStart += lens[piece];
				piece++;
			}
			double t = lens[piece] > 0 ? (d - pieceStart) / lens[piece] : 0;
			double[] a = pts.get(piece);
			double[] b = pts.get(piece + 1);
			double ax = MapUtils.getTileNumberX(ZOOM, a[1]) * TILE;
			double ay = MapUtils.getTileNumberY(ZOOM, a[0]) * TILE;
			double bx = MapUtils.getTileNumberX(ZOOM, b[1]) * TILE;
			double by = MapUtils.getTileNumberY(ZOOM, b[0]) * TILE;
			double len = Math.hypot(bx - ax, by - ay);
			if (len == 0) {
				continue;
			}
			out.add(new Sample(index, ax + (bx - ax) * t, ay + (by - ay) * t, (bx - ax) / len, (by - ay) / len));
		}
	}

	private static byte classify(@NonNull Sample s, @NonNull Map<Long, byte[]> tiles) {
		// right of travel with y pointing down is (-dy, dx)
		double rx = -s.dy;
		double ry = s.dx;
		byte c = scan(s, rx, ry, 0, SEARCH_RIGHT_PX, tiles);
		if (c == NONE) {
			c = scan(s, -rx, -ry, 1, SEARCH_LEFT_PX, tiles);
		}
		return c == OTHER ? NONE : c;
	}

	/** Walks from offset {@code from} to {@code to} px; returns the majority class of the first line hit. */
	private static byte scan(@NonNull Sample s, double nx, double ny, int from, int to,
	                         @NonNull Map<Long, byte[]> tiles) {
		int[] run = new int[6];
		boolean inLine = false;
		for (int k = from; k <= to; k++) {
			byte c = pixel(s.px + nx * k, s.py + ny * k, tiles);
			if (c != NONE) {
				inLine = true;
				run[c]++;
			} else if (inLine) {
				break;
			}
		}
		if (!inLine) {
			return NONE;
		}
		byte best = OTHER;
		int max = 0;
		for (byte c = GREEN; c <= DARK_RED; c++) {
			if (run[c] > max) {
				max = run[c];
				best = c;
			}
		}
		return best;
	}

	private static byte pixel(double gx, double gy, @NonNull Map<Long, byte[]> tiles) {
		int x = (int) Math.floor(gx);
		int y = (int) Math.floor(gy);
		byte[] tile = tiles.get(key(Math.floorDiv(x, TILE), Math.floorDiv(y, TILE)));
		if (tile == null) {
			return NONE;
		}
		return tile[Math.floorMod(y, TILE) * TILE + Math.floorMod(x, TILE)];
	}

	private static long key(int tx, int ty) {
		return ((long) tx << 32) | (ty & 0xffffffffL);
	}

	@NonNull
	private Map<Long, byte[]> loadTiles(@NonNull List<Sample> samples) throws InterruptedException {
		// every tile within the search reach of a sample
		Map<Long, int[]> wanted = new HashMap<>();
		int reach = SEARCH_RIGHT_PX + 1;
		for (Sample s : samples) {
			for (int ox = -reach; ox <= reach; ox += reach) {
				for (int oy = -reach; oy <= reach; oy += reach) {
					int tx = (int) Math.floor((s.px + ox) / TILE);
					int ty = (int) Math.floor((s.py + oy) / TILE);
					wanted.put(key(tx, ty), new int[] {tx, ty});
				}
			}
		}
		Map<Long, byte[]> result = new HashMap<>();
		Map<Long, Future<byte[]>> pending = new HashMap<>();
		long now = System.currentTimeMillis();
		synchronized (cache) {
			for (Map.Entry<Long, int[]> e : wanted.entrySet()) {
				Tile t = cache.get(e.getKey());
				if (t != null && now - t.time < TTL_MS) {
					result.put(e.getKey(), t.classes);
				}
			}
		}
		for (Map.Entry<Long, int[]> e : wanted.entrySet()) {
			if (!result.containsKey(e.getKey())) {
				int[] xy = e.getValue();
				pending.put(e.getKey(), fetchers.submit(() -> fetch(xy[0], xy[1])));
			}
		}
		int failed = 0;
		for (Map.Entry<Long, Future<byte[]>> e : pending.entrySet()) {
			byte[] classes;
			try {
				classes = e.getValue().get();
			} catch (ExecutionException ex) {
				classes = null;
			}
			if (classes == null) {
				failed++;
				continue;
			}
			result.put(e.getKey(), classes);
			synchronized (cache) {
				cache.put(e.getKey(), new Tile(classes, now));
			}
		}
		LOG.info("Traffic tiles: " + wanted.size() + " needed, " + pending.size() + " fetched, " + failed + " failed");
		return result;
	}

	@Nullable
	private static byte[] fetch(int tx, int ty) {
		String url = String.format(Locale.US, URL, Math.floorMod(tx + ty, 4), tx, ty, ZOOM);
		HttpURLConnection connection = null;
		try {
			connection = ChizuTileIdentity.open(url, null, null);
			connection.setConnectTimeout(10000);
			connection.setReadTimeout(15000);
			if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
				LOG.warn("Traffic tile " + tx + "/" + ty + ": HTTP " + connection.getResponseCode());
				return null;
			}
			byte[] bytes;
			try (InputStream in = connection.getInputStream()) {
				bytes = Algorithms.readBytesFromInputStream(in);
			}
			Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
			if (bitmap == null) {
				return null;
			}
			if (bitmap.getWidth() < TILE || bitmap.getHeight() < TILE) {
				// Google answers a featureless area with a 1×1 transparent image
				bitmap.recycle();
				return EMPTY_TILE;
			}
			int[] argb = new int[TILE * TILE];
			bitmap.getPixels(argb, 0, TILE, 0, 0, TILE, TILE);
			bitmap.recycle();
			byte[] classes = new byte[TILE * TILE];
			float[] hsv = new float[3];
			for (int i = 0; i < argb.length; i++) {
				classes[i] = classifyColor(argb[i], hsv);
			}
			return classes;
		} catch (Exception e) {
			LOG.warn("Traffic tile " + tx + "/" + ty + " failed: " + e.getMessage());
			return null;
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	/**
	 * Google's traffic palette: green (22,224,152) h≈159, yellow (255,207,67) h≈45,
	 * red (242,78,66) h≈4, dark red (169,39,39) h=0. Line outlines are darker shades of the same
	 * hue, so red and dark red are told apart by hue (red keeps g &gt; b), not brightness.
	 */
	static byte classifyColor(int argb, float[] hsv) {
		if (Color.alpha(argb) < 128) {
			return NONE;
		}
		Color.colorToHSV(argb, hsv);
		float h = hsv[0];
		float s = hsv[1];
		float v = hsv[2];
		if (s < 0.4f || v < 0.3f) {
			return OTHER;
		}
		if (h >= 100 && h <= 200) {
			return GREEN;
		} else if (h >= 20 && h <= 65) {
			return YELLOW;
		} else if (h >= 2 && h < 20) {
			return RED;
		} else if (h < 2 || h >= 340) {
			return DARK_RED;
		}
		return OTHER;
	}
}
