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

    /** Which strip segment sits at the start of the path (bottom of the left edge). */
    var offset: Int
        get() = prefs.getInt("offset", 0)
        set(v) = prefs.edit().putInt("offset", v).apply()

    var goveeMac: String?
        get() = prefs.getString("govee_mac", null)
        set(v) = prefs.edit().putString("govee_mac", v).apply()

    /** The strip's own camera-sync setting, captured from the strip, replayed to hand control back to the camera. */
    var cameraFrame: String
        get() = prefs.getString("camera_frame", null) ?: "330500000164003200646464640000"
        set(v) = prefs.edit().putString("camera_frame", v).apply()
}
