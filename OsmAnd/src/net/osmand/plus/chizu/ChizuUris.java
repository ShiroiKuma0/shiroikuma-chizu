package net.osmand.plus.chizu;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * shiroikuma fork: the hand-over in content URIs — the form the 自由作業盤 contract takes now that
 * neither app keeps a shared folder for the two to meet in.
 *
 * 自由作業盤 moved its workout archive into its own database, so the walk it hands over and the
 * base map it asks for both live in ITS private storage and reach us only through a
 * {@link android.content.ContentResolver} and a grant. Nothing here is ever touched as a
 * {@link java.io.File}: the path behind the URI is not ours to name, and on most of them we could
 * not open it if we tried.
 *
 * <p><b>Why the caller must call {@code grantUriPermission} and not merely set the intent flags.</b>
 * A flag-scoped grant on a broadcast dies with the broadcast, and this contract outlives it by
 * minutes: {@link ChizuTrackReceiver} goes async, and a cold-started 地図 waits up to three minutes
 * for the app to finish initializing before it so much as looks at the request. The read below is
 * therefore done at delivery, inside {@code onReceive}, where the grant is youngest; the write
 * cannot be, because there is nothing to write until the map has been rendered.
 */
public class ChizuUris {

	/**
	 * Generous for a GPX — a 30-minute walk off the band is 150–220 KB — and small enough that
	 * reading one on the main thread, which is where delivery puts us, costs nothing.
	 */
	private static final int MAX_READ_BYTES = 16 * 1024 * 1024;

	private ChizuUris() {
	}

	/** Trimmed, or null when the extra was absent or blank: presence is what selects URI mode. */
	@Nullable
	public static String value(@Nullable String extra) {
		if (extra == null) {
			return null;
		}
		String clean = extra.trim();
		return clean.isEmpty() ? null : clean;
	}

	/** The whole of it in memory. Called at delivery, so the bytes outlive any grant. */
	@NonNull
	public static byte[] read(@NonNull Context context, @NonNull String uri) throws IOException {
		try (InputStream in = context.getContentResolver().openInputStream(Uri.parse(uri))) {
			if (in == null) {
				throw new IOException("nothing behind the uri");
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[64 * 1024];
			int read;
			while ((read = in.read(buffer)) > 0) {
				if (out.size() + read > MAX_READ_BYTES) {
					throw new IOException("larger than " + MAX_READ_BYTES / (1024 * 1024) + " MB");
				}
				out.write(buffer, 0, read);
			}
			return out.toByteArray();
		} catch (SecurityException error) {
			// the one failure worth naming: it is always the grant, and always the caller's end
			throw new IOException("no read grant for this app", error);
		}
	}

	/**
	 * Opened write-and-truncate, so a shorter picture cannot leave a tail of the one before it —
	 * there is no rename into place on a URI, and the file at the far end may already hold bytes.
	 */
	@NonNull
	public static OutputStream write(@NonNull Context context, @NonNull String uri)
			throws IOException {
		try {
			ContentResolver resolver = context.getContentResolver();
			OutputStream out = resolver.openOutputStream(Uri.parse(uri), "wt");
			if (out == null) {
				throw new IOException("nothing behind the uri");
			}
			return out;
		} catch (SecurityException error) {
			throw new IOException("no write grant for this app", error);
		}
	}

	/** What went wrong, in the words the reply carries back to the caller. */
	@NonNull
	public static String reason(@NonNull Throwable error) {
		String message = error.getMessage();
		return message != null && !message.trim().isEmpty() ? message.trim()
				: error.getClass().getSimpleName();
	}
}
