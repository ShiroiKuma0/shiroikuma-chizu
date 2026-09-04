package net.osmand.plus.chizu;

import android.os.Environment;
import android.util.Log;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.data.RotatedTileBox;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.chizu.ChizuTracks.TrackError;
import net.osmand.util.MapUtils;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * shiroikuma fork: the base-map contract — 白い熊 自由作業盤 asks for the map under a block of
 * standard Web Mercator tiles and gets a PNG, with nothing drawn on it and nothing filed here.
 *
 * <p>The request is in tiles, never in a bounding box, and that is the whole point: {@code z/x/y}
 * plus a size fully determines the geography, so both apps compute the same extent from the same
 * published definition and neither has to remember a framing the other chose. 自由作業盤 caches
 * one such cutout per neighbourhood and strokes every walk that fits inside it locally, instead of
 * asking this app for a fresh 2.5 MB picture — and a library entry — per walk.
 *
 * <p>The tile grid is the ordinary slippy-map one, which is also OsmAnd's own
 * ({@link MapUtils#getTileNumberX}/{@link MapUtils#getTileNumberY}), so the block maps onto a
 * {@link RotatedTileBox} exactly: north up, no rotation, {@code tile_px} pixels to the tile.
 * Nothing is auto-fitted and nothing is padded — the PNG is the block and only the block.
 */
public class ChizuBaseMap {

	private static final String TAG = "ChizuAutomation";

	/** The fork's black, when there was no map data at all to draw. */
	@ColorInt
	private static final int EMPTY_BACKGROUND = 0xFF000000;

	/** The rasterizer's own unit: one tile is 256 px at map density 1. */
	private static final int BASE_TILE_PX = 256;

	private static final int MIN_ZOOM = 1;
	private static final int MAX_ZOOM = 19;
	/** 自由作業盤 asks for at most 6; the ceiling is looser so a later version needs no build here. */
	private static final int MAX_TILES = 8;
	private static final int MIN_TILE_PX = 64;
	private static final int MAX_TILE_PX = 1024;
	/** Room for 6 tiles at 512 px — twice what 自由作業盤 asks for — at 36 MB of bitmap a side. */
	private static final int MAX_EDGE_PX = 3072;

	private ChizuBaseMap() {
	}

	public static class Params {

		public int zoom;
		public long tileX;
		public long tileY;
		public int tilesW;
		public int tilesH;
		public int tilePx = BASE_TILE_PX;
		public String outPath;
		/** Where 自由作業盤 wants the PNG now that it keeps its cutouts in its own database. */
		public String outUri;
		/** Pinned to the day style by default: a cutout cached for months must not drift. */
		public Boolean night = Boolean.FALSE;
	}

	public static class Result {

		public String outPath;
		public int width;
		public int height;
		public String mapDetail;
	}

	/**
	 * Renders the block and writes it to {@link Params#outUri}, or to {@link Params#outPath} when
	 * the caller still names a file.
	 *
	 * <p>To a file, the PNG appears at its final name only once it is whole: it is written beside
	 * itself and renamed into place. To a URI there is no such rename — the picture is rendered
	 * whole in memory first, but past the moment the stream opens, only the reply says whether the
	 * bytes arrived complete. 自由作業盤 accounts for that at its end: {@code out_uri} points at a
	 * temp file of its own and the bytes reach its database only on {@code OK:}. That matters more
	 * than it used to — a cutout is a row now, inside a category 白い熊 can restore from months
	 * later, so a half-written one would not merely blank an area, it would be backed up.
	 */
	@NonNull
	public static Result render(@NonNull OsmandApplication app, @NonNull Params params)
			throws TrackError {
		validate(params);
		// refused before anything opens: a request we will not honour must not touch their file
		File out = params.outUri != null ? null : target(app, params.outPath);
		RotatedTileBox tileBox = tileBox(params);

		ChizuTrackImages.Shot shot = ChizuTrackImages.area(app, tileBox, EMPTY_BACKGROUND, params.night);
		try {
			if (out != null) {
				writeToFile(shot, out);
			} else {
				try {
					ChizuTrackImages.write(shot.bitmap, ChizuUris.write(app, params.outUri));
				} catch (IOException error) {
					throw new TrackError("cannot write " + params.outUri + ": " + reason(error));
				}
			}
		} finally {
			shot.bitmap.recycle();
		}

		Result result = new Result();
		result.outPath = out != null ? out.getAbsolutePath() : params.outUri;
		result.width = tileBox.getPixWidth();
		result.height = tileBox.getPixHeight();
		result.mapDetail = shot.detail.name().toLowerCase(Locale.US);
		Log.i(TAG, "basemap z" + params.zoom + " x" + params.tileX + " y" + params.tileY
				+ " " + params.tilesW + "x" + params.tilesH + " → " + result.width + "×" + result.height
				+ " (" + result.mapDetail + ") " + result.outPath);
		return result;
	}

	// ---------- the block ----------

	/**
	 * The block as a tile box. The centre is the block's centre pixel expressed as a tile
	 * coordinate and turned back into a lat/lon, because that is the only handle
	 * {@link RotatedTileBox} offers on where the picture sits — the round trip through
	 * {@link MapUtils} is exact to well under a millionth of a pixel.
	 *
	 * <p>{@code tile_px} rides on both densities: the map density is what makes a tile that many
	 * pixels wide in the first place, and the screen density has to follow it or the labels would
	 * be drawn for a phone screen and swamp a 256-pixel tile.
	 */
	@NonNull
	private static RotatedTileBox tileBox(@NonNull Params params) {
		int width = params.tilesW * params.tilePx;
		int height = params.tilesH * params.tilePx;
		double centreTileX = params.tileX + (double) params.tilesW / 2;
		double centreTileY = params.tileY + (double) params.tilesH / 2;
		float scale = (float) params.tilePx / BASE_TILE_PX;
		return new RotatedTileBox.RotatedTileBoxBuilder()
				.setLocation(MapUtils.getLatitudeFromTile(params.zoom, centreTileY),
						MapUtils.getLongitudeFromTile(params.zoom, centreTileX))
				.setZoom(params.zoom)
				.density(scale)
				.setMapDensity(scale)
				.setPixelDimensions(width, height, 0.5f, 0.5f)
				.build();
	}

	/**
	 * Everything that would move the geography rather than merely resize the picture is refused
	 * here instead of quietly corrected: a cutout whose extent is not the one asked for cannot be
	 * drawn on, and 自由作業盤 would cache it for months before anyone noticed.
	 */
	private static void validate(@NonNull Params params) throws TrackError {
		if (params.zoom < MIN_ZOOM || params.zoom > MAX_ZOOM) {
			throw new TrackError("zoom out of range: " + params.zoom
					+ " (" + MIN_ZOOM + "–" + MAX_ZOOM + ")");
		}
		if (params.tilesW < 1 || params.tilesW > MAX_TILES
				|| params.tilesH < 1 || params.tilesH > MAX_TILES) {
			throw new TrackError("tiles_w/tiles_h out of range: " + params.tilesW + "x" + params.tilesH
					+ " (1–" + MAX_TILES + ")");
		}
		if (params.tilePx < MIN_TILE_PX || params.tilePx > MAX_TILE_PX) {
			throw new TrackError("tile_px out of range: " + params.tilePx
					+ " (" + MIN_TILE_PX + "–" + MAX_TILE_PX + ")");
		}
		if (params.tilePx % 2 != 0) {
			// the tile box centres on a whole pixel; an odd edge would shift the block half a pixel
			throw new TrackError("tile_px must be even: " + params.tilePx);
		}
		if (params.tilesW * params.tilePx > MAX_EDGE_PX || params.tilesH * params.tilePx > MAX_EDGE_PX) {
			throw new TrackError("picture too large: " + params.tilesW * params.tilePx + "x"
					+ params.tilesH * params.tilePx + " (max " + MAX_EDGE_PX + " a side)");
		}
		long world = 1L << params.zoom;
		if (params.tileX < 0 || params.tileY < 0
				|| params.tileX + params.tilesW > world || params.tileY + params.tilesH > world) {
			throw new TrackError("tile block outside the world at zoom " + params.zoom + ": "
					+ params.tileX + "," + params.tileY + " + " + params.tilesW + "x" + params.tilesH
					+ " (0–" + (world - 1) + ")");
		}
	}

	// ---------- the file ----------

	@NonNull
	private static File target(@NonNull OsmandApplication app, @Nullable String outPath)
			throws TrackError {
		if (outPath == null || outPath.trim().isEmpty()) {
			// not the pre-URI wording: 自由作業盤 matches that one to recognise an older build
			throw new TrackError("no out_uri or out_path");
		}
		File out = new File(outPath.trim());
		if (!out.isAbsolute()) {
			out = new File(Environment.getExternalStorageDirectory(), outPath.trim());
		}
		if (!out.getAbsolutePath().startsWith(app.getAppPath("").getAbsolutePath())
				&& !ChizuStorage.hasAllFilesAccess()) {
			throw new TrackError("no-storage-access");
		}
		if (out.isDirectory()) {
			throw new TrackError("out_path is a directory: " + out.getAbsolutePath());
		}
		File dir = out.getParentFile();
		if (dir == null) {
			throw new TrackError("out_path has no folder: " + out.getAbsolutePath());
		}
		if (!dir.exists() && !dir.mkdirs()) {
			throw new TrackError("cannot create directory " + dir.getAbsolutePath());
		}
		if (!dir.isDirectory()) {
			throw new TrackError("not a directory: " + dir.getAbsolutePath());
		}
		return out;
	}

	/** Beside itself, then into place — so nothing under the real name is ever half a picture. */
	private static void writeToFile(@NonNull ChizuTrackImages.Shot shot, @NonNull File out)
			throws TrackError {
		File part = new File(out.getParentFile(), out.getName() + ".part");
		try {
			try {
				ChizuTrackImages.write(shot.bitmap, part);
			} catch (IOException error) {
				// a stump under a name nobody reads: the picture never reached out_path at all
				part.delete();
				throw error;
			}
			install(part, out);
		} catch (IOException error) {
			throw new TrackError("cannot write " + out.getAbsolutePath() + ": " + reason(error));
		}
	}

	/** The whole picture takes the name, in one step, replacing whatever held it before. */
	private static void install(@NonNull File part, @NonNull File out) throws IOException {
		if (out.exists() && !out.delete()) {
			throw new IOException("cannot replace " + out.getAbsolutePath());
		}
		if (!part.renameTo(out)) {
			throw new IOException("cannot rename " + part.getName() + " to " + out.getName());
		}
	}

	@NonNull
	private static String reason(@NonNull Throwable error) {
		String message = error.getMessage();
		return message != null && !message.trim().isEmpty() ? message
				: error.getClass().getSimpleName();
	}
}
