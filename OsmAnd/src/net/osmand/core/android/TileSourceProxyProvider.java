package net.osmand.core.android;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;

import androidx.annotation.Nullable;

import net.osmand.IndexConstants;
import net.osmand.core.jni.AlphaChannelPresence;
import net.osmand.core.jni.IMapTiledDataProvider;
import net.osmand.core.jni.IQueryController;
import net.osmand.core.jni.MapStubStyle;
import net.osmand.core.jni.SWIGTYPE_p_QByteArray;
import net.osmand.core.jni.SwigUtilities;
import net.osmand.core.jni.ZoomLevel;
import net.osmand.core.jni.interface_ImageMapLayerProvider;
import net.osmand.map.ITileSource;
import net.osmand.map.MapTileDownloader;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.resources.BitmapTilesCache;
import net.osmand.plus.resources.ResourceManager;
import net.osmand.plus.resources.TileLoadDownloadRequest;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.util.MapUtils;


public class TileSourceProxyProvider extends interface_ImageMapLayerProvider {

	private static final int IMAGE_LOAD_TIMEOUT = 30000;

	private final String dirWithTiles;
	private final ResourceManager rm;
	private final BitmapTilesCache tilesCache;
	private final ITileSource tileSource;
	
	public TileSourceProxyProvider(OsmandApplication app, ITileSource tileSource) {
		this.dirWithTiles = app.getAppPath(IndexConstants.TILES_INDEX_DIR).getAbsolutePath();
		this.rm = app.getResourceManager();
		this.tilesCache = rm.getBitmapTilesCache();
		this.tileSource = tileSource;
	}

	@Override
	public MapStubStyle getDesiredStubsStyle() {
		return MapStubStyle.Unspecified;
	}
	
	@Override
	public ZoomLevel getMinZoom() {
		return ZoomLevel.swigToEnum(tileSource.getMinimumZoomSupported());
	}
	
	@Override
	public ZoomLevel getMaxZoom() {
		return ZoomLevel.swigToEnum(tileSource.getMaximumZoomSupported());
	}

	@Override
	public ZoomLevel getMinVisibleZoom() {
		return getMinZoom();
	}

	@Override
	public ZoomLevel getMaxVisibleZoom() {
		return getMaxZoom();
	}

	@Override
	public boolean supportsNaturalObtainData() {
		return true;
	}

	@Override
	public long obtainImageData(IMapTiledDataProvider.Request request, SWIGTYPE_p_QByteArray byteArray) {
		// 白い熊 地図 +041: counted for the minute “perf” line — what the raster layers cost
		long chizuT0 = System.currentTimeMillis();
		long res = chizuObtainImageData(request, byteArray);
		net.osmand.plus.chizu.ChizuPerfLog.tile(res != 0, System.currentTimeMillis() - chizuT0);
		return res;
	}

	/** 白い熊 地図 +048: decoded tiles, newest use last, up to {@value #CHIZU_DECODED_MAX_BYTES} bytes in all. */
	private static final long CHIZU_DECODED_MAX_BYTES = 48L << 20;
	private static long chizuDecodedBytes;
	private static final java.util.LinkedHashMap<String, ChizuDecoded> CHIZU_DECODED =
			new java.util.LinkedHashMap<>(256, 0.75f, true);

	private static final class ChizuDecoded {
		final long crc;
		final byte[] pixels;
		final long size;

		ChizuDecoded(long crc, byte[] pixels, long size) {
			this.crc = crc;
			this.pixels = pixels;
			this.size = size;
		}
	}

	private static void chizuRemember(String key, ChizuDecoded d) {
		if (key == null) {
			return;
		}
		synchronized (CHIZU_DECODED) {
			ChizuDecoded old = CHIZU_DECODED.put(key, d);
			chizuDecodedBytes += d.pixels.length - (old == null ? 0 : old.pixels.length);
			java.util.Iterator<ChizuDecoded> it = CHIZU_DECODED.values().iterator();
			while (chizuDecodedBytes > CHIZU_DECODED_MAX_BYTES && it.hasNext()) {
				chizuDecodedBytes -= it.next().pixels.length;
				it.remove();
			}
		}
	}

