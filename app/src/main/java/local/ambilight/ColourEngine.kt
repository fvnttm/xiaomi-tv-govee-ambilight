package local.ambilight

import kotlin.math.abs

/**
 * Turns zone readings into the colours the strip shows over time.
 *
 * Works on the picture's own 8-bit values. Video is gamma-encoded, so equal steps look roughly equal to the eye:
 * a fade computed here looks even and ends visibly at black, instead of a long dim tail. The LED curve is applied
 * only at the very end (see [OUTPUT]).
 *
 * Each zone moves from where it is to its newest reading in a fixed time, the same fraction for R, G and B (so
 * the colour moves in a straight line and does not change hue on the way):
 *  - big changes and fades to black take [FAST_MS] (about one displayed frame: as good as at once);
 *  - small changes (noise, grain, slow pans) are spread over up to [SLOW_MS].
 *
 * Zones are read one after another, about 3 times a second each. When several fresh readings in a row change
 * brightness the same way (a cut or a fade), the zones not read yet get that brightness change at once, keeping
 * their own hue (zones that were dark, and so have no hue, take the mean colour of those fresh readings), and
 * take their real colour when they are read ([onReading]).
 *
 * Thread-safe: readings arrive on the session thread, [current] is called by the render thread.
 */
class ColourEngine(private val zones: Int) {
    private val from = Array(zones) { FloatArray(3) }
    private val to = Array(zones) { FloatArray(3) }
    private val startAt = LongArray(zones)
    private val duration = LongArray(zones) { 1 }

    /** Last real reading of each zone (after the black gate), -1 before the first one. */
    private val lastReading = IntArray(zones) { -1 }
    private val readAt = LongArray(zones) { Long.MIN_VALUE }
    private val dark = BooleanArray(zones) { true }

    private data class Change(val zone: Int, val at: Long, val oldLuma: Float, val newLuma: Float, val colour: Int)
    private val recent = ArrayDeque<Change>()
    private var lastCutAt = Long.MIN_VALUE / 2
    /** When the change behind the last cut began: zones last read before it were given its prediction. */
    private var lastCutSince = Long.MIN_VALUE

    @Synchronized
    fun reset() {
        for (z in 0 until zones) {
            from[z].fill(0f); to[z].fill(0f)
            duration[z] = 1; startAt[z] = 0
            lastReading[z] = -1; readAt[z] = Long.MIN_VALUE; dark[z] = true
        }
        recent.clear()
        lastCutAt = Long.MIN_VALUE / 2
        lastCutSince = Long.MIN_VALUE
    }

    /** A new reading [colour] (0xRRGGBB, after saturation) of [zone] at time [now] (ms). */
    @Synchronized
    fun onReading(zone: Int, colour: Int, now: Long) {
        val gated = blackGate(zone, colour)
        val previous = lastReading[zone]
        val previousAt = readAt[zone]
        lastReading[zone] = gated
        readAt[zone] = now

        // A zone whose last reading is older than the last cut is only catching up with it: its jump is that
        // same cut, not a new one (counting it would apply the cut's brightness change a second time).
        if (previous >= 0 && previousAt >= lastCutSince) detectCut(zone, luma(previous), gated, now)

        val cur = currentOf(zone, now)
        val target = rgb(gated)
        val d = maxOf(abs(target[0] - cur[0]), abs(target[1] - cur[1]), abs(target[2] - cur[2]))
        val ms = when {
            gated == 0 -> FAST_MS
            d >= BIG -> FAST_MS
            d <= SMALL -> SLOW_MS
            else -> SLOW_MS + ((d - SMALL) / (BIG - SMALL) * (FAST_MS - SLOW_MS)).toLong()
        }
        start(zone, cur, target, now, ms)
    }

    /** The colour of every zone at [now], in picture values 0..255. */
    @Synchronized
    fun current(now: Long, out: Array<FloatArray>) {
        for (z in 0 until zones) {
            val c = currentOf(z, now)
            out[z][0] = c[0]; out[z][1] = c[1]; out[z][2] = c[2]
        }
    }

    private fun currentOf(z: Int, now: Long): FloatArray {
        val x = ((now - startAt[z]).toFloat() / duration[z]).coerceIn(0f, 1f)
        val e = 1f - (1f - x) * (1f - x) // ease-out: moves at once, settles softly
        return FloatArray(3) { c -> from[z][c] + (to[z][c] - from[z][c]) * e }
    }

