package local.ambilight

import android.content.Context
import android.content.pm.PackageManager
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * The app's own ADB client to the TV's adbd on 127.0.0.1:5555. The first connection shows the
 * "Allow USB debugging?" dialog on the TV for this app's key; after "Always allow" it connects silently.
 */
class AdbBridge(private val context: Context) {
    private val privateKey = File(context.filesDir, "adb/adbkey")
    private val publicKey = File(context.filesDir, "adb/adbkey.pub")

    fun connect(): Dadb {
        if (!privateKey.exists()) {
            AdbKeyPair.generate(privateKey, publicKey)
            // The name shown in the TV's authorisation dialog.
            publicKey.writeText(publicKey.readText().replace("unknown@unknown", "Ambilight@TV"))
        }
        return Dadb.create("127.0.0.1", 5555, AdbKeyPair.read(privateKey, publicKey), connectTimeout = 3000)
    }

    /** Grants the app's runtime permissions through the shell, so nothing has to be clicked on the TV. */
    fun grantPermissions(adb: Dadb) {
        val pkg = context.packageName
        for (p in listOf("BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "POST_NOTIFICATIONS")) {
            if (context.checkSelfPermission("android.permission.$p") != PackageManager.PERMISSION_GRANTED) {
                adb.shell("pm grant $pkg android.permission.$p")
            }
        }
    }

    /**
     * Shell command that runs PixelDaemon from this app's APK as the shell user.
     * [frameMs] must cover one displayed frame at the current refresh rate (the HAL latches once per frame).
     */
    fun daemonCommand(panelW: Int, panelH: Int, sides: Int, frameMs: Int): String {
        val apk = context.applicationInfo.sourceDir
        return "shell:CLASSPATH=$apk exec app_process /system/bin local.ambilight.PixelDaemon " +
            "$panelW $panelH $sides ${Govee.SEGMENTS} $frameMs"
    }
}
