package local.ambilight

import android.os.IBinder
import android.os.Parcel
import java.util.Locale

/**
 * Runs as the shell user, started by AmbilightService over the TV's own ADB:
 *   CLASSPATH=<apk> app_process /system/bin local.ambilight.PixelDaemon <panelW> <panelH> <sides> <segments> <frameMs>
 *
 * It reads output pixels from the MediaTek render HAL (the call the TV's own ExtService uses), which sees the
 * final picture: apps, 4K video and HDMI inputs, without touching the GPU.
 *
 * The HAL latches the requested position once per displayed frame and returns the pixel of the position latched
 * at the last vsync, i.e. normally the one asked for in the previous call. So each point is asked for twice,
 * one frame apart: whichever latch the second answer comes from, it belongs to that point. That allows about one
 * point per frame, so the segments are read round-robin, one point each, and every new value is emitted.
 *
 * Output on stdout:
 *   F rrggbb rrggbb ...   one colour per segment along the strip path:
 *                         left bottom->top, top left->right, right top->bottom, and with 4 sides bottom right->left
 *   S <text>              stats, about once per second
 *   E <text>              a problem the service should show
 */
object PixelDaemon {
    private const val DESC = "vendor.mediatek.hardware.render.IRender"
    private const val TX_CLOSE_CONTROLLER = 5
    private const val TX_GET_PIXEL_INFO = 49
    private const val TX_OPEN_CONTROLLER = 69

    /** Below this luma a sample counts as black when looking for letterbox bars. */
    private const val DARK = 24
    private const val BAR_CHECK_MS = 5_000L
    /** Spots read in turn along each segment's stretch of edge; also the length of the averaging window. */
    private const val SPOTS = 3
    /** Two reads this close agree... */
    private const val AGREE = 40
    /** ...and a third this far from them is treated as a stray read and dropped. */
    private const val OUTLIER = 80
    /** A spot whose colour moved this much since its last read has changed, not just been sampled elsewhere. */
    private const val JUMP = 60
    /** This many other changed spots within the last round mean a scene change. */
    private const val SCENE_JUMPS = 2

    private lateinit var hal: IBinder
    private var controller = 0
    private var frameMs = 21L

