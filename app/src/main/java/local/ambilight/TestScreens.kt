package local.ambilight

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/** Known colours on each edge, so the whole chain (picture -> strip) can be checked by eye. Back closes it. */
@Composable
fun TestPicture(onExit: () -> Unit) {
    BackHandler(onBack = onExit)
    Box(Modifier.fillMaxSize().background(Color(0xFF303236))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(0.12f).align(Alignment.CenterStart).background(Color(0xFFFF0000)))
        Box(Modifier.fillMaxHeight().fillMaxWidth(0.12f).align(Alignment.CenterEnd).background(Color(0xFF0000FF)))
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.15f).align(Alignment.TopCenter).background(Color(0xFF00FF00)))
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.15f).align(Alignment.BottomCenter).background(Color(0xFFFFD000)))
        Text(
            "Obraz testowy\nlewa czerwona · góra zielona · prawa niebieska · dół żółty\n\nWstecz zamyka",
            Modifier.align(Alignment.Center),
            color = TextPrimary,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Full screen flipping between black and white every 1.5 s for 30 s; LatencyProbe measures how long the reading
 * and the strip take to follow. The results stay on screen until Back.
 */
@Composable
fun LatencyTest(onExit: () -> Unit) {
    BackHandler(onBack = onExit)
    val result by LatencyProbe.result.collectAsState()
    var white by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        LatencyProbe.start()
        delay(1_500) // settle on black first; this state is not measured
        repeat(20) {
            white = !white
            // Taken at the start of the frame that draws the new colour.
            withFrameNanos { LatencyProbe.flip(white) }
            delay(1_500)
        }
        LatencyProbe.stop()
        done = true
    }
    Box(Modifier.fillMaxSize().background(if (white && !done) Color.White else Color.Black)) {
        val text = if (done) result.describe() + "\n\nWstecz zamyka" else "Test opóźnienia · ${result.flips}/20"
        Text(
            text,
            Modifier.align(Alignment.Center).background(Color(0xFF303236), RoundedCornerShape(8.dp)).padding(16.dp),
            color = TextPrimary,
            fontSize = if (done) 18.sp else 13.sp,
            lineHeight = if (done) 28.sp else 18.sp,
        )
    }
}

