package local.ambilight

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, AmbilightService::class.java))
        val config = Config(this)
        // For tests over ADB: am start -n local.ambilight/.MainActivity --es open picture|latency|wled
        val open = intent.getStringExtra("open")
        setContent {
            App(config, open) {
                startService(Intent(this, AmbilightService::class.java).setAction(AmbilightService.ACTION_TEST))
            }
        }
    }
}

/** The settings screen, or one of the full-screen pages opened from it. Back on a page returns to the row that opened it. */
@Composable
private fun App(config: Config, open: String?, onSegmentTest: () -> Unit) {
    var settings by remember { mutableStateOf(config.read()) }
    var page by remember { mutableStateOf(open) }
    var focusKey by remember { mutableStateOf("enabled") }
    fun change(new: Settings) {
        settings = new
        config.write(new)
    }
    when (page) {
        "picture" -> TestPicture { page = null }
        "latency" -> LatencyTest { page = null }
        "wled" -> WledScreen(settings, onPick = { device ->
            change(settings.copy(
                espIp = device.ip,
                espName = device.name,
                ledCount = if (device.leds in 1..Config.MAX_LEDS) device.leds else settings.ledCount,
            ))
        }) { page = null }
        else -> SettingsScreen(settings, ::change, focusKey, onSegmentTest) { key ->
            focusKey = key
            page = key
        }
    }
}

