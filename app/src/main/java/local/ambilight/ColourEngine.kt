package local.ambilight

import kotlin.math.abs
import kotlin.math.exp

/** How softly the strip follows the picture. Times are to 90 % of a change; the change itself starts at once. */
enum class Smoothing(val label: String, val attackMs: Float, val releaseMs: Float, val cutMs: Float) {
    DYNAMIC("dynamiczna", 150f, 500f, 100f),
    STANDARD("standardowa", 250f, 700f, 120f),
    CALM("spokojna", 500f, 1200f, 200f),
}

/**
 * Turns zone readings into the colours the strip shows over time.
 *
 * Each zone is read only about 3 times a second. Jumping to a reading and holding it until the next one looks like
 * a staircase, so every channel of every zone instead follows its newest reading like a critically damped spring:
 *  - a new reading only moves the target; the channel keeps its speed, so motion flows on without stop-and-go;
 *  - it starts moving in the very next frame, and reaches 90 % of the way in [Smoothing.attackMs] when getting
 *    brighter and the softer [Smoothing.releaseMs] when getting darker. Channels are separate, so in a change of
 *    colour the new one comes in quickly while the old one fades out: the light passes through a bright mix
 *    instead of dimming half way;
 *  - small changes (slow fades, pans, grain) don't move the target at once but glide it there over the time until
 *    the zone's next reading, so a gradual change in the picture stays a gradual change on the strip instead of
 *    "arrive and wait" steps; big changes move the target at once;
 *  - near-black readings set the target to off, and the zone fades there at the release pace (no hard switch).
 *
 * Works on the picture's own (gamma-encoded) 8-bit values, so a fade looks even and ends visibly at black. The
 * LED curve, mixing between zones in light and dithering come at the end ([Picture.leds]).
 *
 * Cuts and fades: when several fresh readings in a row change brightness the same way, the zones not read yet get
 * that brightness change at once, keeping their own hue (zones that were dark take the mean colour of the fresh
 * readings). All of them move at [Smoothing.cutMs], together, in one smooth motion; a later real reading only
 * redirects that motion.
 *
 * Thread-safe: readings arrive on the session thread, [current] is called by the render thread.
 */
class ColourEngine(private val zones: Int) {
    /** Position and speed (per ms) of each channel, picture values 0..255. */
    private val pos = Array(zones) { FloatArray(3) }
    private val vel = Array(zones) { FloatArray(3) }
    /** Where each channel is heading: [glideFrom] moving to [goal] over [glideMs] from [glideAt]. */
    private val goal = Array(zones) { FloatArray(3) }
    private val glideFrom = Array(zones) { FloatArray(3) }
    private val glideAt = LongArray(zones)
    private val glideMs = LongArray(zones)
    /** The zone is following a cut: both directions move at the cut pace until its next real reading. */
    private val cutPace = BooleanArray(zones)
    private var steppedTo = Long.MIN_VALUE

    /** Last real reading of each zone (after the black gate), -1 before the first one. */
    private val lastReading = IntArray(zones) { -1 }
    private val readAt = LongArray(zones) { Long.MIN_VALUE }
    private val dark = BooleanArray(zones) { true }

    private data class Change(val zone: Int, val at: Long, val oldLuma: Float, val newLuma: Float, val colour: Int)
    private val recent = ArrayDeque<Change>()
    private var lastCutAt = Long.MIN_VALUE / 2
    /** When the change behind the last cut began: zones last read before it were given its prediction. */
    private var lastCutSince = Long.MIN_VALUE

    private var attack = omega(Smoothing.STANDARD.attackMs)
    private var release = omega(Smoothing.STANDARD.releaseMs)
    private var cut = omega(Smoothing.STANDARD.cutMs)

    @Synchronized
    fun setSmoothing(s: Smoothing) {
        attack = omega(s.attackMs)
        release = omega(s.releaseMs)
        cut = omega(s.cutMs)
    }

    @Synchronized
    fun reset() {
        for (z in 0 until zones) {
            pos[z].fill(0f); vel[z].fill(0f); goal[z].fill(0f); glideFrom[z].fill(0f)
            glideAt[z] = 0; glideMs[z] = 0
            cutPace[z] = false
            lastReading[z] = -1; readAt[z] = Long.MIN_VALUE; dark[z] = true
        }
        steppedTo = Long.MIN_VALUE
        recent.clear()
        lastCutAt = Long.MIN_VALUE / 2
        lastCutSince = Long.MIN_VALUE
    }

    /** A new reading [colour] (0xRRGGBB, after saturation) of [zone] at time [now] (ms). */
    @Synchronized
    fun onReading(zone: Int, colour: Int, now: Long) {
        step(now)
        val gated = blackGate(zone, colour)
        val previous = lastReading[zone]
        val previousAt = readAt[zone]
        lastReading[zone] = gated
        readAt[zone] = now
        cutPace[zone] = false
        val big = maxOf(
            abs((gated shr 16 and 0xff) - goal[zone][0]),
            abs((gated shr 8 and 0xff) - goal[zone][1]),
            abs((gated and 0xff) - goal[zone][2]),
        ) > GLIDE_MAX
        val interval = if (previous < 0) 0L else (now - previousAt).coerceIn(0L, MAX_GLIDE_MS)
        setTarget(zone, gated, now, if (big || gated == 0) 0L else interval)

        // A zone whose last reading is older than the last cut is only catching up with it: its jump is that
        // same cut, not a new one (counting it would apply the cut's brightness change a second time).
        if (previous >= 0 && previousAt >= lastCutSince) detectCut(zone, luma(previous), gated, now)
    }

