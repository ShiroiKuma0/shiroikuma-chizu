package net.osmand.plus.auto.screens

import androidx.car.app.CarContext
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapWithContentTemplate
import net.osmand.plus.R
import net.osmand.plus.chizu.ChizuCar

class ConfirmScreen(
	carContext: CarContext,
	private val title: String,
	private val description: String,
	private val onConfirm: () -> Unit,
	private val onCancel: () -> Unit = {}
) : BaseAndroidAutoScreen(carContext) {

	override fun getTemplate(): Template {
		val header = Header.Builder()
			.setTitle(title)
			.build()

		// shiroikuma fork: filled yellow buttons — a Pane's body actions accept a background
		// colour on every action, so neither has to stay host grey
		val yesAction = ChizuCar.filledAction(carContext)
			.setTitle(app.getString(R.string.shared_string_yes))
			.setOnClickListener {
				onConfirm()
				screenManager.pop()
			}
			.build()

		val noAction = ChizuCar.filledAction(carContext)
			.setTitle(app.getString(R.string.shared_string_no))
			.setOnClickListener {
				onCancel()
				screenManager.pop()
			}
			.build()

		val paneTemplate = PaneTemplate.Builder(
			Pane.Builder()
				.addRow(
					Row.Builder()
						.setTitle(description)
						.build()
				)
				.addAction(yesAction)
				.addAction(noAction)
				.build()
		)
			.setHeader(header)
			.build()

		return MapWithContentTemplate.Builder()
			.setContentTemplate(paneTemplate)
			.build()
	}
}
