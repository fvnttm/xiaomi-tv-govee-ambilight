package local.ambilight

import androidx.compose.ui.graphics.Color

// One dark neutral ramp and one accent: the warm white of the strip itself. Text is the TV's system typeface,
// the same one the Google TV settings around this app use.
val Background = Color(0xFF0D0E11)
val Panel = Color(0xFF181A20)
val PanelFocused = Color(0xFFECEDEF)
val TextPrimary = Color(0xFFE9EAEE)
val TextMuted = Color(0xFF8E929C)
val Accent = Color(0xFFFFB347)
val Warning = Color(0xFFFF7A59)
val ScreenColour = Color(0xFF1B1D22)
/** An unlit segment, so the strip's shape shows even when the picture is black. */
val SlotColour = Color(0xFF2A2D34)
