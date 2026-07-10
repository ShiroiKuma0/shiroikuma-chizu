package net.osmand.plus.download.ui

import android.graphics.Typeface
import androidx.fragment.app.FragmentActivity
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.base.dialog.BaseDialogController
import net.osmand.plus.base.dialog.data.DialogExtra
import net.osmand.plus.base.dialog.data.DisplayData
import net.osmand.plus.base.dialog.data.DisplayDialogButtonItem
import net.osmand.plus.base.dialog.interfaces.controller.IDisplayDataProvider
import net.osmand.plus.settings.bottomsheets.CustomizableQuestionBottomSheet
import net.osmand.plus.utils.UiUtilities
import net.osmand.plus.widgets.dialogbutton.DialogButtonType

class DuplicateMapDownloadDialogController(
    app: OsmandApplication,
    private val mapName: String,
    private val direction: ConflictDirection,
    private val onReplace: Runnable,
    private val onKeepBoth: Runnable
) : BaseDialogController(app), IDisplayDataProvider {

    enum class ConflictDirection {
        ROAD_TO_STANDARD,
        STANDARD_TO_ROAD
    }

    override fun getProcessId(): String = PROCESS_ID

    override fun getDisplayData(processId: String): DisplayData {
        val displayData = DisplayData()
        displayData.putExtra(DialogExtra.TITLE, getString(R.string.duplicate_map))

        val descRes = when (direction) {
            ConflictDirection.ROAD_TO_STANDARD -> R.string.duplicate_map_road_only_exists_desc
            ConflictDirection.STANDARD_TO_ROAD -> R.string.duplicate_map_standard_exists_desc
        }
        val fullDescription = getString(descRes, mapName)
        val spannableDescription = UiUtilities.createSpannableString(fullDescription, Typeface.BOLD, mapName)
        displayData.putExtra(DialogExtra.DESCRIPTION, spannableDescription)

        val replaceBtnTitleRes = when (direction) {
            ConflictDirection.ROAD_TO_STANDARD -> R.string.duplicate_map_replace_with_standard
            ConflictDirection.STANDARD_TO_ROAD -> R.string.duplicate_map_replace_with_road
        }
        val replaceBtnType = when (direction) {
            ConflictDirection.ROAD_TO_STANDARD -> DialogButtonType.PRIMARY
            ConflictDirection.STANDARD_TO_ROAD -> DialogButtonType.SECONDARY
        }

        val buttons = arrayOf(
            DisplayDialogButtonItem()
                .setTitleId(replaceBtnTitleRes)
                .setButtonType(replaceBtnType)
                .setOnClickListener {
                    onReplace.run()
                    app.dialogManager.askDismissDialog(PROCESS_ID)
                },
            DisplayDialogButtonItem()
                .setTitleId(R.string.keep_both)
                .setButtonType(DialogButtonType.SECONDARY)
                .setOnClickListener {
                    onKeepBoth.run()
                    app.dialogManager.askDismissDialog(PROCESS_ID)
                },
            DisplayDialogButtonItem()
                .setTitleId(R.string.shared_string_cancel)
                .setButtonType(DialogButtonType.SECONDARY)
                .setOnClickListener {
                    app.dialogManager.askDismissDialog(PROCESS_ID)
                }
        )

        displayData.putExtra(DialogExtra.DIALOG_BUTTONS, buttons)
        return displayData
    }

    companion object {
        const val PROCESS_ID = "duplicate_map_download"

        @JvmStatic
        fun showDialog(
            activity: FragmentActivity,
            mapName: String,
            direction: ConflictDirection,
            onReplace: Runnable,
            onKeepBoth: Runnable
        ) {
            val app = activity.application as OsmandApplication
            val controller = DuplicateMapDownloadDialogController(app, mapName, direction, onReplace, onKeepBoth)
            app.dialogManager.register(PROCESS_ID, controller)
            CustomizableQuestionBottomSheet.showInstance(activity.supportFragmentManager, PROCESS_ID, true)
        }
    }
}
