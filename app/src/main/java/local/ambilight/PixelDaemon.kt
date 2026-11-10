package local.ambilight

import java.util.Locale
import kotlin.concurrent.thread

/**
 * Runs as the shell user, started by AmbilightService over the TV's own ADB:
 *   CLASSPATH=<apk> app_process /system/bin local.ambilight.PixelDaemon <panelW> <panelH> <sides> <segments> <frameMs>
 *       [<pointsPerSegment>]
 *
 * It reads output pixels from the MediaTek render HAL (the call the TV's own ExtService uses), which sees the
 * final picture: apps, 4K video and HDMI inputs, without touching the GPU.
 *
 * The HAL latches the requested position once per displayed frame and returns the pixel of the position latched
 * at the last vsync, i.e. normally the one asked for in the previous call. So each point is asked for twice,
 * one frame apart: whichever latch the second answer comes from, it belongs to that point. That allows about one
 * point per frame, so the segments are read round-robin, one point each, and every new value is emitted.
 *
 * With several points per segment, each round reads the next of them, spread along the segment's edge; the
 * segment's colour is the mean of its last readings, so a small object over one point no longer recolours the
 * whole segment. A reading far from that mean (a cut) replaces all of them at once, so cuts stay fast.
 *
 * Input on stdin, one line at a time:
 *   R x y w h             read only this rectangle of the panel (the demo video while the app is open)
 *   R                     read the whole picture again
 *
 * Output on stdout:
 *   F z rrggbb rrggbb ... after each read: z is the segment just read (-1 if the read failed), then one colour
 *                         per segment along the strip path: left bottom->top, top left->right, right top->bottom,
 *                         and with 4 sides bottom right->left
 *   S <text>              stats, about once per second
 *   E <text>              a problem the service should show
 */
object PixelDaemon {
    /** Below this luma a sample counts as black when looking for letterbox bars. */
    private const val DARK = 24
    /** How often the black-bar probes run; they take one read per round, so they never stall the stream. */
    private const val BAR_CHECK_MS = 20_000L
    private const val HEX = "0123456789abcdef"
    /** At most this many points per segment. */
    private const val MAX_POINTS = 4
    /** A reading this far (luma, 0..255) from a segment's mean replaces its other readings: a cut. */
    private const val CUT_LUMA = 64

    private var controller = 0
    private var frameMs = 21L