@Composable
private fun SettingsScreen(
    s: Settings,
    change: (Settings) -> Unit,
    focusKey: String,
    onSegmentTest: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val status by Status.state.collectAsState()
    var hint by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    /** The row to focus when the screen appears: the first one, or the one a page was opened from. */
    fun Modifier.initial(key: String) = if (key == focusKey) focusRequester(focus) else this

    Row(
        Modifier
            .fillMaxSize()
            .background(Background)
            .padding(horizontal = 56.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(vertical = 40.dp)) {
            Text("Ambilight", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(16.dp))
            TvPreview(status.segments, s.sides, Modifier.height(204.dp).aspectRatio(1.62f))
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusLine("Tryb", status.mode)
                StatusLine("Obraz", status.picture)
                StatusLine("Taśma", status.strip)
                StatusLine("ADB", status.adb)
            }
            if (status.problem.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    status.problem,
                    Modifier.background(Warning.copy(alpha = 0.14f), RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                    color = Warning,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(hint, color = TextMuted, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }

        Column(
            Modifier.width(400.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val onHint = { text: String -> hint = text }
            SettingRow(
                "Ambilight", hint = "Włącza i wyłącza podświetlenie. Po wyłączeniu taśma gaśnie.", onHint = onHint,
                modifier = Modifier.initial("enabled"), checked = s.enabled,
            ) { change(s.copy(enabled = !s.enabled)) }

            Section("Taśma")
            SettingRow(
                "Sterownik WLED", s.espName.ifEmpty { s.espIp },
                "Wybierz sterownik WLED znaleziony w sieci albo wpisz jego adres. Liczba diod ustawi się sama.",
                onHint, Modifier.initial("wled"),
            ) { onOpen("wled") }
            SettingRow(
                "Liczba diod", "${s.ledCount}",
                "Lewo i prawo zmieniają liczbę diod na taśmie. Przytrzymaj, żeby zmieniać szybciej.", onHint,
                onStep = { d -> change(s.copy(ledCount = (s.ledCount + d).coerceIn(1, Config.MAX_LEDS))) },
            )
            SettingRow(
                "Kierunek", if (s.reverse) "odwrócony" else "zwykły",
                "Odwróć, jeśli kolory biegną wokół ekranu w przeciwną stronę niż obraz.", onHint,
            ) { change(s.copy(reverse = !s.reverse)) }
            SettingRow(
                "Układ", if (s.sides == 4) "4 boki" else "3 boki",
                "4 boki: taśma dookoła ekranu. 3 boki: lewy, górny i prawy, bez dolnej krawędzi.", onHint,
            ) { change(s.copy(sides = if (s.sides == 4) 3 else 4)) }
            SettingRow(
                "Przesunięcie", "${s.offset}",
                "Lewo i prawo przesuwają początek taśmy o jedną diodę. Włącz test segmentów i ustaw tak, żeby " +
                    "kolory wypadły przy liczbach 1, 8 i 14 na podglądzie.",
                onHint,
                onStep = { d -> change(s.copy(offset = (s.offset + d).mod(s.ledCount))) },
            )

            Section("Obraz")
            SettingRow(
                "Jasność", "${s.brightness}%", "Lewo i prawo zmieniają jasność taśmy.", onHint,
                onStep = { d -> change(s.copy(brightness = (s.brightness + 10 * d).coerceIn(10, 100))) },
            )
            SettingRow(
                "Płynność", s.smoothing.label,
                "Taśma zaczyna zmianę od razu; płynność decyduje, jak miękko dochodzi do nowego koloru. " +
                    "Dynamiczna: szybko. Standardowa: jak Ambilight. Spokojna: łagodnie. Gaśnie zawsze wolniej, " +
                    "niż się zapala. Lewo i prawo zmieniają ustawienie.",
                onHint,
                onStep = { d ->
                    val all = Smoothing.entries
                    change(s.copy(smoothing = all[(s.smoothing.ordinal + d).mod(all.size)]))
                },
            )
            SettingRow(
                "Filtr napisów",
                hint = "Białe i żółte napisy nie rozświetlają środka dolnej krawędzi. Działa przy 4 bokach.",
                onHint = onHint, checked = s.filterSubtitles,
            ) { change(s.copy(filterSubtitles = !s.filterSubtitles)) }

            Section("Testy")
            SettingRow(
                "Test segmentów", if (status.testing) "zakończ" else "60 s",
                "Zapala strefę 1 na czerwono, 2 na żółto (pokazuje kierunek), 8 na zielono i 14 na fioletowo. " +
                    "Ustaw kierunek i przesunięcie tak, żeby kolory wypadły przy liczbach na podglądzie.",
                onHint, onClick = onSegmentTest,
            )
            SettingRow(
                "Obraz testowy", "pokaż",
                "Pełny ekran z kolorowymi krawędziami: lewa czerwona, górna zielona, prawa niebieska, dolna żółta. " +
                    "Taśma powinna pokazać te same kolory po tych samych stronach.",
                onHint, Modifier.initial("picture"),
            ) { onOpen("picture") }
            SettingRow(
                "Test opóźnienia", "30 s",
                "Ekran miga czernią i bielą przez 30 s, a aplikacja mierzy czas od zmiany obrazu do koloru " +
                    "wysłanego na taśmę.",
                onHint, Modifier.initial("latency"),
            ) { onOpen("latency") }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(),
        Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp),
        color = TextMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.2.sp,
    )
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(label, color = TextMuted, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.width(64.dp))
        Text(value, color = TextPrimary, fontSize = 14.sp, lineHeight = 19.sp)
    }
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
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
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
            Modifier.padding(horizontal = 16.dp).height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
            when {
                checked != null -> Switch(checked)
                onStep != null -> {
                    val arrow = LocalContentColor.current.copy(alpha = if (focused) 0.7f else 0.25f)
                    Text("‹", color = arrow, fontSize = 18.sp)
                    Text(
                        value,
                        Modifier.padding(horizontal = 12.dp),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text("›", color = arrow, fontSize = 18.sp)
                }
                else -> Text(value, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Switch(on: Boolean) {
    val content = LocalContentColor.current
    Canvas(Modifier.size(width = 36.dp, height = 20.dp)) {
        val r = size.height / 2
        drawRoundRect(if (on) Accent else content.copy(alpha = 0.25f), cornerRadius = CornerRadius(r, r))
        drawCircle(
            if (on) Background else content.copy(alpha = 0.8f),
            radius = r - 3.dp.toPx(),
            center = Offset(if (on) size.width - r else r, r),
        )
    }
}
