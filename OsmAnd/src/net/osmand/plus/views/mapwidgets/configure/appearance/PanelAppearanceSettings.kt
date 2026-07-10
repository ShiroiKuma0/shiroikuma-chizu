package net.osmand.plus.views.mapwidgets.configure.appearance

import android.content.Context
import androidx.annotation.ColorInt
import net.osmand.plus.R
import net.osmand.plus.OsmandApplication
import net.osmand.plus.settings.backend.ApplicationMode
import net.osmand.plus.settings.backend.OsmandSettings
import net.osmand.plus.settings.backend.preferences.CommonPreference
import net.osmand.plus.settings.enums.PanelBackgroundMode
import net.osmand.plus.settings.enums.PanelIconMode
import net.osmand.plus.settings.enums.PanelSizeMode
import net.osmand.plus.settings.enums.PanelTextColorMode
import net.osmand.plus.settings.enums.ScreenLayoutMode
import net.osmand.plus.settings.enums.WidgetSize
import net.osmand.plus.utils.ColorUtilities
import net.osmand.plus.views.mapwidgets.WidgetsPanel

class PanelAppearanceSettings(
	private val settings: OsmandSettings,
	val panel: WidgetsPanel
) {

	companion object {
		@JvmStatic
		fun getInstance(app: OsmandApplication, panel: WidgetsPanel): PanelAppearanceSettings {
			return PanelAppearanceSettings(app.settings, panel)
		}

		@JvmStatic
		@ColorInt
		fun getDefaultColor(app: OsmandApplication, panel: WidgetsPanel, target: PanelColorTarget,
		                    nightMode: Boolean): Int {
			return when (target) {
				PanelColorTarget.TEXT -> if (panel.isPanelVertical) {
					ColorUtilities.getPrimaryTextColor(app, nightMode)
				} else {
					ColorUtilities.getColor(app, if (nightMode) R.color.widgettext_night else R.color.widgettext_day)
				}
				PanelColorTarget.SECONDARY_TEXT -> ColorUtilities.getSecondaryTextColor(app, nightMode)
				PanelColorTarget.BACKGROUND -> ColorUtilities.getWidgetBackgroundColor(app, nightMode)
			}
		}

		@JvmStatic
		fun resolveWidgetSize(app: OsmandApplication, panel: WidgetsPanel,
		                      individualSize: WidgetSize, ctx: Context): WidgetSize {
			val layoutMode = ScreenLayoutMode.getDefault(ctx)
			val mode = getInstance(app, panel).getSizeModePref(layoutMode).get()
			return mode.widgetSize ?: individualSize
		}

		@JvmStatic
		fun getCustomBackgroundColor(app: OsmandApplication, panel: WidgetsPanel,
		                             layoutMode: ScreenLayoutMode?, nightMode: Boolean): Int? {
			val appearanceSettings = getInstance(app, panel)
			return if (appearanceSettings.getBackgroundModePref(layoutMode).get() == PanelBackgroundMode.CUSTOM) {
				appearanceSettings.getBackgroundColorPref(layoutMode, nightMode).get()
			} else {
				null
			}
		}

		@JvmStatic
		fun resolveIconVisibility(app: OsmandApplication, panel: WidgetsPanel,
		                          individualShowIcon: Boolean, ctx: Context): Boolean {
			val layoutMode = ScreenLayoutMode.getDefault(ctx)
			return when (getInstance(app, panel).getIconModePref(layoutMode).get()) {
				PanelIconMode.ON -> true
				PanelIconMode.OFF -> false
				else -> individualShowIcon
			}
		}
	}

	private val suffix = "_" + panel.name.lowercase()
	private val app = settings.context

	private val sizeMode: CommonPreference<PanelSizeMode> = settings.registerEnumStringPreference(
		"widget_panel_size_mode$suffix", PanelSizeMode.ORIGINAL,
		PanelSizeMode.entries.toTypedArray(), PanelSizeMode::class.java).makeProfile().cache()

	private val iconMode: CommonPreference<PanelIconMode> = settings.registerEnumStringPreference(
		"widget_panel_icon_mode$suffix", PanelIconMode.ORIGINAL,
		PanelIconMode.entries.toTypedArray(), PanelIconMode::class.java).makeProfile().cache()

	private val textColorMode: CommonPreference<PanelTextColorMode> = settings.registerEnumStringPreference(
		"widget_panel_text_color_mode$suffix", PanelTextColorMode.DEFAULT,
		PanelTextColorMode.entries.toTypedArray(), PanelTextColorMode::class.java).makeProfile().cache()

	private val secondaryTextColorMode: CommonPreference<PanelTextColorMode> = settings.registerEnumStringPreference(
		"widget_panel_secondary_text_color_mode$suffix", PanelTextColorMode.DEFAULT,
		PanelTextColorMode.entries.toTypedArray(), PanelTextColorMode::class.java).makeProfile().cache()

	private val backgroundMode: CommonPreference<PanelBackgroundMode> = settings.registerEnumStringPreference(
		"widget_panel_background_mode$suffix", PanelBackgroundMode.DEFAULT,
		PanelBackgroundMode.entries.toTypedArray(), PanelBackgroundMode::class.java).makeProfile().cache()

	private val textColorDay: CommonPreference<Int> = settings.registerIntPreference(
		"widget_panel_text_color_day$suffix",
		getDefaultColor(app, panel, PanelColorTarget.TEXT, false)).makeProfile().cache()

	private val textColorNight: CommonPreference<Int> = settings.registerIntPreference(
		"widget_panel_text_color_night$suffix",
		getDefaultColor(app, panel, PanelColorTarget.TEXT, true)).makeProfile().cache()

	private val secondaryTextColorDay: CommonPreference<Int> = settings.registerIntPreference(
		"widget_panel_secondary_text_color_day$suffix",
		getDefaultColor(app, panel, PanelColorTarget.SECONDARY_TEXT, false)).makeProfile().cache()

	private val secondaryTextColorNight: CommonPreference<Int> = settings.registerIntPreference(
		"widget_panel_secondary_text_color_night$suffix",
		getDefaultColor(app, panel, PanelColorTarget.SECONDARY_TEXT, true)).makeProfile().cache()

	private val backgroundColorDay: CommonPreference<Int> = settings.registerIntPreference(
		"widget_panel_background_color_day$suffix",
		getDefaultColor(app, panel, PanelColorTarget.BACKGROUND, false)).makeProfile().cache()

	private val backgroundColorNight: CommonPreference<Int> = settings.registerIntPreference(
		"widget_panel_background_color_night$suffix",
		getDefaultColor(app, panel, PanelColorTarget.BACKGROUND, true)).makeProfile().cache()

	fun getSizeModePref(layoutMode: ScreenLayoutMode?): CommonPreference<PanelSizeMode> =
		settings.getLayoutPreference(sizeMode, layoutMode)

	fun getIconModePref(layoutMode: ScreenLayoutMode?): CommonPreference<PanelIconMode> =
		settings.getLayoutPreference(iconMode, layoutMode)

	fun getTextColorModePref(layoutMode: ScreenLayoutMode?): CommonPreference<PanelTextColorMode> =
		settings.getLayoutPreference(textColorMode, layoutMode)

	fun getSecondaryTextColorModePref(layoutMode: ScreenLayoutMode?): CommonPreference<PanelTextColorMode> =
		settings.getLayoutPreference(secondaryTextColorMode, layoutMode)

	fun getBackgroundModePref(layoutMode: ScreenLayoutMode?): CommonPreference<PanelBackgroundMode> =
		settings.getLayoutPreference(backgroundMode, layoutMode)

	fun getTextColorPref(layoutMode: ScreenLayoutMode?, nightMode: Boolean): CommonPreference<Int> =
		settings.getLayoutPreference(if (nightMode) textColorNight else textColorDay, layoutMode)

	fun getSecondaryTextColorPref(layoutMode: ScreenLayoutMode?, nightMode: Boolean): CommonPreference<Int> =
		settings.getLayoutPreference(if (nightMode) secondaryTextColorNight else secondaryTextColorDay, layoutMode)

	fun getBackgroundColorPref(layoutMode: ScreenLayoutMode?, nightMode: Boolean): CommonPreference<Int> =
		settings.getLayoutPreference(if (nightMode) backgroundColorNight else backgroundColorDay, layoutMode)

	fun getColorsHash(layoutMode: ScreenLayoutMode?, nightMode: Boolean): Int {
		var result = getBackgroundModePref(layoutMode).get().hashCode()
		result = 31 * result + getTextColorModePref(layoutMode).get().hashCode()
		result = 31 * result + getSecondaryTextColorModePref(layoutMode).get().hashCode()
		result = 31 * result + getTextColorPref(layoutMode, nightMode).get().hashCode()
		result = 31 * result + getSecondaryTextColorPref(layoutMode, nightMode).get().hashCode()
		result = 31 * result + getBackgroundColorPref(layoutMode, nightMode).get().hashCode()
		return result
	}

	private fun allPrefs(layoutMode: ScreenLayoutMode?): List<CommonPreference<*>> = listOf(
		getSizeModePref(layoutMode), getIconModePref(layoutMode),
		getTextColorModePref(layoutMode), getSecondaryTextColorModePref(layoutMode),
		getBackgroundModePref(layoutMode),
		getTextColorPref(layoutMode, false), getTextColorPref(layoutMode, true),
		getSecondaryTextColorPref(layoutMode, false), getSecondaryTextColorPref(layoutMode, true),
		getBackgroundColorPref(layoutMode, false), getBackgroundColorPref(layoutMode, true))

	fun resetToDefault(appMode: ApplicationMode, layoutMode: ScreenLayoutMode?) {
		allPrefs(layoutMode).forEach { it.resetModeToDefault(appMode) }
	}

	fun copyFromProfile(fromAppMode: ApplicationMode, appMode: ApplicationMode, layoutMode: ScreenLayoutMode?) {
		allPrefs(layoutMode).forEach { copyPrefFromAppMode(it, fromAppMode, appMode) }
	}

	fun copyFromPanel(fromPanel: WidgetsPanel, appMode: ApplicationMode, layoutMode: ScreenLayoutMode?) {
		val from = PanelAppearanceSettings(settings, fromPanel).allPrefs(layoutMode)
		val to = allPrefs(layoutMode)
		for (i in to.indices) {
			copyPrefValue(from[i], to[i], appMode)
		}
	}

	private fun <T> copyPrefFromAppMode(pref: CommonPreference<T>, fromAppMode: ApplicationMode,
	                                    appMode: ApplicationMode) {
		pref.setModeValue(appMode, pref.getModeValue(fromAppMode))
	}

	@Suppress("UNCHECKED_CAST")
	private fun copyPrefValue(from: CommonPreference<*>, to: CommonPreference<*>, appMode: ApplicationMode) {
		(to as CommonPreference<Any>).setModeValue(appMode, (from as CommonPreference<Any>).getModeValue(appMode))
	}
}
