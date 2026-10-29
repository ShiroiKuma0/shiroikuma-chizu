package net.osmand.map;

import net.osmand.osm.io.NetworkUtils;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Locale;

/**
 * 白い熊 地図: Google tile requests carry nothing that identifies this phone.
 * <p>
 * The tile downloader sends the app's full version string as User-Agent, which for this fork is
 * unique worldwide and links every Google tile request across IP changes. For Google tile hosts
 * the request is upgraded to https, sent with a common mobile-browser User-Agent and no Referer.
 * Other sources keep the identifying User-Agent their usage policies ask for (OSM requires one).
 */
public final class ChizuTileIdentity {

	public static final String GENERIC_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; K) "
			+ "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";

	private ChizuTileIdentity() {
	}

	public static boolean isGoogleTileUrl(String url) {
		if (url == null) {
			return false;
		}
		String host = host(url);
		return host.endsWith(".google.com") || host.endsWith(".googleapis.com")
				|| host.equals("google.com");
	}

	public static String upgradeUrl(String url) {
		if (isGoogleTileUrl(url) && url.regionMatches(true, 0, "http://", 0, 7)) {
			return "https://" + url.substring(7);
		}
		return url;
	}

	/**
	 * Opens a connection for a tile URL with the identity appropriate for its host.
	 */
	public static HttpURLConnection open(String url, String userAgent, String referer) throws IOException {
		boolean google = isGoogleTileUrl(url);
		HttpURLConnection connection = NetworkUtils.getHttpURLConnection(google ? upgradeUrl(url) : url);
		connection.setUseCaches(false);
		connection.setRequestProperty("User-Agent", google ? GENERIC_USER_AGENT : userAgent);
		if (!google && referer != null) {
			connection.setRequestProperty("Referer", referer);
		}
		return connection;
	}

	private static String host(String url) {
		int start = url.indexOf("://");
		start = start < 0 ? 0 : start + 3;
		int end = start;
		while (end < url.length() && "/:?#".indexOf(url.charAt(end)) < 0) {
			end++;
		}
		return url.substring(start, end).toLowerCase(Locale.ROOT);
	}
}
