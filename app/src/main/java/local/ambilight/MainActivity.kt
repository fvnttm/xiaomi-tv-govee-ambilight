package local.ambilight

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlin.math.exp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, AmbilightService::class.java))
        val config = Config(this)
        // For tests over ADB: am start -n local.ambilight/.MainActivity --es open picture|latency|wled
        val open = intent.getStringExtra("open")
        fun send(action: String) = startService(Intent(this, AmbilightService::class.java).setAction(action))
        setContent {
            App(config, open, onSegmentTest = { send(AmbilightService.ACTION_TEST) }) { send(AmbilightService.ACTION_WHITE) }
        }
    }

    // The strip reads only the demo video while the app is in front; the TV's picture again once it isn't.
    override fun onStart() {
        super.onStart()
        Demo.setAppVisible(true)
    }

    override fun onStop() {
        Demo.setAppVisible(false)
        super.onStop()
    }
}

/** A focus move this soon after a change of section is Compose's, not the user's (see Shell). */
private const val FOCUS_SETTLE_MS = 100L

private enum class Page(val title: String) { START("Start"), PICTURE("Obraz"), CALIBRATION("Kalibracja"), STRIP("Taśma") }

/** The sections, or one of the full-screen pages opened from them. Back on a page returns to its section. */
@Composable
private fun App(config: Config, open: String?, onSegmentTest: () -> Unit, onWhiteTest: () -> Unit) {
    var settings by remember { mutableStateOf(config.read()) }
    var sub by remember { mutableStateOf(open) }
    var page by remember { mutableStateOf(if (open == null) Page.START else Page.STRIP) }
    fun change(new: Settings) {
        settings = new
        config.write(new)
    }
    when (sub) {
        "picture" -> TestPicture { sub = null }
        "latency" -> LatencyTest { sub = null }
        "wled" -> WledScreen(settings, onPick = { device ->
            change(settings.copy(
                espIp = device.ip,
                espName = device.name,
                ledCount = if (device.leds in 1..Config.MAX_LEDS) device.leds else settings.ledCount,
            ))
        }) { sub = null }
        else -> Shell(page, { page = it }) {
            val status by Status.state.collectAsState()
            when (page) {
                Page.START -> StartPage(settings, status, ::change)
                Page.PICTURE -> PicturePage(settings, status, ::change)
                Page.CALIBRATION -> CalibrationPage(settings, status, ::change, onWhiteTest)
                Page.STRIP -> StripPage(settings, status, ::change, onSegmentTest) { sub = it }
            }
        }
    }
}

