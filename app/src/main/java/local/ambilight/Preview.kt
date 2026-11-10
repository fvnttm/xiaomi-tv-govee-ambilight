package local.ambilight

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The demo video with the strip's real colours glowing round it. While it is on screen, the strip reads only this
 * video ([Demo]), so the glow is exactly what the strip does: the colours come from [segments], in the order the
 * zones are read (up the left, across the top, down the right, along the bottom with 4 sides).
 * With [labels], zones 1, 8 and 14 are numbered for the segment test.
 */
@Composable
fun LivePreview(segments: List<Int>, sides: Int, modifier: Modifier = Modifier, labels: Boolean = false, ring: Dp = 22.dp) {
    val measurer = rememberTextMeasurer()
    Box(modifier) {
        Canvas(Modifier.fillMaxSize().blur(12.dp, BlurredEdgeTreatment.Unbounded)) {
            val r = ring.toPx()
            val screen = Size(size.width - 2 * r, size.height - 2 * r)
            val origin = Offset(r, r)
            val n = segments.size
            val path = pathLength(screen, sides)
            for (i in 0 until n) {
                val colour = Color(0xFF000000.toInt() or segments[i])
                drawEdgeSpan(path * i / n, path * (i + 1) / n, screen, origin, r * 0.8f, 0f, colour)
            }
        }
        DemoVideo(Modifier.fillMaxSize().padding(ring))
        if (labels) {
            Canvas(Modifier.fillMaxSize()) {
                val r = ring.toPx()
                val screen = Size(size.width - 2 * r, size.height - 2 * r)
                val n = segments.size
                val path = pathLength(screen, sides)
                for (zone in listOf(0, 7, n - 1)) {
                    if (sides == 3 && zone == n - 1) continue
                    val at = labelPoint(path * (zone + 0.5f) / n, screen, Offset(r, r), -r * 1.2f)
                    val text = measurer.measure("${zone + 1}", TextStyle(color = TextPrimary, fontSize = 13.sp))
                    drawCircle(Background.copy(alpha = 0.75f), radius = 11.dp.toPx(), center = at)
                    drawText(text, topLeft = at - Offset(text.size.width / 2f, text.size.height / 2f))
                }
            }
        }
    }
}

/**
 * The looping, silent demo clip (res/raw/demo.mp4, made by tools/make_demo_video.py). Reports where it is on the panel,
 * so the daemon reads only it; when it leaves the screen, the whole picture is read again.
 */
@Composable
private fun DemoVideo(modifier: Modifier) {
    val view = LocalView.current
    DisposableEffect(Unit) { onDispose { Demo.set(null) } }
    AndroidView(
        factory = { context ->
            // A TextureView, not a VideoView: a VideoView grabs the remote's focus and the audio focus.
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    var player: MediaPlayer? = null
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        player = MediaPlayer.create(context, R.raw.demo)?.apply {
                            setSurface(Surface(texture))
                            isLooping = true
                            setVolume(0f, 0f)
                            start()
                        }
                    }
                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        player?.release()
                        player = null
                        return true
                    }
                }
            }
        },
        modifier = modifier.background(ScreenColour).onGloballyPositioned { coords ->
            val root = view.rootView
            val mode = view.display?.mode ?: return@onGloballyPositioned
            if (root.width == 0 || root.height == 0) return@onGloballyPositioned
            // Window pixels -> panel pixels (the UI may run at 1080p on a 4K panel).
            val sx = mode.physicalWidth.toFloat() / root.width
            val sy = mode.physicalHeight.toFloat() / root.height
            val b = coords.boundsInWindow()
            Demo.set(intArrayOf(
                Math.round(b.left * sx), Math.round(b.top * sy), Math.round(b.width * sx), Math.round(b.height * sy),
            ))
        },
    )
}

private fun pathLength(screen: Size, sides: Int) =
    if (sides == 4) 2 * screen.height + 2 * screen.width else 2 * screen.height + screen.width

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
        when (k) {
            0 -> drawRect(colour, Offset(o.x - gap - band, o.y + h - (b - corners[0])), Size(band, b - a))
            1 -> drawRect(colour, Offset(o.x + (a - corners[1]), o.y - gap - band), Size(b - a, band))
            2 -> drawRect(colour, Offset(o.x + w + gap, o.y + (a - corners[2])), Size(band, b - a))
            3 -> drawRect(colour, Offset(o.x + w - (b - corners[3]), o.y + h + gap), Size(b - a, band))
        }
    }
}