    @JvmStatic
    fun main(args: Array<String>) {
        val w = args[0].toInt()
        val h = args[1].toInt()
        val sides = args[2].toInt()
        val segments = args[3].toInt()
        frameMs = args[4].toLong()
        val perSegment = args.getOrNull(5)?.toIntOrNull()?.coerceIn(1, MAX_POINTS) ?: 1
        thread(name = "stdin", isDaemon = true) { readCommands(w, h) }

        if (!RenderHal.connect()) {
            emit("E render HAL not found")
            return
        }
        // Wake on time after each one-frame sleep (init allows every process nice down to -20).
        runCatching { android.os.Process.setThreadPriority(-10) }
        controller = RenderHal.openController()
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { RenderHal.closeController(controller) } })
        try {
            loop(w, h, sides, segments, perSegment)
        } finally {
            runCatching { RenderHal.closeController(controller) }
        }
    }

    /** The rectangle to read instead of the whole picture, set from stdin; null: the whole picture. */
    @Volatile private var region: Rect? = null

    private fun readCommands(w: Int, h: Int) {
        val input = System.`in`.bufferedReader()
        while (true) {
            val line = input.readLine() ?: return
            if (line.startsWith("R")) region = parseRegion(line, w, h)
        }
    }

    /** "R x y w h" -> that rectangle, kept inside the panel; "R" or anything malformed -> null (whole picture). */
    internal fun parseRegion(line: String, w: Int, h: Int): Rect? {
        val f = line.trim().split(' ').drop(1).map { it.toIntOrNull() ?: return null }
        if (f.size != 4 || f[2] <= 0 || f[3] <= 0) return null
        val (x, y, rw, rh) = f
        val l = x.coerceIn(0, w - 1)
        val t = y.coerceIn(0, h - 1)
        return Rect(l, t, (x + rw).coerceIn(l + 1, w), (y + rh).coerceIn(t + 1, h))
    }

    private fun loop(w: Int, h: Int, sides: Int, segments: Int, perSegment: Int) {
        var bars = Bars(0, 0)
        var candidate = bars
        var area = region
        fun area() = area ?: Rect(bars.x, bars.y, w - bars.x, h - bars.y)
        var points = samplePoints(area(), w, h, sides, segments, perSegment)
        var round = 0
        val colours = IntArray(segments)
        val recent = Array(segments) { IntArray(perSegment) }
        val filled = IntArray(segments)
        val probePoints = barProbePoints(w, h)
        val probeSamples = IntArray(probePoints.size)
        var probeIdx = -1
        var nextBarCheck = System.currentTimeMillis() + BAR_CHECK_MS / 2
        var reads = 0
        var failed = 0
        var statsStart = System.nanoTime()
        val line = StringBuilder(8 * segments + 6)

        while (true) {
            if (region != area) {
                area = region
                points = samplePoints(area(), w, h, sides, segments, perSegment)
                filled.fill(0) // readings of the old area don't belong to the new one
            }
            val k = round++ % perSegment
            for (s in 0 until segments) {
                val px = read(points[s][k].x, points[s][k].y)
                if (px == null) {
                    failed++
                } else {
                    colours[s] = remember(recent[s], filled, s, k, px, colours[s])
                }
                reads++
                line.setLength(0)
                line.append("F ").append(if (px == null) -1 else s)
                for (c in colours) {
                    line.append(' ')
                    for (shift in 20 downTo 0 step 4) line.append(HEX[c shr shift and 0xf])
                }
                emit(line.toString())
                if (System.out.checkError()) return // the service closed the stream
            }

            // Amortize bar checks: probe at most 1 point per round so the frame stream never stalls
            if (probeIdx == -1 && area == null && System.currentTimeMillis() >= nextBarCheck) {
                probeIdx = 0
            }
            if (probeIdx in probePoints.indices) {
                val px = read(probePoints[probeIdx].x, probePoints[probeIdx].y)
                probeSamples[probeIdx] = px ?: 0
                probeIdx++
                if (probeIdx >= probePoints.size) {
                    val seen = evaluateBars(w, h, probeSamples) ?: bars
                    if (seen == candidate && seen != bars && area == null) {
                        bars = seen
                        points = samplePoints(area(), w, h, sides, segments, perSegment)
                        filled.fill(0)
                    }
                    candidate = seen
                    probeIdx = -1
                    nextBarCheck = System.currentTimeMillis() + BAR_CHECK_MS
                }
            }

            val now = System.nanoTime()
            if (now - statsStart >= 1_000_000_000L) {
                val secs = (now - statsStart) / 1e9
                emit(String.format(Locale.ROOT, "S reads=%.1f failed=%d bars=%d,%d", reads / secs, failed, bars.x, bars.y))
                if (failed > 0 && failed >= reads) emit("E pixel reads failing")
                reads = 0
                failed = 0
                statsStart = now
            }
        }
    }

    /** The pixel at one point: ask, wait one displayed frame, ask again and keep the second answer. */
    private fun read(x: Int, y: Int): Int? {
        RenderHal.pixel(controller, x, y)
        Thread.sleep(frameMs)
        return RenderHal.pixel(controller, x, y)
    }

    /**
     * Stores reading [px] as the [k]th of a segment's last readings and returns their mean. A reading far from
     * the mean (a cut, a fade) replaces all of them, so the segment jumps with the picture instead of lagging.
     */
    internal fun remember(recent: IntArray, filled: IntArray, s: Int, k: Int, px: Int, mean: Int): Int {
        if (filled[s] == 0 || kotlin.math.abs(luma(px) - luma(mean)) >= CUT_LUMA) {
            recent.fill(px)
            filled[s] = recent.size
            return px
        }
        recent[k] = px
        var r = 0; var g = 0; var b = 0
        for (c in recent) { r += c shr 16 and 0xff; g += c shr 8 and 0xff; b += c and 0xff }
        val n = recent.size
        return ((r + n / 2) / n shl 16) or ((g + n / 2) / n shl 8) or ((b + n / 2) / n)
    }

    private data class Point(val x: Int, val y: Int)

    /** A part of the panel, [l, r) x [t, b). */
    internal data class Rect(val l: Int, val t: Int, val r: Int, val b: Int)

    /** Width of pillarbox bars (x) and height of letterbox bars (y), in panel pixels. */
    private data class Bars(val x: Int, val y: Int)

    /**
     * [perSegment] points per segment inside [area] (the picture without black bars, or the demo video), spread
     * evenly along each segment's edge span: one in its middle, or several at alternating depths.
     */
    private fun samplePoints(area: Rect, w: Int, h: Int, sides: Int, segments: Int, perSegment: Int): List<List<Point>> {
        val l = area.l
        val t = area.t
        val r = area.r
        val btm = area.b
        val cw = r - l
        val ch = btm - t
        val depth = maxOf(8, minOf(cw, ch) / 20)
        // Keep bottom samples close to the bezel (16-24 px) to stay below subtitle safe-title margins
        val depthBottom = maxOf(8, minOf(24, depth / 4))
        val path = if (sides == 4) 2 * ch + 2 * cw else 2 * ch + cw
        return List(segments) { s ->
            List(perSegment) { k ->
                val along = (k + 0.5f) / perSegment
                // Every other point sits a little deeper, so the segment covers a band rather than a line.
                val inset = if (k % 2 == 1) 2 else 1
                val pos = ((s + along) / segments * path).toInt()
                val p = when {
                    pos < ch -> Point(l + depth * inset, btm - 1 - pos)
                    pos < ch + cw -> Point(l + (pos - ch), t + depth * inset)
                    pos < 2 * ch + cw -> Point(r - 1 - depth * inset, t + (pos - ch - cw))
                    else -> Point(r - 1 - (pos - 2 * ch - cw), btm - 1 - depthBottom * inset)
                }
                Point(p.x.coerceIn(0, w - 1), p.y.coerceIn(0, h - 1))
            }
        }
    }

    private fun barProbePoints(w: Int, h: Int): List<Point> = listOf(
        Point(w / 2, h / 2),
        Point(w / 3, h / 2),
        Point(w / 5, h * 6 / 100),
        Point(w / 5, h * 94 / 100),
        Point(w / 5, h * 16 / 100),
        Point(w * 5 / 100, h / 2),
        Point(w * 95 / 100, h / 2)
    )

    private fun evaluateBars(w: Int, h: Int, samples: IntArray): Bars? {
        if (luma(samples[0]) <= DARK && luma(samples[1]) <= DARK) return null
        val topDark = luma(samples[2]) <= DARK && luma(samples[3]) <= DARK
        val scope = topDark && luma(samples[4]) > DARK
        val sideDark = luma(samples[5]) <= DARK && luma(samples[6]) <= DARK
        return Bars(if (sideDark) w * 125 / 1000 else 0, if (scope) h * 128 / 1000 else if (topDark) h * 6 / 100 else 0)
    }

    private fun luma(px: Int?): Int {
        if (px == null) return 0
        return ((px shr 16 and 0xff) * 2 + (px shr 8 and 0xff) * 5 + (px and 0xff)) / 8
    }

    private fun emit(line: String) {
        System.out.println(line)
        System.out.flush()
    }
}
