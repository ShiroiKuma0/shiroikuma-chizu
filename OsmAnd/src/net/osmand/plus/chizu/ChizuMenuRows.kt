package net.osmand.plus.chizu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 白い熊 地図 +090: a row of the app's own dropdown menus in the black-yellow menu look (see [ChizuMenuPills]).
 * An action row is an outlined pill as wide as the menu, its icon inside at the left and a › at the right
 * when it opens something; a row that toggles is plain, its switch ending where the pills end.
 */
@Composable
fun ChizuMenuRow(
	pill: Boolean,
	color: Color,
	enabled: Boolean,
	opens: Boolean,
	onClick: () -> Unit,
	leading: (@Composable () -> Unit)?,
	trailing: (@Composable () -> Unit)?,
	text: @Composable () -> Unit
) {
	val shape = RoundedCornerShape(50)
	val frame = if (pill) Modifier.border(1.25.dp, color, shape).clip(shape) else Modifier
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 8.dp, vertical = if (pill) 3.dp else 0.dp)
			.heightIn(min = if (pill) 42.dp else 36.dp)
			.then(frame)
			.clickable(enabled = enabled, onClick = onClick)
			.padding(start = 14.dp, end = if (pill) 14.dp else 0.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		if (leading != null) {
			leading()
			Spacer(modifier = Modifier.width(12.dp))
		}
		Box(modifier = Modifier.weight(1f)) {
			text()
		}
		if (trailing != null) {
			Spacer(modifier = Modifier.width(8.dp))
			trailing()
		}
		if (opens) {
			Spacer(modifier = Modifier.width(6.dp))
			Text(text = "›", color = color, fontSize = 20.sp)
		}
	}
}

/** The outlined switch at its menu size: a round outline with a solid dot, never filled. */
@Composable
fun ChizuMenuSwitch(checked: Boolean, on: Color, off: Color) {
	Canvas(modifier = Modifier.size(width = 44.dp, height = 24.dp)) {
		val color = if (checked) on else off
		val stroke = 2.dp.toPx()
		drawRoundRect(
			color = color,
			topLeft = Offset(stroke / 2, stroke / 2),
			size = Size(size.width - stroke, size.height - stroke),
			cornerRadius = CornerRadius(size.height / 2, size.height / 2),
			style = Stroke(width = stroke)
		)
		val cx = if (checked) size.width - size.height / 2 else size.height / 2
		drawCircle(color = color, radius = size.height * 0.23f, center = Offset(cx, size.height / 2))
	}
}
