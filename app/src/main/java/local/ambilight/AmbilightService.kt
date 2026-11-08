package local.ambilight

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import dadb.Dadb
import kotlin.concurrent.thread

const val TAG = "Ambilight"

/**
 * Starts with the TV and keeps the strip following the picture:
 * PixelDaemon (shell, over the TV's own ADB) -> smoothing -> Govee (BLE).
 * With the TV in standby the strip is switched off; when the picture can't be read it goes back to the Govee camera.
 */
class AmbilightService : Service() {
    private lateinit var config: Config
    private lateinit var adb: AdbBridge
    private lateinit var govee: Govee

    @Volatile private var alive = true
    @Volatile private var screenOn = true
    @Volatile private var session: Dadb? = null
    @Volatile private var testUntil = 0L
    private val wake = Object()

    private val smoothed = Array(Govee.SEGMENTS) { FloatArray(3) }
    private var lastPreview = 0L
    private var lastFrameLog = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action == Intent.ACTION_SCREEN_ON
            restartSession()
        }
    }

    /** The daemon waits one frame per point, so a new refresh rate (e.g. 50 Hz for 25 fps films) needs a restart. */
    private val displayListener = object : DisplayManager.DisplayListener {
        private var rate = 0f
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val now = getSystemService(DisplayManager::class.java).getDisplay(displayId)?.refreshRate ?: return
            if (rate != 0f && now != rate) restartSession()
            rate = now
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "enabled" || key == "sides") restartSession()
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        config = Config(this)
        adb = AdbBridge(this)
        govee = Govee(this, config)
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        screenOn = getSystemService(PowerManager::class.java).isInteractive
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        config.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, null)
        govee.start()
        thread(name = "supervisor", isDaemon = true) { supervise() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TEST) {
            testUntil = SystemClock.elapsedRealtime() + 60_000
            if (session == null) govee.mode = Govee.Mode.TEST
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        config.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        unregisterReceiver(screenReceiver)
        restartSession()
        govee.stop()
        super.onDestroy()
    }

    private fun supervise() {
        var backoff = 2_000L
        while (alive) {
            when {
                !config.enabled -> {
                    govee.mode = Govee.Mode.CAMERA
                    Status.set { it.copy(mode = "wyłączony: steruje kamera Govee") }
                    idle(5_000)
                    continue
                }
                !screenOn -> {
                    govee.mode = Govee.Mode.OFF
                    Status.set { it.copy(mode = "TV w czuwaniu") }
                    idle(5_000)
                    continue
                }
            }
            try {
                runSession()
                backoff = 2_000L
            } catch (e: Exception) {
                Log.w(TAG, "session ended", e)
                Status.set { it.copy(problem = describe(e)) }
            }
            session = null
            if (!alive) break
            if (config.enabled && screenOn) {
                govee.mode = if (testing()) Govee.Mode.TEST else Govee.Mode.CAMERA
                Status.set { it.copy(mode = "brak odczytu obrazu: steruje kamera Govee") }
                idle(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000)
            }
        }
    }

    /** Runs until the daemon stream ends or is closed (standby, settings change). */
    private fun runSession() {
        Status.set { it.copy(adb = "łączenie… (jeśli TV zapyta o debugowanie: „Zawsze zezwalaj” i OK)") }
        val connection = adb.connect()
        session = connection
        connection.use { d ->
            d.shell("true") // the handshake; the first time this waits for the TV's authorisation dialog
            Status.set { it.copy(adb = "połączono", problem = "") }
            adb.grantPermissions(d)
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            val mode = display.mode
            // One displayed frame plus margin; the session restarts when the refresh rate changes.
            val frameMs = kotlin.math.ceil(1000.0 / display.refreshRate).toInt() + 2
            d.open(adb.daemonCommand(mode.physicalWidth, mode.physicalHeight, config.sides, frameMs)).use { stream ->
                Status.set { it.copy(picture = "uruchamianie odczytu…") }
                while (alive && screenOn && config.enabled) {
                    val line = stream.source.readUtf8Line() ?: break
                    when {
                        line.startsWith("F ") -> onFrame(line)
                        line.startsWith("S ") -> Status.set { it.copy(picture = describeStats(line)) }
                        line.startsWith("E ") -> Status.set { it.copy(problem = line.substring(2)) }
                    }
                }
            }
        }
    }

    private fun onFrame(line: String) {
        val parts = line.split(' ')
        if (parts.size != Govee.SEGMENTS + 1) return
        val reverse = config.reverse
        val offset = config.offset
        val strip = IntArray(Govee.SEGMENTS)
        val edges = ArrayList<Int>(Govee.SEGMENTS)
        for (p in 0 until Govee.SEGMENTS) {
            val c = parts[p + 1].toInt(16)
            val s = smoothed[p]
            s[0] += ((c shr 16 and 0xff) - s[0]) * SMOOTHING
            s[1] += ((c shr 8 and 0xff) - s[1]) * SMOOTHING
            s[2] += ((c and 0xff) - s[2]) * SMOOTHING
            val out = enhance(s)
            edges += out
            val segment = ((if (reverse) Govee.SEGMENTS - 1 - p else p) + offset).mod(Govee.SEGMENTS)
            strip[segment] = out
        }
        govee.setTargets(strip)
        govee.mode = if (testing()) Govee.Mode.TEST else Govee.Mode.STREAM
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameLog > 10_000) {
            lastFrameLog = now
            Log.d(TAG, "frame in=${line.substring(2)} strip=${strip.joinToString(" ") { "%06x".format(it) }}")
        }
        if (now - lastPreview > 200) {
            lastPreview = now
            Status.set { it.copy(segments = edges, mode = if (testing()) "test segmentów" else "ambilight") }
        }
    }

    /** A little more saturation, and near-black stays off instead of glowing faintly. */
    private fun enhance(c: FloatArray): Int {
        val luma = 0.299f * c[0] + 0.587f * c[1] + 0.114f * c[2]
        if (maxOf(c[0], c[1], c[2]) < 6f) return 0
        fun ch(v: Float) = (luma + (v - luma) * SATURATION).toInt().coerceIn(0, 255)
        return (ch(c[0]) shl 16) or (ch(c[1]) shl 8) or ch(c[2])
    }

    private fun testing() = SystemClock.elapsedRealtime() < testUntil

    /** "S reads=47.5 failed=0 bars=0,276" -> "odczyt sprzętowy, 48 punktów/s, pasy filmowe pominięte" */
    private fun describeStats(line: String): String {
        val fields = line.substring(2).split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }
        val reads = fields["reads"]?.toFloatOrNull()?.let { Math.round(it) } ?: 0
        val bars = fields["bars"]?.split(',')?.any { it != "0" } == true
        val failed = fields["failed"]?.toIntOrNull() ?: 0
        return buildString {
            append("odczyt sprzętowy, $reads punktów/s")
            if (bars) append(", pasy filmowe pominięte")
            if (failed > 0) append(", $failed nieudanych odczytów")
        }
    }

    private fun restartSession() {
        runCatching { session?.close() }
        synchronized(wake) { wake.notifyAll() }
    }

    private fun idle(ms: Long) = synchronized(wake) { wake.wait(ms) }

    private fun describe(e: Exception): String {
        val msg = e.message ?: e.javaClass.simpleName
        return when {
            msg.contains("ECONNREFUSED") || msg.contains("Connection refused") ->
                "Debugowanie sieciowe (ADB na porcie 5555) jest wyłączone"
            else -> msg
        }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ambilight", "Ambilight", NotificationManager.IMPORTANCE_MIN))
        return Notification.Builder(this, "ambilight")
            .setContentTitle("Ambilight")
            .setContentText("Taśma Govee podąża za obrazem")
            .setSmallIcon(R.drawable.icon)
            .build()
    }

    companion object {
        const val ACTION_TEST = "local.ambilight.TEST"
        /** Per daemon line (~45/s, one new point each): about a 0.07 s fade towards each new colour. */
        private const val SMOOTHING = 0.3f
        private const val SATURATION = 1.25f
    }
}
