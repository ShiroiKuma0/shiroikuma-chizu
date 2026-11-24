package net.osmand.plus.chizu;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.google.android.material.color.ChizuColorLoaderBridge;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 白い熊 地図 5.6.0+004: lays the colour roles over OsmAnd's colour resources (black-yellow skill, the MIXED
 * way). A layout, a drawable or a theme attribute that names one of OsmAnd's colours gets the role's
 * value at run time, so upstream's XML stays as upstream wrote it and nothing in it pins a colour.
 * <p>
 * Needs Android 11. Below it the values of {@code colors.xml} show, and the colours OsmAnd takes through
 * {@code ColorUtilities} still follow the roles ({@link ChizuTheme#overrideFor(int)}).
 * <p>
 * One loader is built per state of the roles and added to every {@link Resources} object the app draws
 * from: the application's, its localized copy, and each activity's before it inflates anything.
 */
public final class ChizuPalette {

	private static final org.apache.commons.logging.Log LOG = net.osmand.PlatformUtil.getLog(ChizuPalette.class);

	private static final WeakHashMap<Resources, Object> APPLIED = new WeakHashMap<>();
	private static Object loader; // a ResourcesLoader; typed loosely so the class loads below Android 11
	private static int loaderGeneration = -1;

	private ChizuPalette() {
	}

	public static void install(@NonNull Application app) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
			return;
		}
		apply(app);
		app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
			@Override
			public void onActivityPreCreated(@NonNull Activity activity, @Nullable Bundle state) {
				apply(activity);
			}

			@Override
			public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) {
			}

			@Override
			public void onActivityStarted(@NonNull Activity activity) {
			}

			@Override
			public void onActivityResumed(@NonNull Activity activity) {
				apply(activity); // a colour set meanwhile reaches what this screen builds from now on
			}

			@Override
			public void onActivityPaused(@NonNull Activity activity) {
			}

			@Override
			public void onActivityStopped(@NonNull Activity activity) {
			}

			@Override
			public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle out) {
			}

			@Override
			public void onActivityDestroyed(@NonNull Activity activity) {
			}
		});
	}

	/** Brings the resources {@code context} draws from, and the application's, to the present roles. */
	public static synchronized void apply(@NonNull Context context) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
			return;
		}
		try {
			applyTo(context, context.getResources());
			Context app = context.getApplicationContext();
			if (app != null) {
				applyTo(app, app.getResources()); // the localized copy, when the app runs in a chosen language
				if (app instanceof ContextWrapper && ((ContextWrapper) app).getBaseContext() != null) {
					applyTo(app, ((ContextWrapper) app).getBaseContext().getResources());
				}
			}
		} catch (Throwable e) {
			LOG.error("could not lay the colour roles over the resources", e);
		}
	}

	@RequiresApi(Build.VERSION_CODES.R)
	private static void applyTo(@NonNull Context context, @Nullable Resources resources) {
		if (resources == null) {
			return;
		}
		int generation = ChizuTheme.generation();
		if (loader == null || loaderGeneration != generation) {
			Map<Integer, Integer> colors = ChizuTheme.resourceColors();
			if (colors.isEmpty()) {
				return;
			}
			ResourcesLoader made = ChizuColorLoaderBridge.create(context, colors);
			if (made == null) {
				return;
			}
			loader = made;
			loaderGeneration = generation;
		}
		Object current = APPLIED.get(resources);
		if (current == loader) {
			return;
		}
		if (current != null) {
			resources.removeLoaders((ResourcesLoader) current);
		}
		resources.addLoaders((ResourcesLoader) loader);
		APPLIED.put(resources, loader);
	}
}
