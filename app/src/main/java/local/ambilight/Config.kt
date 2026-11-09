package local.ambilight

import android.content.Context
import android.content.SharedPreferences

/** User settings and remembered device facts, shared by the service and the screen. */
class Config(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("ambilight", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    /** Strip runs the other way round the screen. */
    var reverse: Boolean
        get() = prefs.getBoolean("reverse", false)
        set(v) = prefs.edit().putBoolean("reverse", v).apply()

    /** 4 = all round (this TV's strip); 3 = left, top, right. */
    var sides: Int
        get() = prefs.getInt("sides", 4)
        set(v) = prefs.edit().putInt("sides", v).apply()

    /** Suppress subtitle glare on bottom LED segments. */
    var filterSubtitles: Boolean
        get() = prefs.getBoolean("filter_subtitles", true)
        set(v) = prefs.edit().putBoolean("filter_subtitles", v).apply()

    /** Strip offset in individual LED units around the screen. */
    var offset: Int
        get() = prefs.getInt("offset", 3)
        set(v) = prefs.edit().putInt("offset", v).apply()

    /** ESP32 WLED IP address for DDP UDP packets. */
    var espIp: String
        get() = prefs.getString("esp_ip", null) ?: "192.168.0.189"
        set(v) = prefs.edit().putString("esp_ip", v).apply()

    /** Total number of addressable LEDs on the strip. */
    var ledCount: Int
        get() {
            val v = prefs.getInt("led_count", 72)
            return if (v == 216) 72 else v
        }
        set(v) = prefs.edit().putInt("led_count", v).apply()

    var goveeMac: String?
        get() = prefs.getString("govee_mac", null)
        set(v) = prefs.edit().putString("govee_mac", v).apply()

    /** The strip's own camera-sync setting, captured from the strip, replayed to hand control back to the camera. */
    var cameraFrame: String
        get() = prefs.getString("camera_frame", null) ?: "330500000164003200646464640000"
        set(v) = prefs.edit().putString("camera_frame", v).apply()
}
