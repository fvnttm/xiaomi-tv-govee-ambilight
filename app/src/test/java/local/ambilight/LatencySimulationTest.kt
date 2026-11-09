package local.ambilight

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The latency test screen, simulated against the real ColourEngine: the picture flips between black and white,
 * zones are read one after another every [READ_MS] (as on the TV at 60 Hz), and the time is measured until the
 * mean output brightness is 90 % of the way there. The screen-to-HAL and network delays are not included.
 */
class LatencySimulationTest {
    private val zones = 14

    private fun simulate(seed: Int): List<Long> {
        val engine = ColourEngine(zones)
        val rnd = Random(seed)
        val out = Array(zones) { FloatArray(3) }
        val results = ArrayList<Long>()
        var white = false
        var flipAt = 500L + rnd.nextLong(READ_MS)
        var pending = false
        var zone = 0
        var nextRead = 0L
        for (t in 0L until 40_000L) {
            if (t == flipAt) {
                white = !white
                pending = true
                flipAt += 1_500 + rnd.nextLong(-40, 40)
            }
            if (t == nextRead) {
                engine.onReading(zone, if (white) 0xffffff else 0x000000, t)
                zone = (zone + 1) % zones
                nextRead += READ_MS
            }
            if (pending && t % 16 == 0L) {
                engine.current(t, out)
                val level = out.sumOf { maxOf(it[0], it[1], it[2]).toDouble() } / zones / 255
                if (if (white) level >= 0.9 else level <= 0.1) {
                    results += t - (flipAt - 1_500)
                    pending = false
                }
            }
        }
        return results.drop(1)
    }

    @Test
    fun flipsReachTheStripQuickly() {
        val all = (1..20).flatMap { simulate(it) }.sorted()
        val median = all[all.size / 2]
        val worst = all.last()
        println("simulated reading→output 90%: median $median ms, max $worst ms over ${all.size} flips")
        // Before: ~0.3–0.7 s. With cut prediction a full-screen change needs two jumping readings plus FAST_MS.
        assertTrue("median $median ms", median <= 150)
        assertTrue("max $worst ms", worst <= 200)
    }

    companion object {
        /** One read per displayed frame plus margin: ceil(1000/60) + 2 ms, plus ~2 ms for the two HAL calls. */
        const val READ_MS = 21L
    }
}
