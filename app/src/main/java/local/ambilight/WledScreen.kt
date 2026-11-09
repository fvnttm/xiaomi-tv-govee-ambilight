package local.ambilight

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Choosing the WLED controller: the ones found on the network (mDNS), or an address typed in. Picking either
 * saves the address, and the LED count when WLED answers with it. Back closes.
 */
@Composable
fun WledScreen(settings: Settings, onPick: (WledDevice) -> Unit, onExit: () -> Unit) {
    BackHandler(onBack = onExit)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val found by remember { Wled.discover(context.applicationContext) }.collectAsState(emptyList())
    var address by remember { mutableStateOf(settings.espIp) }
    var message by remember { mutableStateOf("") }
    var fieldFocused by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { field.requestFocus() }

    fun useAddress() {
        val ip = address.trim()
        if (!Wled.isIpv4(ip)) {
            message = "To nie jest adres IPv4, np. 192.168.1.50."
            return
        }
        message = "Sprawdzam $ip…"
        scope.launch {
            val device = withContext(Dispatchers.IO) { Wled.info(ip) }
            onPick(device ?: WledDevice("", ip, 0))
            message = if (device != null) {
                "Zapisano: ${device.name}, ${device.leds} diod."
            } else {
                "Zapisano $ip, ale WLED tam nie odpowiada. Sprawdź adres i czy sterownik jest włączony."
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Background).padding(horizontal = 56.dp, vertical = 40.dp),
    ) {
        Text("Sterownik WLED", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Teraz: ${settings.espName.ifEmpty { "WLED" }} · ${settings.espIp} · ${settings.ledCount} diod",
            color = TextMuted, fontSize = 14.sp,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Column(Modifier.width(400.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Label("Adres")
                BasicTextField(
                    value = address,
                    onValueChange = { v -> address = v.filter { it.isDigit() || it == '.' }.take(15) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(field)
                        .onFocusChanged { fieldFocused = it.isFocused }
                        .background(Panel, RoundedCornerShape(8.dp))
                        .border(2.dp, if (fieldFocused) PanelFocused else Panel, RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    singleLine = true,
                    textStyle = TextStyle(color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium),
                    cursorBrush = SolidColor(Accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { useAddress() }),
                )
                Choice("Użyj tego adresu", "") { useAddress() }
                if (message.isNotEmpty()) {
                    Text(message, Modifier.padding(top = 8.dp), color = TextMuted, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Label("Znalezione w sieci")
                if (found.isEmpty()) {
                    Text(
                        "Szukam… Sterownik musi być włączony i w tej samej sieci co telewizor. " +
                            "Jeśli się nie pojawi, wpisz jego adres obok.",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = TextMuted, fontSize = 14.sp, lineHeight = 20.sp,
                    )
                }
                for (device in found) {
                    val leds = if (device.leds > 0) " · ${device.leds} diod" else ""
                    Choice(device.name, device.ip + leds) {
                        address = device.ip
                        onPick(device)
                        message = "Zapisano: ${device.name}$leds."
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text.uppercase(),
        Modifier.padding(start = 16.dp, bottom = 2.dp),
        color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp,
    )
}

@Composable
private fun Choice(label: String, detail: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
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
        Row(Modifier.padding(horizontal = 16.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(detail, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}
