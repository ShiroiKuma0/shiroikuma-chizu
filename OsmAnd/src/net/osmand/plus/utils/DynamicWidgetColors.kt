package net.osmand.plus.utils

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt
import java.util.concurrent.ConcurrentHashMap

object DynamicWidgetColors {

	private const val LUMINANCE_THRESHOLD = 0.5

	@ColorInt private val SECONDARY_WHITE = withOpacity(Color.WHITE, 0.6f)
	@ColorInt private val SECONDARY_BLACK = withOpacity(Color.BLACK, 0.6f)
	@ColorInt private val DIVIDER_WHITE = withOpacity(Color.WHITE, 0.2f)
	@ColorInt private val DIVIDER_BLACK = withOpacity(Color.BLACK, 0.2f)

	private val WHITE_ACCENTS = AccentColors(Color.WHITE, SECONDARY_WHITE, DIVIDER_WHITE)
	private val BLACK_ACCENTS = AccentColors(Color.BLACK, SECONDARY_BLACK, DIVIDER_BLACK)

	private val cache = ConcurrentHashMap<Int, AccentColors>()

	data class AccentColors(
		@ColorInt val primaryText: Int,
		@ColorInt val secondaryText: Int,
		@ColorInt val divider: Int
	)

	@JvmStatic
	fun resolve(@ColorInt backgroundColor: Int): AccentColors {
		return cache.getOrPut(backgroundColor) {
			val useWhiteAccents = ColorUtils.calculateLuminance(backgroundColor) < LUMINANCE_THRESHOLD
			if (useWhiteAccents) WHITE_ACCENTS else BLACK_ACCENTS
		}
	}

	@ColorInt
	private fun withOpacity(@ColorInt color: Int, opacity: Float): Int {
		return ColorUtils.setAlphaComponent(color, (opacity * 255).roundToInt())
	}
}