    @JvmStatic
    fun main(args: Array<String>) {
        val w = args[0].toInt()
        val h = args[1].toInt()
        val sides = args[2].toInt()
        val segments = args[3].toInt()
        frameMs = args[4].toLong()

        val service = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "$DESC/default") as IBinder?
        if (service == null) {
            emit("E render HAL not found")
            return
        }
        hal = service
        controller = openController()
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { closeController() } })
        try {
            loop(w, h, sides, segments)
        } finally {
            runCatching { closeController() }
        }
    }

    private fun loop(w: Int, h: Int, sides: Int, segments: Int) {
        var bars = Bars(0, 0)
        var candidate = bars
        var round = 0
        var points = samplePoints(w, h, bars, sides, segments, round)
        // The last SPOTS reads of each segment, one per spot, so on a still picture the window always holds the
        // same spots and the result cannot alternate. A read that disagrees with the other two is dropped.
        val history = Array(segments) { IntArray(SPOTS) { -1 } }
        val colours = IntArray(segments)
        var nextBarCheck = System.currentTimeMillis() + BAR_CHECK_MS
        var reads = 0
        var failed = 0
        var statsStart = System.nanoTime()

        // Read indexes at which a spot changed a lot since its previous read: several of those close together
        // mean the scene changed, and then segments take the new colour at once instead of averaging into it.
        val jumps = ArrayDeque<Int>()
        var readIndex = 0

        while (true) {
            for (s in 0 until segments) {
                val px = read(points[s].x, points[s].y)
                readIndex++
                if (px == null) {
                    failed++
                } else {
                    val slot = round % SPOTS
                    val old = history[s][slot]
                    while (jumps.isNotEmpty() && readIndex - jumps.first() > segments) jumps.removeFirst()
                    if (old >= 0 && distance(px, old) > JUMP) {
                        if (jumps.size >= SCENE_JUMPS) history[s].fill(px) else history[s][slot] = px
                        jumps.addLast(readIndex)
                    } else {
                        history[s][slot] = px
                    }
                    colours[s] = combine(history[s])
                }
                reads++
                emit(buildString(8 * segments + 2) {
                    append('F')
                    for (c in colours) append(' ').append(String.format(Locale.ROOT, "%06x", c))
                })
                if (System.out.checkError()) return // the service closed the stream
            }
            // Each round samples the next spot of every segment's stretch of edge.
            round++

            if (System.currentTimeMillis() >= nextBarCheck) {
                // A new bar layout must be seen twice in a row before the sampling moves.
                val seen = detectBars(w, h) ?: bars
                if (seen == candidate && seen != bars) bars = seen
                candidate = seen
                nextBarCheck = System.currentTimeMillis() + BAR_CHECK_MS
            }
            points = samplePoints(w, h, bars, sides, segments, round)

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
        pixel(x, y)
        Thread.sleep(frameMs)
        return pixel(x, y)
    }

    private data class Point(val x: Int, val y: Int)

    /** Width of pillarbox bars (x) and height of letterbox bars (y), in panel pixels. */
    private data class Bars(val x: Int, val y: Int)

    /** Mean of the filled slots (-1 = empty); with three reads, one far from two that agree is left out. */
    private fun combine(h: IntArray): Int {
        var reads = h.filter { it >= 0 }
        if (reads.isEmpty()) return 0
        if (reads.size == 3) {
            val pairs = listOf(Pair(0, 1), Pair(0, 2), Pair(1, 2))
            val (a, b) = pairs.minBy { (i, j) -> distance(reads[i], reads[j]) }
            val odd = 3 - a - b
            if (distance(reads[a], reads[b]) <= AGREE && distance(reads[odd], reads[a]) > OUTLIER) reads = listOf(reads[a], reads[b])
        }
        fun mean(shift: Int) = reads.sumOf { it shr shift and 0xff } / reads.size
        return (mean(16) shl 16) or (mean(8) shl 8) or mean(0)
    }

    private fun distance(a: Int, b: Int) = maxOf(
        kotlin.math.abs((a shr 16 and 0xff) - (b shr 16 and 0xff)),
        kotlin.math.abs((a shr 8 and 0xff) - (b shr 8 and 0xff)),
        kotlin.math.abs((a and 0xff) - (b and 0xff)),
    )

    /**
     * One sample per segment, inside any black bars. Each segment visits SPOTS spots along its stretch of
     * edge in turn, so together the reads cover the area the segment stands for.
     */
    private fun samplePoints(w: Int, h: Int, bars: Bars, sides: Int, segments: Int, round: Int): List<Point> {
        val l = bars.x
        val t = bars.y
        val r = w - bars.x
        val btm = h - bars.y
        val cw = r - l
        val ch = btm - t
        val depth = maxOf(8, minOf(cw, ch) / 20)
        val along = ((round % SPOTS) + 0.5) / SPOTS
        val path = if (sides == 4) 2 * ch + 2 * cw else 2 * ch + cw
        return List(segments) { s ->
            val pos = ((s + along) / segments * path).toInt()
            val p = when {
                pos < ch -> Point(l + depth, btm - 1 - pos)
                pos < ch + cw -> Point(l + (pos - ch), t + depth)
                pos < 2 * ch + cw -> Point(r - 1 - depth, t + (pos - ch - cw))
                else -> Point(r - 1 - (pos - 2 * ch - cw), btm - 1 - depth)
            }
            Point(p.x.coerceIn(0, w - 1), p.y.coerceIn(0, h - 1))
        }
    }

    /**
     * Looks for black bars with a few probes (each costs a frame): 2.39:1 films leave ~12.8 % of the height
     * black at top and bottom, 4:3 pictures ~12.5 % of the width at the sides. Null when the picture is too
     * dark to tell.
     */
    private fun detectBars(w: Int, h: Int): Bars? {
        if (luma(read(w / 2, h / 2)) <= DARK && luma(read(w / 3, h / 2)) <= DARK) return null
        val topDark = luma(read(w / 2, h * 6 / 100)) <= DARK && luma(read(w / 2, h * 94 / 100)) <= DARK
        val scope = topDark && luma(read(w / 2, h * 16 / 100)) > DARK
        val sideDark = luma(read(w * 5 / 100, h / 2)) <= DARK && luma(read(w * 95 / 100, h / 2)) <= DARK
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

    private fun call(code: Int, write: (Parcel) -> Unit): Parcel {
        val data = Parcel.obtain(hal)
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESC)
            write(data)
            hal.transact(code, data, reply, 0)
        } finally {
            data.recycle()
        }
        reply.readException()
        return reply
    }

    private fun openController(): Int {
        val r = call(TX_OPEN_CONTROLLER) {
            it.writeInt(1) // non-null ST_OPEN_CONTROLLER_PARAMS
            it.writeInt(4) // its size: it has no fields
        }
        r.readInt() // non-null RetOpenController
        r.readInt() // size
        val eRet = r.readInt()
        val c = r.readInt()
        r.recycle()
        check(eRet == 0) { "OpenController failed: $eRet" }
        return c
    }

    private fun closeController() {
        call(TX_CLOSE_CONTROLLER) { it.writeInt(controller) }.recycle()
    }

    /** The HAL's answer as 0xRRGGBB (see the class comment for which position it belongs to), or null. */
    private fun pixel(x: Int, y: Int): Int? {
        val r = call(TX_GET_PIXEL_INFO) {
            it.writeInt(controller)
            it.writeInt(1)  // non-null ST_PIXEL_POSITION
            it.writeInt(12) // size: size field + x + y
            it.writeInt(x)
            it.writeInt(y)
        }
        try {
            r.readInt() // non-null RetGetPixelInfo
            r.readInt() // size
            if (r.readInt() != 0) return null // eRet
            r.readInt() // non-null ST_PIXEL_INFO
            r.readInt() // size
            r.readInt() // non-null position
            r.readInt() // position size
            r.readInt() // x
            r.readInt() // y
            val red = r.readInt() and 0xffff
            val green = r.readInt() and 0xffff
            val blue = r.readInt() and 0xffff
            return (red.coerceAtMost(255) shl 16) or (green.coerceAtMost(255) shl 8) or blue.coerceAtMost(255)
        } finally {
            r.recycle()
        }
    }
}