	private long chizuObtainImageData(IMapTiledDataProvider.Request request, SWIGTYPE_p_QByteArray byteArray) {
		long requestTimestamp = System.currentTimeMillis();
		IQueryController queryController = request.getQueryController();
		boolean cacheOnly = request.getCacheOnly();
		int zoom = request.getZoom().swigValue();
		int tileX = request.getTileId().getX();
		int tileY = request.getTileId().getY();
		float tileSize = tileSource.getTileSize();
		double offsetY = 0;
		if (tileSource.isEllipticYTile()) {
			double latitude = MapUtils.getLatitudeFromTile(zoom, tileY);
			double[] tileOffset = MapUtils.getTileEllipsoidNumberAndOffsetY(zoom, latitude, tileSource.getTileSize());
			tileY = (int) tileOffset[0];
			offsetY = tileOffset[1];
		}
		boolean shiftedTile = offsetY > 0;
		byte[] firstTileData;
		byte[] secondTileData;
		firstTileData = getTileBytes(tileX, tileY, zoom, requestTimestamp, cacheOnly, queryController);
		if (firstTileData == null) {
			return 0;
		}

		// 白い熊 地図 +048: a tile asked for again with the same content is handed over as decoded before
		String chizuKey = null;
		long chizuCrc = 0;
		if (!shiftedTile) {
			chizuKey = tileSource.getName() + '@' + zoom + '/' + tileX + '/' + tileY;
			java.util.zip.CRC32 crc = new java.util.zip.CRC32();
			crc.update(firstTileData, 0, firstTileData.length);
			chizuCrc = crc.getValue() ^ ((long) firstTileData.length << 32);
			ChizuDecoded hit;
			synchronized (CHIZU_DECODED) {
				hit = CHIZU_DECODED.get(chizuKey);
			}
			if (hit != null && hit.crc == chizuCrc) {
				SwigUtilities.appendToQByteArray(byteArray, hit.pixels);
				net.osmand.plus.chizu.ChizuPerfLog.tileReused();
				return hit.size;
			}
		}

		Bitmap firstTileBitmap = BitmapFactory.decodeByteArray(firstTileData, 0, firstTileData.length);
		if (firstTileBitmap == null) {
			return 0;
		}
		if (shiftedTile) {
			Bitmap resultTileBitmap = Bitmap.createBitmap((int)tileSize, (int)tileSize, Bitmap.Config.ARGB_8888);
			Paint paint = new Paint();
			Canvas canvas = new Canvas(resultTileBitmap);
			canvas.translate(0, (float)-offsetY);
			canvas.drawBitmap(firstTileBitmap, 0, 0, paint);
			secondTileData = getTileBytes(tileX, tileY + 1, zoom, requestTimestamp, cacheOnly, queryController);
			if (secondTileData != null) {
				Bitmap secondTileBitmap = BitmapFactory.decodeByteArray(secondTileData, 0, secondTileData.length);
				if (secondTileBitmap == null) {
					return 0;
				}
				canvas.translate(0, tileSize);
				canvas.drawBitmap(secondTileBitmap, 0, 0, paint);
				secondTileBitmap.recycle(); // 白い熊 地図 +041
			}
			byte[] bytes = AndroidUtils.getByteArrayFromBitmap(resultTileBitmap);
			SwigUtilities.appendToQByteArray(byteArray, bytes);
			long size = (long) resultTileBitmap.getHeight() << 32 | resultTileBitmap.getWidth();
			// 白い熊 地図 +041: the pixels are copied out — free them now, not at some later GC
			resultTileBitmap.recycle();
			firstTileBitmap.recycle();
			return size;
		} else {
			byte[] bytes = AndroidUtils.getByteArrayFromBitmap(firstTileBitmap);
			SwigUtilities.appendToQByteArray(byteArray, bytes);
			long size = (long) firstTileBitmap.getHeight() << 32 | firstTileBitmap.getWidth();
			firstTileBitmap.recycle(); // 白い熊 地図 +041
			chizuRemember(chizuKey, new ChizuDecoded(chizuCrc, bytes, size));
			return size;
		}

	}