@Composable
private fun Shell(page: Page, onPage: (Page) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val status by Status.state.collectAsState()
    // One requester per item, always attached: changing an item's modifiers on selection would drop the focus.
    val requesters = remember { Page.entries.associateWith { FocusRequester() } }
    LaunchedEffect(Unit) { requesters.getValue(page).requestFocus() }
    // Left and right change values in the lists, so Back is the way from a section to the rail.
    var inRail by remember { mutableStateOf(true) }
    BackHandler(enabled = !inRail) { requesters.getValue(page).requestFocus() }
    var switchedAt by remember { mutableLongStateOf(0L) }
    fun select(p: Page) {
        val now = SystemClock.uptimeMillis()
        // The first time a section is left for another, Compose moves the focus back to the first rail item a few
        // milliseconds after the switch. No remote presses that fast: put the focus back instead of following it.
        if (now - switchedAt < FOCUS_SETTLE_MS) {
            requesters.getValue(page).requestFocus()
            return
        }
        switchedAt = now
        onPage(p)
    }

    Row(Modifier.fillMaxSize().background(Background)) {
        Column(
            Modifier.width(168.dp).fillMaxHeight().onFocusChanged { inRail = it.hasFocus }.background(Rail).padding(start = 20.dp, end = 14.dp, top = 32.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.padding(start = 8.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Logo()
                Spacer(Modifier.width(8.dp))
                Text("Ambilight", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            for (p in Page.entries) {
                val all = Page.entries
                RailItem(
                    p, p == page,
                    Modifier.focusRequester(requesters.getValue(p)).focusProperties {
                        up = all.getOrNull(p.ordinal - 1)?.let(requesters::getValue) ?: FocusRequester.Cancel
                        down = all.getOrNull(p.ordinal + 1)?.let(requesters::getValue) ?: FocusRequester.Cancel
                    },
                ) { select(p) }
            }
            Spacer(Modifier.weight(1f))
            val running = status.problem.isEmpty() && status.mode == "ambilight"
            Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(if (running) Good else Warning, RoundedCornerShape(3.dp)))
                Spacer(Modifier.width(6.dp))
                Text(status.mode, color = TextPrimary, fontSize = 11.sp, maxLines = 2)
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 36.dp, end = 40.dp, top = 32.dp, bottom = 24.dp)) {
            Text(page.title, color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

/** A section in the rail: focusing it shows the section (D-pad right then moves into it). */
@Composable
private fun RailItem(page: Page, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    Surface(
        onClick = onSelect,
        modifier = modifier.fillMaxWidth().height(32.dp).onFocusChanged { if (it.isFocused && !selected) onSelect() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) PanelRaised else Color.Transparent,
            contentColor = if (selected) TextPrimary else TextSecondary,
            focusedContainerColor = PanelFocused,
            focusedContentColor = Background,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            RailIcon(page, LocalContentColor.current)
            Spacer(Modifier.width(10.dp))
            Text(page.title, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
        }
    }
}

@Composable
private fun RailIcon(page: Page, colour: Color) {
    Canvas(Modifier.size(14.dp)) {
        val s = size.width
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
        when (page) {
            Page.START -> drawPath(Path().apply {
                moveTo(s * 0.12f, s * 0.48f); lineTo(s * 0.5f, s * 0.15f); lineTo(s * 0.88f, s * 0.48f)
                moveTo(s * 0.22f, s * 0.42f); lineTo(s * 0.22f, s * 0.86f); lineTo(s * 0.78f, s * 0.86f); lineTo(s * 0.78f, s * 0.42f)
            }, colour, style = stroke)
            Page.PICTURE -> {
                drawRoundRect(colour, Offset(s * 0.1f, s * 0.18f), androidx.compose.ui.geometry.Size(s * 0.8f, s * 0.52f), CornerRadius(2f), style = stroke)
                drawLine(colour, Offset(s * 0.32f, s * 0.88f), Offset(s * 0.68f, s * 0.88f), stroke.width, StrokeCap.Round)
            }
            Page.CALIBRATION -> {
                drawCircle(colour, s * 0.38f, style = stroke)
                drawArc(colour, 90f, 180f, true, Offset(s * 0.12f, s * 0.12f), androidx.compose.ui.geometry.Size(s * 0.76f, s * 0.76f))
            }
            Page.STRIP -> for (k in 0 until 3) {
                val y = s * (0.25f + 0.25f * k)
                drawLine(colour, Offset(s * 0.1f, y), Offset(s * 0.9f, y), stroke.width, StrokeCap.Round)
                drawCircle(colour, s * 0.08f, Offset(s * (0.3f + 0.2f * k), y))
            }
        }
    }
}

@Composable
private fun Logo() {
    Canvas(Modifier.size(20.dp)) {
        val s = size.width
        val stroke = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round)
        drawRoundRect(TextPrimary, Offset(s * 0.22f, s * 0.3f), androidx.compose.ui.geometry.Size(s * 0.56f, s * 0.38f), CornerRadius(3f), style = stroke)
        drawArc(Accent, 120f, 120f, false, Offset(s * 0.04f, s * 0.12f), androidx.compose.ui.geometry.Size(s * 0.5f, s * 0.75f), style = stroke)
        drawArc(Accent, -60f, 120f, false, Offset(s * 0.46f, s * 0.12f), androidx.compose.ui.geometry.Size(s * 0.5f, s * 0.75f), style = stroke)
    }
}

// ---- Start ----

@Composable
private fun ColumnScope.StartPage(s: Settings, status: Status.State, change: (Settings) -> Unit) {
    var hint by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LivePreview(status.segments, s.sides, Modifier.width(480.dp).height(290.dp).clip(RoundedCornerShape(14.dp)).background(ScreenColour), ring = 26.dp)
        Column(Modifier.weight(1f).height(290.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusCard("Obraz", status.picture, Modifier.weight(1f))
            StatusCard("Taśma", status.strip, Modifier.weight(1f))
            StatusCard("ADB", status.adb, Modifier.weight(1f))
        }
    }
    if (status.problem.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Problem(status.problem)
    }
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        SettingRow(
            "Ambilight", hint = "Włącza i wyłącza podświetlenie. Po wyłączeniu taśma gaśnie płynnie.",
            onHint = { hint = it }, modifier = Modifier.width(170.dp), checked = s.enabled, tall = true,
        ) { change(s.copy(enabled = !s.enabled)) }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in Smoothing.entries) {
                Choice(p.label, p == s.smoothing, Modifier.weight(1f), onFocus = {
                    hint = "Płynność: taśma zaczyna zmianę od razu, a profil decyduje, jak miękko dochodzi do koloru."
                }) { change(s.copy(smoothing = p)) }
            }
        }
        SettingRow(
            "Jasność", "${s.brightness}%", "Lewo i prawo zmieniają jasność taśmy.", { hint = it },
            Modifier.width(190.dp), tall = true,
            onStep = { d -> change(s.copy(brightness = (s.brightness + 10 * d).coerceIn(10, 100))) },
        )
    }
    Spacer(Modifier.weight(1f))
    Text(
        hint.ifEmpty { "Taśma czyta teraz tylko wideo pokazowe, żeby było widać, jak działa. Po wyjściu z aplikacji wraca do obrazu." },
        color = TextMuted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2,
    )
}

@Composable
private fun StatusCard(label: String, value: String, modifier: Modifier) {
    Column(modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label.uppercase(), color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(3.dp))
        Text(value, color = TextPrimary, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Problem(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().background(Warning.copy(alpha = 0.14f), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        color = Warning, fontSize = 13.sp, lineHeight = 18.sp,
    )
}

/** One of a few options side by side; the chosen one is filled with the accent. */
@Composable
private fun Choice(label: String, chosen: Boolean, modifier: Modifier, onFocus: () -> Unit = {}, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(52.dp).onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (chosen) Accent else Panel,
            contentColor = if (chosen) Background else TextSecondary,
            focusedContainerColor = PanelFocused,
            focusedContentColor = Background,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

// ---- Sections with a list on the left and an explanation on the right ----

/** What the right panel explains: the focused row. */
private class Focus(val key: String, val title: String, val text: String)

@Composable
private fun ListAndPanel(
    status: Status.State,
    sides: Int,
    labels: Boolean = false,
    rows: @Composable ColumnScope.(onFocus: (Focus) -> Unit) -> Unit,
    extra: @Composable ColumnScope.(Focus?) -> Unit = {},
) {
    var focus by remember { mutableStateOf<Focus?>(null) }
    if (status.problem.isNotEmpty()) {
        Problem(status.problem)
        Spacer(Modifier.height(10.dp))
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(
            Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { rows { focus = it } }
        Column(
            Modifier.weight(1f).fillMaxHeight().background(Panel, RoundedCornerShape(16.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LivePreview(status.segments, sides, Modifier.width(300.dp).height(180.dp).align(Alignment.CenterHorizontally), labels = labels)
            focus?.let {
                Text(it.title, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(it.text, color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
            }
            extra(focus)
        }
    }
}

/** A row that explains itself in the panel when focused. */
@Composable
private fun Explained(
    key: String,
    label: String,
    value: String = "",
    text: String,
    onFocus: (Focus) -> Unit,
    checked: Boolean? = null,
    onStep: ((Int) -> Unit)? = null,
    onClick: () -> Unit = { onStep?.invoke(1) },
) = SettingRow(label, value, text, { onFocus(Focus(key, label, it)) }, checked = checked, onStep = onStep, onClick = onClick)

private fun <T> next(all: List<T>, current: T, d: Int): T = all[(all.indexOf(current).coerceAtLeast(0) + d).mod(all.size)]

// ---- Obraz ----

@Composable
private fun PicturePage(s: Settings, status: Status.State, change: (Settings) -> Unit) {
    ListAndPanel(status, s.sides, rows = { onFocus ->
        Section("Ruch")
        Explained(
            "smoothing", "Płynność", s.smoothing.label,
            "Taśma zaczyna zmianę od razu. Płynność decyduje, jak miękko kolor dochodzi do celu; gaszenie jest " +
                "zawsze wolniejsze niż zapalanie, jak w Ambilight.",
            onFocus, onStep = { d -> change(s.copy(smoothing = next(Smoothing.entries, s.smoothing, d))) },
        )
        Explained(
            "brightness", "Jasność", "${s.brightness}%",
            "Jasność całej taśmy. Liczona przed zaokrągleniem do 8 bitów, więc nie dodaje schodków w ciemnych scenach.",
            onFocus, onStep = { d -> change(s.copy(brightness = (s.brightness + 10 * d).coerceIn(10, 100))) },
        )
        Section("Odczyt obrazu")
        Explained(
            "sampling", "Próbkowanie strefy", s.sampling.label,
            "Każda strefa jest czytana po kolei w ${s.sampling.points} " +
                (if (s.sampling.points == 1) "punkcie" else "punktach") +
                ", a jej kolor to średnia. Mały obiekt w jednym punkcie nie przestawia całej strefy; cięcia scen " +
                "działają od razu. Szybkie: 1 punkt, dokładne: 4 (średnia z ok. 1,2 s).",
            onFocus, onStep = { d -> change(s.copy(sampling = next(Sampling.entries, s.sampling, d))) },
        )
        Explained(
            "subtitles", "Filtr napisów",
            text = "Białe i żółte napisy nie rozświetlają środka dolnej krawędzi. Działa przy 4 bokach.",
            onFocus = onFocus, checked = s.filterSubtitles,
        ) { change(s.copy(filterSubtitles = !s.filterSubtitles)) }
        Section("Zasilanie")
        Explained(
            "fadeOut", "Wygaszanie", seconds(s.fadeOutMs),
            "Gdy telewizor przechodzi w czuwanie albo wyłączysz Ambilight, taśma gaśnie powoli zamiast od razu.",
            onFocus, onStep = { d -> change(s.copy(fadeOutMs = next(Config.FADE_OUT_MS, s.fadeOutMs, d))) },
        )
        Explained(
            "fadeIn", "Rozjaśnianie", seconds(s.fadeInMs),
            "Po włączeniu telewizora albo Ambilight taśma rozjaśnia się łagodnie do obrazu.",
            onFocus, onStep = { d -> change(s.copy(fadeInMs = next(Config.FADE_IN_MS, s.fadeInMs, d))) },
        )
    }, extra = { focus -> if (focus?.key == "smoothing") SmoothingChart(s.smoothing) })
}

private fun seconds(ms: Int) = if (ms == 0) "od razu" else "${"%.1f".format(ms / 1000f).removeSuffix(",0").removeSuffix(".0")} s"

/** Each profile's response to the picture getting brighter, then darker; the chosen one in the accent. */
@Composable
private fun SmoothingChart(chosen: Smoothing) {
    Canvas(Modifier.fillMaxWidth().height(70.dp)) {
        val w = size.width
        val h = size.height
        drawLine(Line, Offset(0f, h - 1), Offset(w, h - 1), 1.dp.toPx())
        for (p in Smoothing.entries.sortedBy { it == chosen }) {
            val path = Path()
            for (i in 0..100) {
                val t = i * 20f // 0..2000 ms: up at 0, down at 1000
                val up = t < 1000f
                val ms = if (up) p.attackMs else p.releaseMs
                val x = (if (up) t else t - 1000f) * 3.89f / ms
                val f = (1 + x) * exp(-x)
                val v = if (up) 1 - f else f
                val px = w * i / 100f
                val py = h - 2 - v * (h - 6)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, if (p == chosen) Accent else Line, style = Stroke(width = (if (p == chosen) 2.5f else 1.5f).dp.toPx()))
        }
    }
}

// ---- Kalibracja ----

@Composable
private fun CalibrationPage(s: Settings, status: Status.State, change: (Settings) -> Unit, onWhiteTest: () -> Unit) {
    ListAndPanel(status, s.sides, rows = { onFocus ->
        Section("Otoczenie")
        Explained(
            "wall", "Kolor ściany", Calibration.walls[s.wall].label,
            "Ściana zabarwia światło taśmy. Aplikacja dzieli jej odcień, żeby kolory z obrazu wyglądały na ścianie " +
                "jak na ekranie.",
            onFocus, onStep = { d -> change(s.copy(wall = (s.wall + d).mod(Calibration.walls.size))) },
        )
        Explained(
            "white", "Temperatura bieli", Calibration.whitePoints[s.whitePoint].label,
            "Odcień bieli na taśmie: ciepła jak żarówka, neutralna jak ekran, zimna jak dzienne światło.",
            onFocus, onStep = { d -> change(s.copy(whitePoint = (s.whitePoint + d).mod(Calibration.whitePoints.size))) },
        )
        Section("Balans bieli taśmy")
        val gainText = "Włącz „Pokaż biel na taśmie” i zmniejszaj kanał, który przeważa, aż biel na ścianie będzie czysta."
        Explained("r", "Czerwony", "${s.gainR}%", gainText, onFocus, onStep = { d -> change(s.copy(gainR = gain(s.gainR, d))) })
        Explained("g", "Zielony", "${s.gainG}%", gainText, onFocus, onStep = { d -> change(s.copy(gainG = gain(s.gainG, d))) })
        Explained("b", "Niebieski", "${s.gainB}%", gainText, onFocus, onStep = { d -> change(s.copy(gainB = gain(s.gainB, d))) })
        Explained(
            "whiteTest", "Pokaż biel na taśmie",
            text = "Cała taśma świeci skalibrowaną bielą przez 2 minuty. Najlepiej wieczorem, przy zgaszonym świetle.",
            onFocus = onFocus, checked = status.white, onClick = onWhiteTest,
        )
        Explained(
            "reset", "Przywróć domyślne", "",
            "Biała ściana, neutralna biel, wszystkie kanały 100%.", onFocus,
        ) { change(s.copy(wall = 0, whitePoint = 1, gainR = 100, gainG = 100, gainB = 100)) }
    }, extra = { WallPreview(s) })
}

private fun gain(v: Int, d: Int) = (v + 2 * d).coerceIn(Config.MIN_GAIN, 100)

/** White light on the chosen wall, as the strip would give it without and with the correction. */
@Composable
private fun WallPreview(s: Settings) {
    val wall = Calibration.walls[s.wall].rgb
    val p = Calibration.whitePoints[s.whitePoint]
    val raw = floatArrayOf(p.r * s.gainR / 100f, p.g * s.gainG / 100f, p.b * s.gainB / 100f).let { g -> val m = g.max(); FloatArray(3) { g[it] / m } }
    val fixed = Calibration.gains(s)
    fun onWall(g: FloatArray) = Color(
        red = g[0] * (wall shr 16 and 0xff) / 255f,
        green = g[1] * (wall shr 8 and 0xff) / 255f,
        blue = g[2] * (wall and 0xff) / 255f,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((label, g) in listOf("bez korekty" to raw, "z korektą" to fixed)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF000000.toInt() or wall).copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.fillMaxWidth(0.8f).height(30.dp).blur(10.dp, BlurredEdgeTreatment.Unbounded).background(onWall(g), RoundedCornerShape(15.dp)))
                }
                Text(
                    if (g === fixed) "$label · R ${pct(g[0])} G ${pct(g[1])} B ${pct(g[2])}" else label,
                    color = TextMuted, fontSize = 11.sp,
                )
            }
        }
    }
}

private fun pct(v: Float) = Math.round(v * 100)

// ---- Taśma ----

@Composable
private fun StripPage(s: Settings, status: Status.State, change: (Settings) -> Unit, onSegmentTest: () -> Unit, open: (String) -> Unit) {
    ListAndPanel(status, s.sides, labels = true, rows = { onFocus ->
        Explained(
            "wled", "Sterownik WLED", s.espName.ifEmpty { s.espIp },
            "Wybierz sterownik WLED znaleziony w sieci albo wpisz jego adres. Liczba diod ustawi się sama.", onFocus,
        ) { open("wled") }
        Section("Montaż")
        Explained(
            "count", "Liczba diod", "${s.ledCount}", "Lewo i prawo zmieniają liczbę diod. Przytrzymaj, żeby zmieniać szybciej.",
            onFocus, onStep = { d -> change(s.copy(ledCount = (s.ledCount + d).coerceIn(1, Config.MAX_LEDS))) },
        )
        Explained(
            "reverse", "Kierunek", if (s.reverse) "odwrócony" else "zwykły",
            "Odwróć, jeśli kolory biegną wokół ekranu w przeciwną stronę niż obraz.", onFocus,
        ) { change(s.copy(reverse = !s.reverse)) }
        Explained(
            "sides", "Układ", if (s.sides == 4) "4 boki" else "3 boki",
            "4 boki: taśma dookoła ekranu. 3 boki: lewy, górny i prawy, bez dolnej krawędzi.", onFocus,
        ) { change(s.copy(sides = if (s.sides == 4) 3 else 4)) }
        Explained(
            "offset", "Przesunięcie", "${s.offset}",
            "Lewo i prawo przesuwają początek taśmy o jedną diodę. Włącz test segmentów i ustaw tak, żeby kolory " +
                "wypadły przy liczbach 1, 8 i 14 na podglądzie.",
            onFocus, onStep = { d -> change(s.copy(offset = (s.offset + d).mod(s.ledCount))) },
        )
        Section("Testy")
        Explained(
            "segments", "Test segmentów", if (status.testing) "zakończ" else "60 s",
            "Zapala strefę 1 na czerwono, 2 na żółto (pokazuje kierunek), 8 na zielono i 14 na fioletowo.",
            onFocus, onClick = onSegmentTest,
        )
        Explained(
            "picture", "Obraz testowy", "pokaż",
            "Pełny ekran z kolorowymi krawędziami: lewa czerwona, górna zielona, prawa niebieska, dolna żółta.", onFocus,
        ) { open("picture") }
        Explained(
            "latency", "Test opóźnienia", "30 s",
            "Ekran miga czernią i bielą przez 30 s, a aplikacja mierzy czas od zmiany obrazu do taśmy.", onFocus,
        ) { open("latency") }
    })
}

// ---- Shared pieces ----

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(),
        Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp),
        color = TextMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.2.sp,
    )
}

/**
 * One setting. With [checked] it is a switch; with [onStep] the left and right keys change the value (and OK
 * steps up); otherwise OK runs [onClick] and [value] says what it will do or what is set.
 */
@Composable
private fun SettingRow(
    label: String,
    value: String = "",
    hint: String,
    onHint: (String) -> Unit,
    modifier: Modifier = Modifier,
    checked: Boolean? = null,
    onStep: ((Int) -> Unit)? = null,
    tall: Boolean = false,
    onClick: () -> Unit = { onStep?.invoke(1) },
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onHint(hint)
            }
            .onPreviewKeyEvent { e ->
                if (onStep == null || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { onStep(-1); true }
                    Key.DirectionRight -> { onStep(1); true }
                    else -> false
                }
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Panel,
            contentColor = TextPrimary,
            focusedContainerColor = PanelFocused,
            focusedContentColor = Background,
            pressedContainerColor = PanelFocused,
            pressedContentColor = Background,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp).height(if (tall) 52.dp else 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
            when {
                checked != null -> Switch(checked)
                onStep != null -> Stepper(value, focused)
                else -> Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun RowScope.Stepper(value: String, focused: Boolean) {
    val arrow = LocalContentColor.current.copy(alpha = if (focused) 0.6f else 0.25f)
    Text("‹", color = arrow, fontSize = 16.sp)
    Text(value, Modifier.padding(horizontal = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    Text("›", color = arrow, fontSize = 16.sp)
}

@Composable
private fun Switch(on: Boolean) {
    val content = LocalContentColor.current
    Canvas(Modifier.size(width = 34.dp, height = 18.dp)) {
        val r = size.height / 2
        drawRoundRect(if (on) Accent else content.copy(alpha = 0.25f), cornerRadius = CornerRadius(r, r))
        drawCircle(
            if (on) Background else content.copy(alpha = 0.8f),
            radius = r - 3.dp.toPx(),
            center = Offset(if (on) size.width - r else r, r),
        )
    }
}
