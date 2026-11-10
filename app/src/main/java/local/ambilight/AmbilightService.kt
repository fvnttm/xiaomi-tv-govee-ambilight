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
    @Volatile private lateinit var settings: Settings

    private val ddp = Ddp { ip, reachable ->
        val s = settings
        if (ip == s.espIp) Status.set {
            it.copy(strip = if (reachable) "${s.espName.ifEmpty { "WLED" }} · $ip · ${s.ledCount} diod" else "WLED nie odpowiada pod $ip")
        }
    }

    @Volatile private var alive = true
    @Volatile private var screenOn = true
    @Volatile private var session: Dadb? = null
    @Volatile private var testUntil = 0L
    private val wake = java.lang.Object()

    private var lastFrameLog = 0L
    private var lastSubtitleLog = 0L

    /** Zone colours over time; fed by the session thread, read by the render thread. */
    private val engine = ColourEngine(SEGMENTS)
    @Volatile private var rendering = false

    /** Black frame and WLED off, so the strip doesn't fall back to its own preset when the stream stops. */
    private fun blackout() {
        engine.reset()
        ddp.sendBlack(settings.ledCount)
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

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val old = settings
        val new = config.read()
        settings = new
        engine.setSmoothing(new.smoothing)
        if (new.espIp != old.espIp) {
            ddp.ip = new.espIp
            if (session != null) ddp.setOn()
        }
        if (new.enabled != old.enabled || new.sides != old.sides) restartSession()
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        config = Config(this)
        adb = AdbBridge(this)
        settings = config.read()
        engine.setSmoothing(settings.smoothing)
        ddp.ip = settings.espIp
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
            // Starts the segment test, or ends one that is running.
            testUntil = if (testing()) 0 else SystemClock.elapsedRealtime() + 60_000
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
            ddp.sendBlack(settings.ledCount)
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
                !settings.enabled -> {
                    blackout()
                    Status.set { it.copy(mode = "wyłączony") }
                    while (alive && !settings.enabled) {
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
            if (settings.enabled && screenOn) {
                // Wait before reconnecting. The test pattern is resent every second, because WLED leaves
                // realtime mode a few seconds after the last packet.
                val retryAt = SystemClock.elapsedRealtime() + backoff
                var dark = false
                var testShown = false
                while (alive && settings.enabled && screenOn) {
                    val left = retryAt - SystemClock.elapsedRealtime()
                    if (left <= 0) break
                    if (testing()) {
                        if (!testShown) ddp.setOn()
                        val s = settings
                        ddp.sendTest(s.ledCount, s.reverse, s.offset)
                        Status.set { it.copy(mode = "test segmentów", testing = true) }
                        testShown = true
                        dark = false
                        idle(minOf(left, 200))
                    } else {
                        if (!dark) blackout()
                        dark = true
                        testShown = false
                        Status.set { it.copy(mode = "brak odczytu obrazu, taśma wyłączona", testing = false) }
                        idle(left)
                    }
                }
                backoff = (backoff * 2).coerceAtMost(30_000)
            }
        }
    }

    /** Runs until the daemon stream ends or is closed (standby, settings change). */
    private fun runSession() {
        Status.set { it.copy(adb = "łączenie… Gdy TV zapyta o debugowanie: „Zawsze zezwalaj” i OK") }
        val connection = adb.connect()
        session = connection
        connection.use { d ->
            // The handshake; the first time this waits for the TV's authorisation dialog. If the dialog is
            // dismissed or times out, adbd never answers: give up after a while, so the retry asks again.
            var authorised = false
            thread(name = "adb-auth-timeout", isDaemon = true) {
                Thread.sleep(AUTH_TIMEOUT_MS)
                if (!authorised) runCatching { d.close() }
            }
            d.shell("true")
            authorised = true
            Status.set { it.copy(adb = "połączono", problem = "") }
            adb.grantPermissions(d)
            ddp.setOn()
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            val mode = display.mode
            // One displayed frame plus a margin: the HAL latches the position once per vsync, and asking again
            // before the next vsync returns the previous point (measured; without the margin colours flicker).
            // The session restarts when the refresh rate changes.
            val frameMs = kotlin.math.ceil(1000.0 / display.refreshRate).toInt() + 2
            d.open(adb.daemonCommand(mode.physicalWidth, mode.physicalHeight, settings.sides, frameMs)).use { stream ->
                Status.set { it.copy(picture = "uruchamianie odczytu…") }
                engine.reset()
                rendering = true
                val renderer = thread(name = "render") { render() }
                try {
                    while (alive && screenOn && settings.enabled) {
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
                    Status.set { it.copy(testing = false) }
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
        val now = SystemClock.elapsedRealtime()
        val s = settings
        if (s.filterSubtitles && s.sides == 4 && Picture.filterSubtitles(raw) && now - lastSubtitleLog > 5_000) {
            lastSubtitleLog = now
            Log.d(TAG, "subtitles suppressed")
        }
        engine.onReading(zone, Picture.enhance(raw[zone]), now)

        if (now - lastFrameLog > 10_000) {
            lastFrameLog = now
            Log.d(TAG, "frame in=${line.substring(2)}")
        }
    }

    /**
     * Sends the strip a frame every 1/120 s while a session runs: zone colours at this moment, interpolated for
     * every LED, through the LED curve. Runs on its own clock, so transitions keep an even pace whatever the
     * rhythm of the readings. Ddp drops frames that repeat the last one.
     */
    private fun render() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
        val zoneColours = Array(SEGMENTS) { FloatArray(3) }
        var leds = IntArray(0)
        val dither = Picture.Dither()
        var lastPreview = 0L
        var next = System.nanoTime()
        while (rendering) {
            val now = SystemClock.elapsedRealtime()
            val s = settings
            val testing = testing()
            if (testing) {
                ddp.sendTest(s.ledCount, s.reverse, s.offset)
            } else {
                if (leds.size != s.ledCount) leds = IntArray(s.ledCount)
                val moving = engine.current(now, zoneColours)
                // Dither only while colours move: a still picture then gives identical frames, which Ddp drops.
                Picture.leds(zoneColours, leds, s.reverse, s.offset.mod(s.ledCount), s.brightness, dither.takeIf { moving })
                ddp.sendFrame(leds)
                LatencyProbe.onOutput(
                    zoneColours.sumOf { maxOf(it[0], it[1], it[2]).toDouble() }.toFloat() / SEGMENTS / 255f,
                )
            }
            if (now - lastPreview > PREVIEW_MS) {
                lastPreview = now
                val mode = if (testing) "test segmentów" else "ambilight"
                val old = Status.state.value
                // The preview colours matter only while the screen is open.
                if (Status.state.subscriptionCount.value > 0) {
                    val edges = zoneColours.map { c ->
                        (Math.round(c[0]) shl 16) or (Math.round(c[1]) shl 8) or Math.round(c[2])
                    }
                    Status.set { it.copy(segments = edges, mode = mode, testing = testing) }
                } else if (old.mode != mode || old.testing != testing) {
                    Status.set { it.copy(mode = mode, testing = testing) }
                }
            }
            next += FRAME_NS
            val wait = next - System.nanoTime()
            if (wait > 0) java.util.concurrent.locks.LockSupport.parkNanos(wait) else next = System.nanoTime()
        }
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
        /** How often the preview on the screen gets new colours. */
        private const val PREVIEW_MS = 100L
        /** A little longer than the TV keeps its debugging dialog up (30 s). */
        private const val AUTH_TIMEOUT_MS = 35_000L

        /** The strip gets a frame every 1/120 s: a new reading waits 4 ms on average for the next packet. */
        private const val FRAME_NS = 8_333_333L
    }
}
