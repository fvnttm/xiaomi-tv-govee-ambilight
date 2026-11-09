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
    private lateinit var ddp: Ddp

    @Volatile private var alive = true
    @Volatile private var screenOn = true
    @Volatile private var session: Dadb? = null
    @Volatile private var testUntil = 0L
    private val wake = java.lang.Object()

    private var lastPreview = 0L
    private var lastFrameLog = 0L

    private val smoothR = FloatArray(Govee.SEGMENTS)
    private val smoothG = FloatArray(Govee.SEGMENTS)
    private val smoothB = FloatArray(Govee.SEGMENTS)

    private fun updateChannel(current: Float, target: Float): Float {
        val diff = target - current
        val absDiff = kotlin.math.abs(diff)
        if (absDiff < 1.0f) return target
        // Instant response on scene cuts and fast dynamic shifts:
        if (absDiff >= 60f) return target
        // Ultra-fast responsive tracking on continuous motion:
        val rate = if (diff > 0f) 0.85f else 0.75f
        return current + diff * rate
    }

    private fun resetSmoothing() {
        smoothR.fill(0f)
        smoothG.fill(0f)
        smoothB.fill(0f)
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
            if (session == null) ddp.sendTest(config.reverse, config.offset)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        config.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        unregisterReceiver(screenReceiver)
        restartSession()
        resetSmoothing()
        ddp.sendBlack()
        ddp.setOff()
        ddp.close()
        super.onDestroy()
    }

    private fun supervise() {
        var backoff = 2_000L
        while (alive) {
            when {
                !config.enabled -> {
                    resetSmoothing()
                    ddp.sendBlack()
                    ddp.setOff()
                    Status.set { it.copy(mode = "wyłączony") }
                    while (alive && !config.enabled) {
                        idle(60_000)
                    }
                    continue
                }
                !screenOn -> {
                    resetSmoothing()
                    ddp.sendBlack()
                    ddp.setOff()
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
                if (testing()) {
                    ddp.sendTest(config.reverse, config.offset)
                    Status.set { it.copy(mode = "test segmentów") }
                } else {
                    resetSmoothing()
                    ddp.sendBlack()
                    ddp.setOff()
                    Status.set { it.copy(mode = "brak odczytu obrazu") }
                }
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
            ddp.setOn()
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            val mode = display.mode
            // Frame duration rounded up to vsync boundary
            val frameMs = kotlin.math.ceil(1000.0 / display.refreshRate).toInt()
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

        val rawIn = IntArray(Govee.SEGMENTS) { p -> parts[p + 1].toInt(16) }
        if (config.filterSubtitles && config.sides == 4) {
            filterSubtitles(rawIn)
        }

        val raw = IntArray(Govee.SEGMENTS) { p -> enhance(rawIn[p]) }

        // Continuous temporal easing on all 14 segment control points simultaneously:
        for (p in 0 until Govee.SEGMENTS) {
            val tr = (raw[p] shr 16 and 0xff).toFloat()
            val tg = (raw[p] shr 8 and 0xff).toFloat()
            val tb = (raw[p] and 0xff).toFloat()

            smoothR[p] = updateChannel(smoothR[p], tr)
            smoothG[p] = updateChannel(smoothG[p], tg)
            smoothB[p] = updateChannel(smoothB[p], tb)
        }

        val smoothedRaw = IntArray(Govee.SEGMENTS) { p ->
            (Math.round(smoothR[p]).coerceIn(0, 255) shl 16) or
            (Math.round(smoothG[p]).coerceIn(0, 255) shl 8) or
            Math.round(smoothB[p]).coerceIn(0, 255)
        }

        // 3-tap spatial halo diffusion across neighboring segments for TV preview:
        val edges = ArrayList<Int>(Govee.SEGMENTS)
        for (p in 0 until Govee.SEGMENTS) {
            val prev = smoothedRaw[(p - 1 + Govee.SEGMENTS) % Govee.SEGMENTS]
            val curr = smoothedRaw[p]
            val next = smoothedRaw[(p + 1) % Govee.SEGMENTS]
            edges += blend3(prev, curr, next, 0.08f, 0.84f, 0.08f)
        }

        val sCount = Govee.SEGMENTS
        val nLeds = config.ledCount
        val leds = IntArray(nLeds)
        val offsetLeds = offset.mod(nLeds)

        if (testing()) {
            ddp.sendTest(reverse, offset)
        } else {
            // High-resolution spatial Hermite interpolation directly from floating-point smooth buffers
            for (i in 0 until nLeds) {
                val u = (i.toFloat() / nLeds) * sCount
                val i0 = Math.floor(u.toDouble()).toInt().mod(sCount)
                val i1 = (i0 + 1).mod(sCount)
                val frac = (u - Math.floor(u.toDouble())).toFloat()
                val t = frac * frac * (3f - 2f * frac)
                val w1 = 1f - t
                val w2 = t

                val r = Math.round(smoothR[i0] * w1 + smoothR[i1] * w2).coerceIn(0, 255)
                val g = Math.round(smoothG[i0] * w1 + smoothG[i1] * w2).coerceIn(0, 255)
                val b = Math.round(smoothB[i0] * w1 + smoothB[i1] * w2).coerceIn(0, 255)
                val col = (r shl 16) or (g shl 8) or b

                val targetIdx = ((if (reverse) nLeds - 1 - i else i) + offsetLeds).mod(nLeds)
                leds[targetIdx] = col
            }
            ddp.sendFrame(leds)
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameLog > 10_000) {
            lastFrameLog = now
            Log.d(TAG, "frame in=${line.substring(2)} leds=$nLeds ddp=ok")
        }
        if (now - lastPreview > 200) {
            lastPreview = now
            Status.set { it.copy(segments = edges, mode = if (testing()) "test segmentów" else "ambilight") }
        }
    }

    private fun blend3(c1: Int, c2: Int, c3: Int, w1: Float, w2: Float, w3: Float): Int {
        val r = ((c1 shr 16 and 0xff) * w1 + (c2 shr 16 and 0xff) * w2 + (c3 shr 16 and 0xff) * w3).toInt().coerceIn(0, 255)
        val g = ((c1 shr 8 and 0xff) * w1 + (c2 shr 8 and 0xff) * w2 + (c3 shr 8 and 0xff) * w3).toInt().coerceIn(0, 255)
        val b = ((c1 and 0xff) * w1 + (c2 and 0xff) * w2 + (c3 and 0xff) * w3).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
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

    /** Enhances saturation and applies a perceptual shadow lift so ambient zones cast a rich glow. */
    private fun enhance(rgb: Int): Int {
        val r = (rgb shr 16 and 0xff).toFloat()
        val g = (rgb shr 8 and 0xff).toFloat()
        val b = (rgb and 0xff).toFloat()
        val maxCh = maxOf(r, g, b)
        if (maxCh < 2.0f) return 0 // Clean black noise gate

        // For dark pixels (maxCh < 10), preserve linear channels exactly to avoid crushing low levels.
        // Above 10, smoothly ramp saturation up to SATURATION.
        val sat = if (maxCh < 10f) {
            1.0f + (SATURATION - 1.0f) * ((maxCh - 2f) / 8f).coerceIn(0f, 1f)
        } else {
            SATURATION
        }
        val luma = 0.299f * r + 0.587f * g + 0.114f * b
        fun ch(v: Float): Int {
            val satVal = (luma + (v - luma) * sat).toInt().coerceIn(0, 255)
            return BOOST_LUT[satVal]
        }
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
            .setContentText("Taśma Govee podąża za obrazem")
            .setSmallIcon(R.drawable.icon)
            .build()
    }

    companion object {
        const val ACTION_TEST = "local.ambilight.TEST"
        private const val SATURATION = 1.25f

        /** Perceptual gamma 0.78 curve to lift ambient shadows and boost overall brightness. */
        private val BOOST_LUT = IntArray(256) { i ->
            if (i < 2) 0
            else {
                val norm = i / 255.0
                val boosted = Math.pow(norm, 0.78) * 255.0
                Math.round(boosted).toInt().coerceIn(0, 255)
            }
        }
    }
}
