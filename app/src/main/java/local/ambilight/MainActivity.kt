package local.ambilight

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

private val Background = Color(0xFF0E0F12)
private val Panel = Color(0xFF17191E)
private val PanelFocused = Color(0xFFE9EAEE)
private val TextPrimary = Color(0xFFE9EAEE)
private val TextMuted = Color(0xFF8B8F98)
private val Warning = Color(0xFFF2A65A)
private val ScreenColour = Color(0xFF1B1D22)
/** An unlit segment, so the strip's shape shows even when the picture is black. */
private val SlotColour = Color(0xFF2A2D34)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, AmbilightService::class.java))
        val config = Config(this)
        setContent {
            Screen(config) {
                startService(Intent(this, AmbilightService::class.java).setAction(AmbilightService.ACTION_TEST))
            }
        }
    }
}

@Composable
private fun Screen(config: Config, onTest: () -> Unit) {
    val status by Status.state.collectAsState()
    var enabled by remember { mutableStateOf(config.enabled) }
    var reverse by remember { mutableStateOf(config.reverse) }
    var sides by remember { mutableIntStateOf(config.sides) }
    var filterSubtitles by remember { mutableStateOf(config.filterSubtitles) }
    var offset by remember { mutableIntStateOf(config.offset) }
    var showPicture by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    if (showPicture) {
        TestPicture { showPicture = false }
        return
    }
    LaunchedEffect(Unit) { first.requestFocus() }

    Row(
        Modifier
            .fillMaxSize()
            .background(Background)
            .padding(horizontal = 64.dp, vertical = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(56.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ambilight", color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            TvPreview(status.segments, sides, reverse, offset)
            Spacer(Modifier.height(12.dp))
            StatusLine("Tryb", status.mode)
            StatusLine("Obraz", status.picture)
            StatusLine("Taśma", status.strip)
            StatusLine("ADB", status.adb)
            if (status.problem.isNotEmpty()) Text(status.problem, color = Warning, fontSize = 15.sp)
        }
        Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Setting("Ambilight", if (enabled) "włączony" else "wyłączony", Modifier.focusRequester(first)) {
                enabled = !enabled
                config.enabled = enabled
            }
            Setting("Kierunek taśmy", if (reverse) "odwrócony" else "zwykły") {
                reverse = !reverse
                config.reverse = reverse
            }
            Setting("Układ", if (sides == 4) "4 boki" else "3 boki") {
                sides = if (sides == 4) 3 else 4
                config.sides = sides
            }
            Setting("Filtr napisów", if (filterSubtitles) "włączony" else "wyłączony") {
                filterSubtitles = !filterSubtitles
                config.filterSubtitles = filterSubtitles
            }
            Setting("Przesunięcie (+1 dioda)", "$offset diod") {
                offset = (offset + 1) % config.ledCount
                config.offset = offset
            }
            Setting("Przesunięcie (-1 dioda)", "cofnij") {
                offset = (offset - 1 + config.ledCount) % config.ledCount
                config.offset = offset
            }
            Setting("Test segmentów", "60 s", onClick = onTest)
            Setting("Obraz testowy", "pokaż") { showPicture = true }
            Text(
                "Test segmentów zapala 1 na czerwono, 2 na żółto (kierunek), 8 na zielono i ostatni, 14, na fioletowo; " +
                    "powinny wypaść przy liczbach na podglądzie. Obraz testowy ma krawędzie: lewa czerwona, " +
                    "góra zielona, prawa niebieska, dół żółty.",
                color = TextMuted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

/**
 * The picture edges the strip follows, in the order the colours are read: up the left, across the top, down the right.
 * Labels 1, 8 and 15 mark where those strip segments sit with the current direction and offset (the test lights them).
 */
@Composable
private fun TvPreview(edges: List<Int>, sides: Int, reverse: Boolean, offset: Int) {
    val measurer = rememberTextMeasurer()
    // Inner screen about 16:9 with four sides (238 x 132 dp).
    Canvas(Modifier.size(width = 300.dp, height = 194.dp)) {
        val margin = 16.dp.toPx()
        val band = 10.dp.toPx()
        val gap = 5.dp.toPx()
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
        for (segment in listOf(0, 7, n - 1)) {
            val q = (segment - offset).mod(n)
            val p = if (reverse) n - 1 - q else q
            val at = labelPoint(path * (p + 0.5f) / n, screen, origin, ring - margin / 2)
            val text = measurer.measure("${segment + 1}", TextStyle(color = TextMuted, fontSize = 11.sp))
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
        val pad = 1.5f
        when (k) {
            0 -> drawRect(colour, Offset(o.x - gap - band, o.y + h - (b - corners[0]) + pad), Size(band, b - a - 2 * pad))
            1 -> drawRect(colour, Offset(o.x + (a - corners[1]) + pad, o.y - gap - band), Size(b - a - 2 * pad, band))
            2 -> drawRect(colour, Offset(o.x + w + gap, o.y + (a - corners[2]) + pad), Size(band, b - a - 2 * pad))
            3 -> drawRect(colour, Offset(o.x + w - (b - corners[3]) + pad, o.y + h + gap), Size(b - a - 2 * pad, band))
        }
    }
}

/** Known colours on each edge, so the whole chain (picture -> strip) can be checked by eye. Back closes it. */
@Composable
private fun TestPicture(onExit: () -> Unit) {
    BackHandler(onBack = onExit)
    Box(Modifier.fillMaxSize().background(Color(0xFF303236))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(0.12f).align(Alignment.CenterStart).background(Color(0xFFFF0000)))
        Box(Modifier.fillMaxHeight().fillMaxWidth(0.12f).align(Alignment.CenterEnd).background(Color(0xFF0000FF)))
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.15f).align(Alignment.TopCenter).background(Color(0xFF00FF00)))
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.15f).align(Alignment.BottomCenter).background(Color(0xFFFFD000)))
        Text("Obraz testowy · Wstecz zamyka", Modifier.align(Alignment.Center), color = TextPrimary, fontSize = 18.sp)
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(label, color = TextMuted, fontSize = 15.sp, modifier = Modifier.width(72.dp))
        Text(value, color = TextPrimary, fontSize = 15.sp)
    }
}

@Composable
private fun Setting(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Panel,
            contentColor = TextPrimary,
            focusedContainerColor = PanelFocused,
            focusedContentColor = Background,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}
