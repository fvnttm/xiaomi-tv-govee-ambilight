package local.ambilight

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The strip simulated against the real ColourEngine, with the TV's reading rhythm: zones are read one after another
 * every [READ_MS] (as at 60 Hz) and the render thread takes a frame every [SEND_MS]. The screen-to-HAL and network
 * delays are not included.
 */
class LatencySimulationTest {
    private val zones = 14

    /** Times from each black/white flip to the mean output starting to move (10 %) and arriving (90 %). */
    private fun flips(seed: Int, smoothing: Smoothing): Pair<List<Long>, List<Long>> {
        val engine = ColourEngine(zones).apply { setSmoothing(smoothing) }
        val rnd = Random(seed)
        val out = Array(zones) { FloatArray(3) }
        val starts = ArrayList<Long>()
        val ends = ArrayList<Long>()
        var white = false
        var flipAt = 500L + rnd.nextLong(READ_MS)
        var flippedAt = 0L
        var startPending = false
        var endPending = false
        var zone = 0
        var nextRead = 0L
        for (t in 0L until 40_000L) {
            if (t == flipAt) {
                white = !white
                startPending = true
                endPending = true
                flippedAt = t
                flipAt += 2_500 + rnd.nextLong(-40, 40)
            }
            if (t == nextRead) {
                engine.onReading(zone, if (white) 0xffffff else 0x000000, t)
                zone = (zone + 1) % zones
                nextRead += READ_MS
            }
            if (t % SEND_MS == 0L) {
                engine.current(t, out)
                val level = out.sumOf { maxOf(it[0], it[1], it[2]).toDouble() } / zones / 255
                if (startPending && (if (white) level >= 0.1 else level <= 0.9)) {
                    starts += t - flippedAt
                    startPending = false
                }
                if (endPending && (if (white) level >= 0.9 else level <= 0.1)) {
                    ends += t - flippedAt
                    endPending = false
                }
            }
        }
        return starts.drop(1) to ends.drop(1)
    }

    @Test
    fun flipsStartAtOnceAndArriveSmoothly() {
        for (s in Smoothing.entries) {
            val runs = (1..20).map { flips(it, s) }
            val starts = runs.flatMap { it.first }.sorted()
            val ends = runs.flatMap { it.second }.sorted()
            val startMedian = starts[starts.size / 2]
            val endMedian = ends[ends.size / 2]
            println("$s: start (10%) median $startMedian ms, max ${starts.last()} ms; " +
                "90% median $endMedian ms, max ${ends.last()} ms")
            // Two readings must jump to recognise a cut; the strip then moves at once.
            assertTrue("$s start $startMedian ms", startMedian <= 70)
            // Two jumping readings to recognise the cut, then the cut pace.
            assertTrue("$s 90% $endMedian ms", endMedian <= s.cutMs + 70)
        }
    }

    /** Per-frame changes of zone 0 while the picture fades from black to 200 over [rampMs], linearly. */
    private fun rampSteps(smoothing: Smoothing, rampMs: Long): List<Float> {
        val engine = ColourEngine(zones).apply { setSmoothing(smoothing) }
        val out = Array(zones) { FloatArray(3) }
        val steps = ArrayList<Float>()
        var zone = 0
        var last = 0f
        for (t in 0L until rampMs + 2_000) {
            if (t % READ_MS == 0L) {
                val v = (200 * t / rampMs).toInt().coerceIn(0, 200)
                engine.onReading(zone, (v shl 16) or (v shl 8) or v, t)
                zone = (zone + 1) % zones
            }
            if (t % SEND_MS == 0L) {
                engine.current(t, out)
                if (t in rampMs / 3..rampMs * 2 / 3) steps += out[0][0] - last
                last = out[0][0]
            }
        }
        return steps
    }

    @Test
    fun slowFadesHaveNoStaircase() {
        for (s in Smoothing.entries) {
            val steps = rampSteps(s, 3_000)
            val mean = steps.average().toFloat()
            val max = steps.max()
            val flat = steps.count { it < mean * 0.25f }
            println("$s ramp: mean step $mean, max $max, near-flat frames $flat/${steps.size}")
            assertTrue("$s never goes back", steps.all { it >= -0.01f })
            // Hold-then-jump (the old engine) gives a few big steps between many flat frames.
            assertTrue("$s max step $max vs mean $mean", max <= mean * 2.5f)
            assertTrue("$s flat frames $flat", flat <= steps.size / 10)
        }
    }

    @Test
    fun cutsMoveInSmallFrameSteps() {
        val engine = ColourEngine(zones).apply { setSmoothing(Smoothing.DYNAMIC) }
        val out = Array(zones) { FloatArray(3) }
        val last = FloatArray(zones)
        var worst = 0f
        var zone = 0
        for (t in 0L until 6_000L) {
            if (t % READ_MS == 0L) {
                engine.onReading(zone, if (t / 1_500 % 2 == 1L) 0xffffff else 0x000000, t)
                zone = (zone + 1) % zones
            }
            if (t % SEND_MS == 0L) {
                engine.current(t, out)
                for (z in 0 until zones) {
                    worst = maxOf(worst, abs(out[z][0] - last[z]))
                    last[z] = out[z][0]
                }
            }
        }
        println("largest frame step in black/white cuts (dynamic): $worst of 255")
        assertTrue("worst $worst", worst <= 255 * 0.15f) // was ~40 % per frame with 20 ms jumps
    }

    companion object {
        /** One read per displayed frame plus margin: ceil(1000/60) + 2 ms, plus ~2 ms for the two HAL calls. */
        const val READ_MS = 21L
        /** The render thread sends a frame every 1/120 s. */
        const val SEND_MS = 8L
    }
}
