package raul.upt.uwbfinder

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

private val RadarBackground = Color(red = 0.067f, green = 0.086f, blue = 0.102f)
private val RadarBorder = Color(red = 0.15f, green = 0.15f, blue = 0.15f)
private val YouArrowColor = Color(red = 0.29f, green = 0.62f, blue = 1.0f)
private val CenterDotColor = Color(red = 0.9f, green = 0.9f, blue = 0.9f)
private val LabelGray = Color(red = 0.557f, green = 0.557f, blue = 0.576f)   // iOS system gray

/** Radar disc with range rings, the two arrows and the compass labels. Port of RadarView.swift. */
@Composable
fun RadarView(state: RadarUiState, modifier: Modifier = Modifier) {
    val labelPaint = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }

    Canvas(modifier = modifier.fillMaxWidth().aspectRatio(1f)) {
        val side = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = side * 0.42f

        // Dark circular background with a thin border
        drawCircle(color = RadarBackground, radius = side / 2f, center = center)
        drawCircle(
            color = RadarBorder,
            radius = side / 2f - 0.5.dp.toPx(),
            center = center,
            style = Stroke(width = 1.dp.toPx()),
        )

        // Disc fill
        val discColor = Color(
            red = (state.colorR / 255.0).toFloat().coerceIn(0f, 1f),
            green = (state.colorG / 255.0).toFloat().coerceIn(0f, 1f),
            blue = (state.colorB / 255.0).toFloat().coerceIn(0f, 1f),
        )
        drawCircle(color = discColor, radius = radius, center = center)

        // Range rings
        for (frac in listOf(1f / 3f, 2f / 3f, 1f)) {
            drawCircle(
                color = Color.White.copy(alpha = 0.10f),
                radius = radius * frac,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )
        }

        // Target arrow (anchor direction) - white, fades in with confidence
        if (state.targetVisible) {
            val alpha = (0.35 + 0.65 * (state.confidencePct / 100.0)).toFloat()
            drawArrow(
                center = center,
                radius = radius,
                angleDeg = state.displayedTargetAngle,
                color = Color.White.copy(alpha = alpha),
                lengthFrac = 0.85f,
                headSize = 9.dp.toPx(),
            )
        }

        // "You" facing arrow - blue
        drawArrow(
            center = center,
            radius = radius,
            angleDeg = state.displayedYouAngle,
            color = YouArrowColor,
            lengthFrac = 0.55f,
            headSize = 7.dp.toPx(),
        )

        // Center dot
        drawCircle(color = CenterDotColor, radius = 5.dp.toPx(), center = center)

        // Compass labels
        val mainSize = 11.sp.toPx()
        val minorSize = 10.sp.toPx()
        val minorGray = LabelGray.copy(alpha = 0.7f)
        drawLabel(labelPaint, center, "N", 0.0, radius + 14.dp.toPx(), mainSize, LabelGray)
        drawLabel(labelPaint, center, "S", 180.0, radius + 18.dp.toPx(), mainSize, LabelGray)
        drawLabel(labelPaint, center, "E", 90.0, radius + 14.dp.toPx(), mainSize, LabelGray)
        drawLabel(labelPaint, center, "W", 270.0, radius + 14.dp.toPx(), mainSize, LabelGray)
        drawLabel(labelPaint, center, "NE", 45.0, radius - 18.dp.toPx(), minorSize, minorGray)
        drawLabel(labelPaint, center, "SE", 135.0, radius - 18.dp.toPx(), minorSize, minorGray)
        drawLabel(labelPaint, center, "SW", 225.0, radius - 18.dp.toPx(), minorSize, minorGray)
        drawLabel(labelPaint, center, "NW", 315.0, radius - 18.dp.toPx(), minorSize, minorGray)
    }
}

private fun DrawScope.drawArrow(
    center: Offset,
    radius: Float,
    angleDeg: Double,
    color: Color,
    lengthFrac: Float,
    headSize: Float,
) {
    // 0 degrees points up (north); angles grow clockwise, as on a compass.
    val rad = (angleDeg - 90.0) * Math.PI / 180.0
    val hx = cos(rad).toFloat()
    val hy = sin(rad).toFloat()
    val len = radius * lengthFrac
    val ax = center.x + hx * len
    val ay = center.y + hy * len

    drawLine(color = color, start = center, end = Offset(ax, ay), strokeWidth = 3.dp.toPx())

    val px = -hy   // perpendicular
    val py = hx
    val head = Path().apply {
        moveTo(ax + hx * headSize, ay + hy * headSize)
        lineTo(
            ax - hx * headSize * 0.6f + px * headSize * 0.6f,
            ay - hy * headSize * 0.6f + py * headSize * 0.6f,
        )
        lineTo(
            ax - hx * headSize * 0.6f - px * headSize * 0.6f,
            ay - hy * headSize * 0.6f - py * headSize * 0.6f,
        )
        close()
    }
    drawPath(path = head, color = color)
}

private fun DrawScope.drawLabel(
    paint: Paint,
    center: Offset,
    text: String,
    angleDeg: Double,
    dist: Float,
    textSizePx: Float,
    color: Color,
) {
    val rad = (angleDeg - 90.0) * Math.PI / 180.0
    val x = center.x + cos(rad).toFloat() * dist
    val y = center.y + sin(rad).toFloat() * dist
    paint.textSize = textSizePx
    paint.color = color.toArgb()
    // Paint draws from the baseline; shift so the text is centred on (x, y).
    val baseline = y - (paint.ascent() + paint.descent()) / 2f
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawText(text, x, baseline, paint)
    }
}