	@Override
	public boolean supportsObtainImage() {
		return false;
	}

	@Override
	public boolean supportsNaturalObtainDataAsync() {
		return true;
	}

	@Override
	public long getTileSize() {
		return tileSource.getTileSize();
	}
	
	@Override
	public float getTileDensityFactor() {
		return 1.0f;
	}
	
	@Override
	public AlphaChannelPresence getAlphaChannelPresence() {
		return AlphaChannelPresence.Unknown;
	}

	private static class TileReadyCallback implements MapTileDownloader.IMapDownloaderCallback {
		private final ITileSource tileSource;
		private final int x;
		private final int y;
		private final int zoom;
		private boolean ready;
		private final Object sync = new Object();

		public TileReadyCallback(ITileSource tileSource, int x, int y, int zoom) {
			this.tileSource = tileSource;
			this.x = x;
			this.y = y;
			this.zoom = zoom;
		}

		public boolean isReady() {
			return ready;
		}

		public Object getSync() {
			return sync;
		}

		@Override
		public void tileDownloaded(MapTileDownloader.DownloadRequest request) {
			if (!(request instanceof TileLoadDownloadRequest)) {
				return;
			}
			TileLoadDownloadRequest tileLoadRequest = (TileLoadDownloadRequest)request;

			if (tileSource != tileLoadRequest.tileSource ||
					x != tileLoadRequest.xTile ||
					y != tileLoadRequest.yTile ||
					zoom != tileLoadRequest.zoom) {
				return;
			}

			synchronized (sync) {
				ready = true;
				sync.notifyAll();
			}
		}
	}

	@Nullable
	private byte[] getTileBytes(int tileX, int tileY, int zoom, long requestTimestamp,
			boolean cacheOnly, IQueryController queryController) {
		byte[] bytes = null;
		try {
			String tileFilename = rm.calculateTileId(tileSource, tileX, tileY, zoom);
			if (!cacheOnly && tileSource.couldBeDownloadedFromInternet()) {
				TileReadyCallback tileReadyCallback = new TileReadyCallback(tileSource, tileX, tileY, zoom);
				rm.getMapTileDownloader().addDownloaderCallback(tileReadyCallback);
				try {
					while (tilesCache.getTileForMapSync(tileFilename, tileSource, tileX, tileY, zoom, true,
							requestTimestamp) == null && System.currentTimeMillis() - requestTimestamp < IMAGE_LOAD_TIMEOUT) {
						synchronized (tileReadyCallback.getSync()) {
							if (tileReadyCallback.isReady()) {
								break;
							}
							try {
								tileReadyCallback.getSync().wait(50);
							} catch (InterruptedException ignored) {
							}
						}
						if (queryController == null || queryController.isAborted()) {
							break;
						}				
					}
				} finally {
					rm.getMapTileDownloader().removeDownloaderCallback(tileReadyCallback);
				}
			} else {
				tilesCache.get(tileFilename, requestTimestamp);
				if (cacheOnly && tileSource.couldBeDownloadedFromInternet()) {
					// Async load tile if it's expired
					tilesCache.getTileForMapAsync(tileFilename, tileSource, tileX, tileY, zoom, true, requestTimestamp);
				}
			}
			bytes = tileSource.getBytes(tileX, tileY, zoom, dirWithTiles);
		} catch (Exception ignore) {

		}
		return bytes;
	}
}
