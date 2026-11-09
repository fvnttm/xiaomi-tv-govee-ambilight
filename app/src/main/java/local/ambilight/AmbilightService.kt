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
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Display
import dadb.Dadb
import kotlin.concurrent.thread

const val TAG = "Ambilight"

/** Zones read round the picture edge; each LED is interpolated between the two nearest zones. */
const val SEGMENTS = 14

/**
 * Starts with the TV and keeps the strip following the picture:
 * PixelDaemon (shell, over the TV's own ADB) -> smoothing -> WLED on an ESP32 (DDP over UDP).
 * With the TV in standby, with Ambilight switched off, or when the picture can't be read, the strip is switched off.
 */
class AmbilightService : Service() {
    private lateinit var config: Config
    private lateinit var adb: AdbBridge
    private lateinit var ddp: Ddp

    @Volatile private var alive = true
    @Volatile private var screenOn = true
    @Volatile private var session: Dadb? = null
    @Volatile private var testUntil = 0L
    private val wake = java.lang.Object()

    private var lastPreview = 0L
    private var lastFrameLog = 0L

    /** Zone colours over time; fed by the session thread, read by the render thread. */
    private val engine = ColourEngine(SEGMENTS)
    @Volatile private var rendering = false

    /** Black frame and WLED off, so the strip doesn't fall back to its own preset when the stream stops. */
    private fun blackout() {
        engine.reset()
        ddp.sendBlack()
        ddp.setOff()
    }

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
        ddp = Ddp(config)
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        screenOn = getSystemService(PowerManager::class.java).isInteractive
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        config.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, null)
        thread(name = "supervisor", isDaemon = true) { supervise() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TEST) {
            testUntil = SystemClock.elapsedRealtime() + 60_000
            // A running session shows the test from onFrame; otherwise wake the supervisor to show it.
            synchronized(wake) { wake.notifyAll() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        config.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        unregisterReceiver(screenReceiver)
        restartSession()
        // Network calls are not allowed on the main thread.
        thread(name = "shutdown") {
            ddp.sendBlack()
            ddp.setOff()
            ddp.close()
        }
        super.onDestroy()
    }

    private fun supervise() {
        // This thread also reads the daemon's stream: it must not wait behind the UI or background work.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
        var backoff = 2_000L
        while (alive) {
            when {
                !config.enabled -> {
                    blackout()
                    Status.set { it.copy(mode = "wyłączony") }
                    while (alive && !config.enabled) {
                        idle(60_000)
                    }
                    continue
                }
                !screenOn -> {
                    blackout()
                    Status.set { it.copy(mode = "TV w czuwaniu") }
                    while (alive && !screenOn) {
                        idle(60_000)
                    }
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
                // Wait before reconnecting. The test pattern is resent every second, because WLED leaves
                // realtime mode a few seconds after the last packet.
                val retryAt = SystemClock.elapsedRealtime() + backoff
                var dark = false
                var testShown = false
                while (alive && config.enabled && screenOn) {
                    val left = retryAt - SystemClock.elapsedRealtime()
                    if (left <= 0) break
                    if (testing()) {
                        if (!testShown) ddp.setOn()
                        ddp.sendTest(config.reverse, config.offset)
                        Status.set { it.copy(mode = "test segmentów") }
                        testShown = true
                        dark = false
                        idle(minOf(left, 1_000))
                    } else {
                        if (!dark) blackout()
                        dark = true
                        testShown = false
                        Status.set { it.copy(mode = "brak odczytu obrazu: taśma wyłączona") }
                        idle(left)
                    }
                }
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
            ddp.setOn()
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            val mode = display.mode
            // One displayed frame plus a margin: the HAL latches the position once per vsync, and asking again
            // before the next vsync returns the previous point (measured; without the margin colours flicker).
            // The session restarts when the refresh rate changes.
            val frameMs = kotlin.math.ceil(1000.0 / display.refreshRate).toInt() + 2
            d.open(adb.daemonCommand(mode.physicalWidth, mode.physicalHeight, config.sides, frameMs)).use { stream ->
                Status.set { it.copy(picture = "uruchamianie odczytu…") }
                engine.reset()
                rendering = true
                val renderer = thread(name = "render") { render() }
                try {
                    while (alive && screenOn && config.enabled) {
                        val line = stream.source.readUtf8Line() ?: break
                        when {
                            line.startsWith("F ") -> onFrame(line)
                            line.startsWith("S ") -> Status.set { it.copy(picture = describeStats(line)) }
                            line.startsWith("E ") -> Status.set { it.copy(problem = line.substring(2)) }
                        }
                    }
                } finally {
                    rendering = false
                    renderer.join(500)
                }
            }
        }
    }

    /** "F z c0 c1 …": zone z was just read (-1: the read failed); c0… are the latest colours of all zones. */
    private fun onFrame(line: String) {
        val parts = line.split(' ')
        if (parts.size != SEGMENTS + 2) return
        val zone = parts[1].toIntOrNull() ?: return
        val raw = IntArray(SEGMENTS) { p -> parts[p + 2].toInt(16) }
        LatencyProbe.onRead(raw)
        if (zone !in 0 until SEGMENTS) return
        if (config.filterSubtitles && config.sides == 4) filterSubtitles(raw)
        engine.onReading(zone, enhance(raw[zone]), SystemClock.elapsedRealtime())

        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameLog > 10_000) {
            lastFrameLog = now
            Log.d(TAG, "frame in=${line.substring(2)}")
        }
    }

    /**
     * Sends the strip a frame every 1/120 s while a session runs: zone colours at this moment, interpolated for
     * every LED, through the LED curve. Runs on its own clock, so transitions keep an even pace whatever the
     * rhythm of the readings.
     */
    private fun render() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
        val zoneColours = Array(SEGMENTS) { FloatArray(3) }
        var next = System.nanoTime()
        while (rendering) {
            val now = SystemClock.elapsedRealtime()
            if (testing()) {
                ddp.sendTest(config.reverse, config.offset)
            } else {
                engine.current(now, zoneColours)
                ddp.sendFrame(leds(zoneColours))
                LatencyProbe.onOutput(
                    zoneColours.sumOf { maxOf(it[0], it[1], it[2]).toDouble() }.toFloat() / SEGMENTS / 255f,
                )
            }
            if (now - lastPreview > 200) {
                lastPreview = now
                val edges = zoneColours.map { c ->
                    (Math.round(c[0]) shl 16) or (Math.round(c[1]) shl 8) or Math.round(c[2])
                }
                Status.set { it.copy(segments = edges, mode = if (testing()) "test segmentów" else "ambilight") }
            }
            next += FRAME_NS
            val wait = next - System.nanoTime()
            if (wait > 0) java.util.concurrent.locks.LockSupport.parkNanos(wait) else next = System.nanoTime()
        }
    }

    /** Smooth (Hermite) interpolation between the two nearest zones for every LED, then the LED curve. */
    private fun leds(zoneColours: Array<FloatArray>): IntArray {
        val n = config.ledCount
        val reverse = config.reverse
        val offset = config.offset.mod(n)
        val out = IntArray(n)
        for (i in 0 until n) {
            val u = ((i + 0.5f) / n * SEGMENTS - 0.5f).mod(SEGMENTS.toFloat())
            val i0 = u.toInt().mod(SEGMENTS)
            val i1 = (i0 + 1) % SEGMENTS
            val f = u - u.toInt()
            val t = f * f * (3f - 2f * f)
            fun ch(c: Int) = ColourEngine.OUTPUT[
                Math.round(zoneColours[i0][c] * (1f - t) + zoneColours[i1][c] * t).coerceIn(0, 255)
            ]
            out[((if (reverse) n - 1 - i else i) + offset).mod(n)] = (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
        }
        return out
    }

    private fun blend2(c1: Int, c2: Int, w1: Float): Int {
        val w2 = 1.0f - w1
        val r = (((c1 shr 16 and 0xff) * w1) + ((c2 shr 16 and 0xff) * w2)).toInt().coerceIn(0, 255)
        val g = (((c1 shr 8 and 0xff) * w1) + ((c2 shr 8 and 0xff) * w2)).toInt().coerceIn(0, 255)
        val b = (((c1 and 0xff) * w1) + ((c2 and 0xff) * w2)).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    private var lastSubLog = 0L

    /**
     * Detects when movie/series subtitles light up bottom-center segments (11 and 12)
     * and replaces them with an interpolation of the surrounding bottom background (10 and 13).
     */
    private fun filterSubtitles(raw: IntArray) {
        if (raw.size != 14) return

        // Top segments: 3, 4, 5, 6
        val topLuma = (lumaOf(raw[3]) + lumaOf(raw[4]) + lumaOf(raw[5]) + lumaOf(raw[6])) / 4f
        // High-key scene (bright daylight / snow / white flash): do not suppress
        if (topLuma > 180f) return

        val c10 = raw[10]
        val c13 = raw[13]
        val bgLuma = maxOf(lumaOf(c10), lumaOf(c13))

        val isSub11 = isSubtitle(raw[11], bgLuma)
        val isSub12 = isSubtitle(raw[12], bgLuma)

        if (isSub11 && isSub12) {
            raw[11] = blend2(c10, c13, 0.67f)
            raw[12] = blend2(c10, c13, 0.33f)
        } else if (isSub11) {
            raw[11] = blend2(c10, raw[12], 0.60f)
        } else if (isSub12) {
            raw[12] = blend2(c13, raw[11], 0.60f)
        }

        if (isSub11 || isSub12) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSubLog > 5_000) {
                lastSubLog = now
                Log.d(TAG, "subtitle suppressed: s11=$isSub11 s12=$isSub12 (bgLuma=${bgLuma.toInt()})")
            }
        }
    }

    private fun isSubtitle(rgb: Int, bgLuma: Float): Boolean {
        val r = (rgb shr 16) and 0xff
        val g = (rgb shr 8) and 0xff
        val b = rgb and 0xff
        val l = 0.299f * r + 0.587f * g + 0.114f * b

        // Subtitles are bright text: must be at least luma 110
        if (l < 110f) return false

        // Contrast test: subtitle must stand out significantly against the background scene
        if (l < bgLuma * 1.4f + 35f) return false

        // Color test:
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)

        // White / grey subtitles: low color saturation
        val isWhite = (maxC - minC) <= 40

        // Yellow subtitles (e.g. Prime Video or user preference): high R & G, lower B
        val isYellow = r >= 150 && g >= 130 && b <= 110 && (r - b) >= 45 && kotlin.math.abs(r - g) <= 45

        return isWhite || isYellow
    }

    private fun lumaOf(rgb: Int): Float {
        val r = (rgb shr 16) and 0xff
        val g = (rgb shr 8) and 0xff
        val b = rgb and 0xff
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    /** A little more saturation; very dark colours keep theirs, so noise in black doesn't turn colourful. */
    private fun enhance(rgb: Int): Int {
        val r = (rgb shr 16 and 0xff).toFloat()
        val g = (rgb shr 8 and 0xff).toFloat()
        val b = (rgb and 0xff).toFloat()
        val maxCh = maxOf(r, g, b)
        val sat = 1f + (SATURATION - 1f) * ((maxCh - 8f) / 24f).coerceIn(0f, 1f)
        val luma = 0.299f * r + 0.587f * g + 0.114f * b
        fun ch(v: Float) = (luma + (v - luma) * sat).toInt().coerceIn(0, 255)
        return (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
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
            .setContentText("Taśma LED podąża za obrazem")
            .setSmallIcon(R.drawable.icon)
            .build()
    }

    companion object {
        const val ACTION_TEST = "local.ambilight.TEST"
        private const val SATURATION = 1.25f

        /** The strip gets a frame every 1/120 s: a new reading waits 4 ms on average for the next packet. */
        private const val FRAME_NS = 8_333_333L
    }
}