    private fun start(z: Int, cur: FloatArray, target: FloatArray, now: Long, ms: Long) {
        cur.copyInto(from[z]); target.copyInto(to[z])
        startAt[z] = now
        duration[z] = maxOf(1, ms)
    }

    /** Near-black is shown as off; a zone has to get clearly brighter to light up again (no flicker at the edge). */
    private fun blackGate(z: Int, colour: Int): Int {
        val m = maxChannel(colour)
        if (dark[z]) {
            if (m < BLACK_OFF) return 0
            dark[z] = false
        } else if (m <= BLACK_ON) {
            dark[z] = true
            return 0
        }
        return colour
    }

    private fun detectCut(zone: Int, oldLuma: Float, colour: Int, now: Long) {
        recent.addLast(Change(zone, now, oldLuma, luma(colour), colour))
        while (recent.size > CUT_WINDOW) recent.removeFirst()
        if (now - lastCutAt < CUT_HOLD_MS) return
        val jumps = recent.filter { abs(it.newLuma - it.oldLuma) >= CUT_JUMP }
        val down = jumps.filter { it.newLuma < it.oldLuma }
        val up = jumps.filter { it.newLuma > it.oldLuma }
        val cut = when {
            down.size >= CUT_READINGS -> down
            up.size >= CUT_READINGS -> up
            else -> return
        }
        lastCutAt = now
        recent.clear()

        val since = cut.minOf { it.at }
        lastCutSince = since
        val oldMean = cut.map { it.oldLuma }.average().toFloat()
        val newMean = cut.map { it.newLuma }.average().toFloat()
        val toBlack = newMean <= BLACK_ON
        val ratio = ((newMean + 4f) / (oldMean + 4f)).coerceIn(MIN_RATIO, MAX_RATIO)
        fun mean(shift: Int) = Math.round(cut.map { (it.colour shr shift and 0xff).toFloat() }.average()).toInt()
        val meanColour = (mean(16) shl 16) or (mean(8) shl 8) or mean(0)
        for (z in 0 until zones) {
            if (readAt[z] >= since) continue // read since the change began: it has its real colour
            val last = lastReading[z]
            if (last < 0) continue
            val predicted = when {
                toBlack -> 0
                ratio > 1f && luma(last) < 16f -> meanColour // was dark, so no hue of its own
                else -> scale(last, ratio)
            }
            start(z, currentOf(z, now), rgb(predicted), now, FAST_MS)
        }
    }

    companion object {
        const val FAST_MS = 20L
        const val SLOW_MS = 200L
        /** Per-channel difference (0..255) from which a change counts as big... */
        const val BIG = 24f
        /** ...and up to which it counts as noise. */
        const val SMALL = 6f
        /** Below or at this the zone goes off... */
        const val BLACK_ON = 10
        /** ...and it lights again only from this. */
        const val BLACK_OFF = 18
        /** Luma change of one reading that counts towards a cut. */
        const val CUT_JUMP = 48f
        /** Readings looked at, and how many of them must jump the same way. */
        const val CUT_WINDOW = 3
        const val CUT_READINGS = 2
        /** After a cut, the next one can be detected only after this long. */
        const val CUT_HOLD_MS = 40L
        const val MIN_RATIO = 0.1f
        const val MAX_RATIO = 3f

        /** LED curve: picture value -> PWM value. WLED applies none to realtime data by default. */
        const val OUTPUT_GAMMA = 2.0
        val OUTPUT = IntArray(256) { v -> Math.round(255.0 * Math.pow(v / 255.0, OUTPUT_GAMMA)).toInt() }

        fun luma(c: Int) = 0.299f * (c shr 16 and 0xff) + 0.587f * (c shr 8 and 0xff) + 0.114f * (c and 0xff)

        fun maxChannel(c: Int) = maxOf(c shr 16 and 0xff, c shr 8 and 0xff, c and 0xff)

        fun rgb(c: Int) = floatArrayOf((c shr 16 and 0xff).toFloat(), (c shr 8 and 0xff).toFloat(), (c and 0xff).toFloat())

        /** Brightness times [ratio], same hue: if a channel would pass 255, all are scaled down together. */
        fun scale(c: Int, ratio: Float): Int {
            val r = (c shr 16 and 0xff) * ratio
            val g = (c shr 8 and 0xff) * ratio
            val b = (c and 0xff) * ratio
            val k = maxOf(1f, maxOf(r, g, b) / 255f)
            return (Math.round(r / k) shl 16) or (Math.round(g / k) shl 8) or Math.round(b / k)
        }
    }
}
