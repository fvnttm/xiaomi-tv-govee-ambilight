package local.ambilight

import android.content.Context
import android.content.SharedPreferences

/** How many points of each zone the daemon reads in turn; its colour is their mean. */
enum class Sampling(val label: String, val points: Int) {
    FAST("szybkie", 1),
    BALANCED("zrównoważone", 2),
    PRECISE("dokładne", 4),
}

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
    /** How softly the strip follows the picture. */
    val smoothing: Smoothing,
    /** Time for a zone whose picture went black to fade out (ms; 0: the profile's release time). */
    val blackFadeMs: Int,
    /** Small changes glide over the time between readings: smoother, but up to ~0.3 s later. */
    val glide: Boolean,
    val sampling: Sampling,
    /** How long the strip fades out on standby or when switched off, and in when it starts (ms, 0: at once). */
    val fadeOutMs: Int,
    val fadeInMs: Int,
    /** Index into [Calibration.walls] and [Calibration.whitePoints]. */
    val wall: Int,
    val whitePoint: Int,
    /** The strip's own white balance, percent per channel (50..100). */
    val gainR: Int,
    val gainG: Int,
    val gainB: Int,
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
        smoothing = enumOf("smoothing", Smoothing.entries)
            // Earlier builds stored only fast (false) or smooth (true) reactions.
            ?: if (prefs.getBoolean("smooth", true)) Smoothing.STANDARD else Smoothing.DYNAMIC,
        blackFadeMs = prefs.getInt("black_fade_ms", 0).let { if (it in BLACK_FADE_MS) it else 0 },
        glide = prefs.getBoolean("glide", true),
        sampling = enumOf("sampling", Sampling.entries) ?: Sampling.BALANCED,
        fadeOutMs = prefs.getInt("fade_out_ms", 2000).let { if (it in FADE_OUT_MS) it else 2000 },
        fadeInMs = prefs.getInt("fade_in_ms", 1000).let { if (it in FADE_IN_MS) it else 1000 },
        wall = prefs.getInt("wall", 0).coerceIn(Calibration.walls.indices),
        whitePoint = prefs.getInt("white_point", 1).coerceIn(Calibration.whitePoints.indices),
        gainR = prefs.getInt("gain_r", 100).coerceIn(MIN_GAIN, 100),
        gainG = prefs.getInt("gain_g", 100).coerceIn(MIN_GAIN, 100),
        gainB = prefs.getInt("gain_b", 100).coerceIn(MIN_GAIN, 100),
    )

    private fun <E : Enum<E>> enumOf(key: String, all: List<E>) =
        prefs.getString(key, null)?.let { name -> all.firstOrNull { it.name == name } }

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
        .putString("smoothing", s.smoothing.name)
        .remove("smooth")
        .putInt("black_fade_ms", s.blackFadeMs)
        .putBoolean("glide", s.glide)
        .putString("sampling", s.sampling.name)
        .putInt("fade_out_ms", s.fadeOutMs)
        .putInt("fade_in_ms", s.fadeInMs)
        .putInt("wall", s.wall)
        .putInt("white_point", s.whitePoint)
        .putInt("gain_r", s.gainR)
        .putInt("gain_g", s.gainG)
        .putInt("gain_b", s.gainB)
        .apply()

    companion object {
        const val MAX_LEDS = 1500
        const val MIN_GAIN = 50
        val FADE_OUT_MS = listOf(0, 1000, 2000, 4000)
        val FADE_IN_MS = listOf(0, 500, 1000, 2000)
        /** 0: as the profile releases. */
        val BLACK_FADE_MS = listOf(0, 400, 200)
    }
}
