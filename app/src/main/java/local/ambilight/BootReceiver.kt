package local.ambilight

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the service when the TV boots and after the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.startForegroundService(Intent(context, AmbilightService::class.java))
    }
}