    /**
     * The colour of every zone at [now], in picture values 0..255. Returns whether anything is still moving
     * (the output dithers only then, so a still picture still gives identical frames).
     */
    @Synchronized
    fun current(now: Long, out: Array<FloatArray>): Boolean {
        step(now)
        var moving = false
        for (z in 0 until zones) for (c in 0 until 3) {
            out[z][c] = pos[z][c]
            if (abs(pos[z][c] - goal[z][c]) > SETTLED || abs(vel[z][c]) > SETTLED / 100f) moving = true
        }
        return moving
    }

    /** Moves every channel on to [now], in steps short enough that the pace follows the direction of motion. */
    private fun step(now: Long) {
        if (steppedTo == Long.MIN_VALUE || now - steppedTo > MAX_GAP_MS) steppedTo = now // first call, or a pause
        while (steppedTo < now) {
            val dt = minOf(STEP_MS, now - steppedTo)
            steppedTo += dt
            for (z in 0 until zones) for (c in 0 until 3) advance(z, c, dt.toFloat(), targetOf(z, c, steppedTo))
        }
    }

    /** Exact critically damped step: stable for any [dt], never overshoots a target it approaches from rest. */
    private fun advance(z: Int, c: Int, dt: Float, goal: Float) {
        val x = pos[z][c]
        val v = vel[z][c]
        val w = when {
            cutPace[z] -> cut
            goal > x -> attack
            else -> release
        }
        val e = x - goal
        val k = v + w * e
        val decay = exp(-w * dt)
        var nx = goal + (e + k * dt) * decay
        var nv = (v - w * k * dt) * decay
        if (nx <= 0f) { nx = 0f; nv = maxOf(nv, 0f) }
        if (nx >= 255f) { nx = 255f; nv = minOf(nv, 0f) }
        if (goal == 0f && nx < SNAP_TO_BLACK && nv <= 0f) { nx = 0f; nv = 0f } // the tail ends: off, not glimmer
        pos[z][c] = nx
        vel[z][c] = nv
    }

    private fun targetOf(z: Int, c: Int, t: Long): Float {
        val f = if (glideMs[z] <= 0) 1f else ((t - glideAt[z]).toFloat() / glideMs[z]).coerceIn(0f, 1f)
        return glideFrom[z][c] + (goal[z][c] - glideFrom[z][c]) * f
    }

    /** Heads zone [z] for [colour], gliding there over [ms] from where its target is now (0: at once). */
    private fun setTarget(z: Int, colour: Int, now: Long, ms: Long) {
        for (c in 0 until 3) glideFrom[z][c] = targetOf(z, c, now)
        goal[z][0] = (colour shr 16 and 0xff).toFloat()
        goal[z][1] = (colour shr 8 and 0xff).toFloat()
        goal[z][2] = (colour and 0xff).toFloat()
        glideAt[z] = now
        glideMs[z] = ms
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
        val found = when {
            down.size >= CUT_READINGS -> down
            up.size >= CUT_READINGS -> up
            else -> return
        }
        lastCutAt = now
        recent.clear()

        val since = found.minOf { it.at }
        lastCutSince = since
        val oldMean = found.map { it.oldLuma }.average().toFloat()
        val newMean = found.map { it.newLuma }.average().toFloat()
        val toBlack = newMean <= BLACK_ON
        val ratio = ((newMean + 4f) / (oldMean + 4f)).coerceIn(MIN_RATIO, MAX_RATIO)
        fun mean(shift: Int) = Math.round(found.map { (it.colour shr shift and 0xff).toFloat() }.average()).toInt()
        val meanColour = (mean(16) shl 16) or (mean(8) shl 8) or mean(0)
        for (z in 0 until zones) {
            val last = lastReading[z]
            if (last < 0) continue
            cutPace[z] = true // the readings that showed the cut move with the rest, in one motion
            if (readAt[z] >= since) continue // read since the change began: it has its real colour
            val predicted = when {
                toBlack -> 0
                ratio > 1f && luma(last) < 16f -> meanColour // was dark, so no hue of its own
                else -> scale(last, ratio)
            }
            setTarget(z, predicted, now, 0)
        }
    }

    companion object {
        /** Spring rate for a time to 90 %: (1 + wt)e^(-wt) = 0.1 at wt = 3.89. */
        fun omega(ms90: Float) = 3.89f / ms90

        /** Integration step; the pace is picked per step, so a turn of direction changes it within this. */
        private const val STEP_MS = 4L
        /** A longer gap between calls (the render thread was paused) is skipped rather than replayed. */
        private const val MAX_GAP_MS = 1_000L
        /** A reading that differs from the zone's target by more than this (per channel, of 255) moves it at once. */
        private const val GLIDE_MAX = 32
        /** Smaller changes glide over the time since the zone's last reading, at most this long. */
        private const val MAX_GLIDE_MS = 400L
        /** Closer than this to the target, and slower, counts as still. */
        private const val SETTLED = 0.05f
        /** A fading channel below this (of 255, before the LED curve: well under 1 PWM step) goes off. */
        private const val SNAP_TO_BLACK = 2f

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

        /** LED curve: picture value -> light (PWM fraction). WLED applies none to realtime data by default. */
        const val OUTPUT_GAMMA = 2.0
        val LIGHT = FloatArray(256) { v -> Math.pow(v / 255.0, OUTPUT_GAMMA).toFloat() }
        val OUTPUT = IntArray(256) { v -> Math.round(255f * LIGHT[v]) }

        fun luma(c: Int) = 0.299f * (c shr 16 and 0xff) + 0.587f * (c shr 8 and 0xff) + 0.114f * (c and 0xff)

        fun maxChannel(c: Int) = maxOf(c shr 16 and 0xff, c shr 8 and 0xff, c and 0xff)

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
