package net.osmand.plus.gallery.model

import androidx.annotation.StringRes
import net.osmand.plus.R

enum class GallerySortMode(@StringRes val titleId: Int) {
	NEAREST(R.string.gallery_sort_nearest),
	LAST_MODIFIED(R.string.gallery_sort_last_modified),
	NAME_A_Z(R.string.gallery_sort_name_a_z),
	NAME_Z_A(R.string.gallery_sort_name_z_a),
	NEWEST_FIRST(R.string.gallery_sort_newest_first),
	OLDEST_FIRST(R.string.gallery_sort_oldest_first),
	DURATION_LONG_SHORT(R.string.gallery_sort_duration_long_short),
	DURATION_SHORT_LONG(R.string.gallery_sort_duration_short_long)
}
