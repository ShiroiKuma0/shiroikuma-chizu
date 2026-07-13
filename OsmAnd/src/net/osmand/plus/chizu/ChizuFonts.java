package net.osmand.plus.chizu;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * shiroikuma fork: external font support for the 白い熊 地図 UI.
 *
 * Imported fonts (.ttf/.otf, via SAF) live in filesDir/fonts. The selected global family
 * (+ weight) is served to {@link net.osmand.plus.utils.FontCache}, which is the app-wide
 * typeface funnel (TextViewEx, spans, widgets).
 */
public class ChizuFonts {

	public static final String MONOSPACE = "@monospace";
	public static final String SERIF = "@serif";

	private static final String FONT_FAMILY_KEY = "chizu_font_family";
	private static final String FONT_WEIGHT_KEY = "chizu_font_weight";
	private static final List<String> FONT_EXTENSIONS = Arrays.asList("ttf", "otf");

	private static final Map<String, Typeface> TYPEFACE_CACHE = new HashMap<>();
	private static Context appContext;
	private static volatile Typeface globalTypeface;

	public static class FontOption {
		public final String displayName;
		public final String fileName;

		public FontOption(String displayName, String fileName) {
			this.displayName = displayName;
			this.fileName = fileName;
		}
	}

	private ChizuFonts() {
	}

	public static void init(@NonNull Context context) {
		appContext = context.getApplicationContext();
		reloadGlobalTypeface();
	}

	@NonNull
	private static SharedPreferences prefs() {
		return appContext.getSharedPreferences(ChizuTheme.PREFS_NAME, Context.MODE_PRIVATE);
	}

	@NonNull
	public static File getFontsDir(@NonNull Context context) {
		File dir = new File(context.getFilesDir(), "fonts");
		if (!dir.exists()) {
			//noinspection ResultOfMethodCallIgnored
			dir.mkdirs();
		}
		return dir;
	}

	@NonNull
	public static String getGlobalFamily() {
		return appContext == null ? "" : prefs().getString(FONT_FAMILY_KEY, "");
	}

	public static int getGlobalWeight() {
		return appContext == null ? 0 : prefs().getInt(FONT_WEIGHT_KEY, 0);
	}

	public static void setGlobalFamily(@NonNull String fileName) {
		prefs().edit().putString(FONT_FAMILY_KEY, fileName).apply();
		reloadGlobalTypeface();
	}

	public static void setGlobalWeight(int weight) {
		prefs().edit().putInt(FONT_WEIGHT_KEY, weight).apply();
		reloadGlobalTypeface();
	}

	private static void reloadGlobalTypeface() {
		String family = getGlobalFamily();
		if (family.isEmpty() && getGlobalWeight() <= 0) {
			globalTypeface = null;
		} else {
			Typeface base = typefaceFor(appContext, family);
			int weight = getGlobalWeight();
			if (weight > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
				globalTypeface = Typeface.create(base, weight, false);
			} else if (weight >= 600) {
				globalTypeface = Typeface.create(base, Typeface.BOLD);
			} else {
				globalTypeface = base;
			}
		}
	}

	/** The app-wide typeface, or null when the system default applies. Hot path (FontCache). */
	@Nullable
	public static Typeface getGlobalTypeface() {
		return globalTypeface;
	}

	/** True when {@code typeface} is one of the stock system faces our global font may replace. */
	public static boolean isSystemDefault(@Nullable Typeface typeface) {
		return typeface == null || typeface == Typeface.DEFAULT || typeface == Typeface.DEFAULT_BOLD
				|| typeface == Typeface.SANS_SERIF;
	}

	@NonNull
	public static Typeface typefaceFor(@NonNull Context context, @NonNull String fileName) {
		if (fileName.isEmpty()) {
			return Typeface.DEFAULT;
		}
		if (MONOSPACE.equals(fileName)) {
			return Typeface.MONOSPACE;
		}
		if (SERIF.equals(fileName)) {
			return Typeface.SERIF;
		}
		synchronized (TYPEFACE_CACHE) {
			Typeface cached = TYPEFACE_CACHE.get(fileName);
			if (cached != null) {
				return cached;
			}
			Typeface created;
			try {
				created = Typeface.createFromFile(new File(getFontsDir(context), fileName));
			} catch (RuntimeException e) {
				created = Typeface.DEFAULT;
			}
			TYPEFACE_CACHE.put(fileName, created);
			return created;
		}
	}

	@NonNull
	public static List<FontOption> getAvailableFonts(@NonNull Context context,
			@NonNull String systemDefaultLabel, @NonNull String monospaceLabel, @NonNull String serifLabel) {
		List<FontOption> options = new ArrayList<>();
		options.add(new FontOption(systemDefaultLabel, ""));
		options.add(new FontOption(monospaceLabel, MONOSPACE));
		options.add(new FontOption(serifLabel, SERIF));
		File[] files = getFontsDir(context).listFiles();
		if (files != null) {
			List<File> fonts = new ArrayList<>();
			for (File file : files) {
				String ext = extensionOf(file.getName());
				if (file.isFile() && FONT_EXTENSIONS.contains(ext)) {
					fonts.add(file);
				}
			}
			Collections.sort(fonts, (a, b) -> a.getName().toLowerCase(Locale.ROOT)
					.compareTo(b.getName().toLowerCase(Locale.ROOT)));
			for (File font : fonts) {
				String name = font.getName();
				int dot = name.lastIndexOf('.');
				options.add(new FontOption(dot > 0 ? name.substring(0, dot) : name, name));
			}
		}
		return options;
	}

	/** Copies a SAF-picked .ttf/.otf into the fonts dir; returns its filename or null on failure. */
	@Nullable
	public static String importFont(@NonNull Context context, @NonNull Uri uri) {
		String name = fileNameOf(context, uri);
		if (name == null || !FONT_EXTENSIONS.contains(extensionOf(name))) {
			return null;
		}
		File target = new File(getFontsDir(context), name);
		try (InputStream in = context.getContentResolver().openInputStream(uri);
				OutputStream out = new FileOutputStream(target)) {
			if (in == null) {
				return null;
			}
			byte[] buffer = new byte[8192];
			int read;
			while ((read = in.read(buffer)) != -1) {
				out.write(buffer, 0, read);
			}
		} catch (Exception e) {
			//noinspection ResultOfMethodCallIgnored
			target.delete();
			return null;
		}
		synchronized (TYPEFACE_CACHE) {
			TYPEFACE_CACHE.remove(name);
		}
		return name;
	}

	@NonNull
	private static String extensionOf(@NonNull String name) {
		int dot = name.lastIndexOf('.');
		return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
	}

	@Nullable
	private static String fileNameOf(@NonNull Context context, @NonNull Uri uri) {
		try (android.database.Cursor cursor = context.getContentResolver()
				.query(uri, null, null, null, null)) {
			if (cursor != null && cursor.moveToFirst()) {
				int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
				if (index >= 0) {
					return cursor.getString(index);
				}
			}
		} catch (Exception ignored) {
		}
		String path = uri.getLastPathSegment();
		return path != null && path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
	}
}
