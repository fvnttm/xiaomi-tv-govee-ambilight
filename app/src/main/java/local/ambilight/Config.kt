package local.ambilight

import android.content.Context
import android.content.SharedPreferences

/** Everything the user can set, read once so the render thread doesn't query preferences 120 times a second. */
data class Settings(
    val enabled: Boolean,
    /** Strip runs the other way round the screen. */
    val reverse: Boolean,
    /** 4 = all round; 3 = left, top, right. */
    val sides: Int,
    /** Keep subtitles from lighting the middle of the bottom edge. */
    val filterSubtitles: Boolean,
    /** Where the strip starts, in LEDs round the screen. */
    val offset: Int,
    /** WLED's address, for DDP packets and its JSON API. */
    val espIp: String,
    /** WLED's name, shown instead of the bare address when known. */
    val espName: String,
    val ledCount: Int,
    /** Strip brightness in percent. */
    val brightness: Int,
    /** Slower, softer transitions instead of the fastest ones. */
    val smooth: Boolean,
)

/** User settings, shared by the service and the screen. */
class Config(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("ambilight", Context.MODE_PRIVATE)

    fun read() = Settings(
        enabled = prefs.getBoolean("enabled", true),
        reverse = prefs.getBoolean("reverse", false),
        sides = prefs.getInt("sides", 4),
        filterSubtitles = prefs.getBoolean("filter_subtitles", true),
        offset = prefs.getInt("offset", 3),
        espIp = prefs.getString("esp_ip", null) ?: "192.168.0.189",
        espName = prefs.getString("esp_name", null) ?: "",
        // An earlier build stored 216 for the 72-LED strip it was made for.
        ledCount = prefs.getInt("led_count", 72).let { if (it == 216) 72 else it }.coerceIn(1, MAX_LEDS),
        brightness = prefs.getInt("brightness", 100).coerceIn(10, 100),
        smooth = prefs.getBoolean("smooth", false),
    )

    fun write(s: Settings) = prefs.edit()
        .putBoolean("enabled", s.enabled)
        .putBoolean("reverse", s.reverse)
        .putInt("sides", s.sides)
        .putBoolean("filter_subtitles", s.filterSubtitles)
        .putInt("offset", s.offset)
        .putString("esp_ip", s.espIp)
        .putString("esp_name", s.espName)
        .putInt("led_count", s.ledCount)
        .putInt("brightness", s.brightness)
        .putBoolean("smooth", s.smooth)
        .apply()

    companion object {
        const val MAX_LEDS = 1500
    }
}
