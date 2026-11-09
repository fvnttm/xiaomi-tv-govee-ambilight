package local.ambilight

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The picture zones the strip follows, in the order the colours are read: up the left, across the top, down the
 * right (and along the bottom with 4 sides). Labels 1, 8 and 14 mark the zones the segment test lights; the test
 * goes through the strip's direction and offset, so with the right settings its colours appear at these labels.
 */
@Composable
fun TvPreview(edges: List<Int>, sides: Int, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val margin = 18.dp.toPx()
        val band = 12.dp.toPx()
        val gap = 6.dp.toPx()
        val ring = margin + band + gap
        val origin = Offset(ring, ring)
        val screen = Size(size.width - 2 * ring, size.height - ring - if (sides == 4) ring else gap)
        drawRect(ScreenColour, origin, screen)
        val n = edges.size
        val path = if (sides == 4) 2 * screen.height + 2 * screen.width else 2 * screen.height + screen.width
        for (i in 0 until n) {
            val from = path * i / n
            val to = path * (i + 1) / n
            drawEdgeSpan(from, to, screen, origin, band, gap, SlotColour)
            val c = edges[i]
            val lit = maxOf(c shr 16 and 0xff, c shr 8 and 0xff, c and 0xff) > 0x38
            if (lit) drawEdgeSpan(from, to, screen, origin, band, gap, Color(0xFF000000.toInt() or c))
        }
        for (zone in listOf(0, 7, n - 1)) {
            val at = labelPoint(path * (zone + 0.5f) / n, screen, origin, ring - margin / 2)
            val text = measurer.measure("${zone + 1}", TextStyle(color = TextMuted, fontSize = 13.sp))
            drawText(text, topLeft = at - Offset(text.size.width / 2f, text.size.height / 2f))
        }
    }
}

/** A point [distance] outside the screen edge, [t] along the edge path. */
private fun labelPoint(t: Float, screen: Size, o: Offset, distance: Float): Offset {
    val h = screen.height
    val w = screen.width
    return when {
        t < h -> Offset(o.x - distance, o.y + h - t)
        t < h + w -> Offset(o.x + (t - h), o.y - distance)
        t < 2 * h + w -> Offset(o.x + w + distance, o.y + (t - h - w))
        else -> Offset(o.x + w - (t - 2 * h - w), o.y + h + distance)
    }
}

private fun DrawScope.drawEdgeSpan(from: Float, to: Float, screen: Size, o: Offset, band: Float, gap: Float, colour: Color) {
    val h = screen.height
    val w = screen.width
    // Split the span where it turns a corner, then draw each straight piece.
    val corners = listOf(0f, h, h + w, 2 * h + w, 2 * h + 2 * w)
    for (k in 0 until 4) {
        val a = maxOf(from, corners[k])
        val b = minOf(to, corners[k + 1])
        if (a >= b) continue
        val pad = 2f
        when (k) {
            0 -> drawRect(colour, Offset(o.x - gap - band, o.y + h - (b - corners[0]) + pad), Size(band, b - a - 2 * pad))
            1 -> drawRect(colour, Offset(o.x + (a - corners[1]) + pad, o.y - gap - band), Size(b - a - 2 * pad, band))
            2 -> drawRect(colour, Offset(o.x + w + gap, o.y + (a - corners[2]) + pad), Size(band, b - a - 2 * pad))
            3 -> drawRect(colour, Offset(o.x + w - (b - corners[3]) + pad, o.y + h + gap), Size(b - a - 2 * pad, band))
        }
    }
}

