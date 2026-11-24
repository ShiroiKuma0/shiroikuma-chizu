package com.google.android.material.color;

import android.content.Context;
import android.content.res.loader.ResourcesLoader;
import android.os.Build;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.Map;

/**
 * 白い熊 地図: the one class the fork keeps in Material's package. Material's colour-table creator (it
 * builds an in-memory resource table that overrides colour resources by id) is package-private; this
 * hands it to {@code net.osmand.plus.chizu.ChizuPalette}.
 */
@Keep
public final class ChizuColorLoaderBridge {

	private ChizuColorLoaderBridge() {
	}

	/** A loader that gives every colour resource id in the map its new ARGB value, or null. */
	@Nullable
	@RequiresApi(Build.VERSION_CODES.R)
	public static ResourcesLoader create(Context context, Map<Integer, Integer> colors) {
		return ColorResourcesLoaderCreator.create(context, colors);
	}
}
