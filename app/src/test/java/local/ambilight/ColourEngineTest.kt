package local.ambilight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColourEngineTest {
    private val zones = 14
    private val engine = ColourEngine(zones).apply { setSmoothing(Smoothing.STANDARD) }
    private val out = Array(zones) { FloatArray(3) }
    private val s = Smoothing.STANDARD

    /** Steps the engine like the render thread (every 8 ms) up to [t]; returns zone [zone] at [t]. */
    private var clock = 0L
    private fun at(t: Long, zone: Int): FloatArray {
        while (clock + 8 < t) { clock += 8; engine.current(clock, out) }
        clock = t
        engine.current(t, out)
        return out[zone].copyOf()
    }

    /** Every zone read once, in order, one per 20 ms from [t0]; returns the time after the last one. */
    private fun readAll(colour: Int, t0: Long): Long {
        for (z in 0 until zones) { at(t0 + z * 20L, 0); engine.onReading(z, colour, t0 + z * 20L) }
        return t0 + zones * 20L
    }

    @Test
    fun startsAtOnceAndReachesNinetyPercentInAttackTime() {
        at(0, 0)
        engine.onReading(0, 0xc8c8c8, 0)
        assertTrue("moves within a frame", at(8, 0)[0] > 0.5f)
        val t90 = (0..2_000).first { at(it.toLong(), 0)[0] >= 180f }
        assertEquals(s.attackMs, t90.toFloat(), 10f)
    }

    @Test
    fun fadesOutSlowerThanItLightsAndEndsAtBlack() {
        engine.onReading(0, 0xc8c8c8, 0)
        at(3_000, 0)
        engine.onReading(0, 0x000000, 3_000)
        val t10 = (3_000..6_000).first { at(it.toLong(), 0)[0] <= 20f } - 3_000
        assertEquals(s.releaseMs, t10.toFloat(), 15f)
        assertEquals("off, no glimmer", 0f, at(6_000, 0)[0], 0f)
    }

    @Test
    fun aNewReadingKeepsTheMotionGoing() {
        engine.onReading(0, 0x646464, 0)
        at(100, 0)
        val before = at(100, 0)[0]
        engine.onReading(0, 0xc8c8c8, 100) // a further step up mid-way
        var lastStep = at(108, 0)[0] - before
        var last = before + lastStep
        for (t in 116L..400L step 8) { // no stop and restart: speed changes gradually
            val v = at(t, 0)[0]
            val step = v - last
            assertTrue("never goes back at $t", step >= -0.001f)
            assertTrue("no sudden jump at $t: $step after $lastStep", step <= lastStep * 1.6f + 0.5f)
            last = v; lastStep = step
        }
    }

    @Test
    fun colourChangePassesThroughBrightLight() {
        engine.onReading(0, 0xc80000, 0)
        at(3_000, 0)
        engine.onReading(0, 0x0000c8, 3_000)
        for (t in 3_000L..3_600L step 8) {
            val c = at(t, 0)
            assertTrue("never dimmer than either colour at $t: ${c.toList()}", c[0] + c[2] >= 199f)
        }
    }

    @Test
    fun blackGateHasHysteresis() {
        engine.onReading(0, 0x0c0c0c, 0) // 12: starts dark, stays off
        assertEquals(0f, at(500, 0)[0], 0f)
        engine.onReading(0, 0x141414, 500) // 20: lights
        assertEquals(20f, at(2_000, 0)[0], 0.1f)
        engine.onReading(0, 0x0c0c0c, 2_000) // 12: still lit
        assertEquals(12f, at(4_000, 0)[0], 0.1f)
        engine.onReading(0, 0x090909, 4_000) // 9: off, softly
        assertTrue("fades, not switched off", at(4_016, 0)[0] > 5f)
        assertEquals(0f, at(6_000, 0)[0], 0f)
    }

    @Test
    fun cutToBlackDarkensZonesNotReadYetTogether() {
        val t = readAll(0x969696, 0) + 2_000
        at(t, 0)
        engine.onReading(0, 0x000000, t)
        assertTrue("one jump is not a cut", at(t + 20, 5)[0] > 149f)
        engine.onReading(1, 0x000000, t + 20)
        val end = t + 20 + s.cutMs.toLong() + 10
        for (z in listOf(0, 1, 5, 13)) assertTrue("zone $z dark", at(end, z)[0] <= 15f)
    }

    @Test
    fun cutToBrighterKeepsEachZonesHue() {
        val t = readAll(0x281408, 0) + 2_000 // 40, 20, 8
        at(t, 0)
        engine.onReading(0, 0xa0502a, t)
        engine.onReading(1, 0xa0502a, t + 20)
        val c = at(t + 600, 7)
        assertTrue("brighter: ${c[0]}", c[0] > 100f)
        assertEquals("same hue (r:g)", 2f, c[0] / c[1], 0.1f)
    }

    @Test
    fun readingAfterACutWinsOverThePrediction() {
        val t = readAll(0x969696, 0) + 2_000
        at(t, 0)
        engine.onReading(0, 0x000000, t)
        engine.onReading(1, 0x000000, t + 20)
        engine.onReading(2, 0x0000c8, t + 40) // zone 2 really turned blue
        assertEquals(200f, at(t + 3_000, 2)[2], 0.1f)
    }

    @Test
    fun zonesCatchingUpWithACutAreNotASecondCut() {
        var t = readAll(0x282828, 0) + 2_000 // 40
        for (z in 0 until zones) { // the whole picture turns to 120, read zone by zone
            at(t, 0)
            engine.onReading(z, 0x787878, t)
            t += 20
        }
        for (z in 0 until zones) {
            assertEquals("zone $z stays at its reading", 120f, at(t + 3_000, z)[0], 0.1f)
        }
    }

    @Test
    fun fadeToBlackCanBeFasterThanTheProfile() {
        engine.setSmoothing(Smoothing.STANDARD, blackMs = 200)
        engine.onReading(0, 0xc8c8c8, 0)
        at(3_000, 0)
        engine.onReading(0, 0x000000, 3_000)
        val t10 = (3_000..6_000).first { at(it.toLong(), 0)[0] <= 20f } - 3_000
        assertEquals(200f, t10.toFloat(), 15f)
    }

    @Test
    fun withoutGlideSmallChangesStartAtTheirFullPace() {
        fun after(glide: Boolean): Float {
            val e = ColourEngine(zones).apply { setSmoothing(Smoothing.STANDARD, glideSmall = glide) }
            val o = Array(zones) { FloatArray(3) }
            e.onReading(0, 0x646464, 0)
            for (t in 0L..3_000L step 8) e.current(t, o)
            e.onReading(0, 0x787878, 3_290) // +20, read again after a normal round
            for (t in 3_008L..3_400L step 8) e.current(t, o)
            return o[0][0]
        }
        assertTrue("gliding lags: ${after(true)} < ${after(false)}", after(true) < after(false) - 3f)
        assertEquals("only the spring: half way after 110 ms", 110f, after(false), 1.5f)
    }

    @Test
    fun stillWhenSettled() {
        engine.onReading(0, 0x808080, 0)
        assertTrue(engine.current(100, out))
        at(5_000, 0)
        assertTrue("settled", !engine.current(5_008, out))
    }

    @Test
    fun outputCurve() {
        assertEquals(0, ColourEngine.OUTPUT[0])
        assertEquals(64, ColourEngine.OUTPUT[128])
        assertEquals(255, ColourEngine.OUTPUT[255])
    }

    @Test
    fun scaleKeepsHueWhenClipping() {
        val c = ColourEngine.scale(0x804020, 3f) // would be 384, 192, 96
        assertEquals(255, c shr 16 and 0xff)
        assertEquals(128, c shr 8 and 0xff)
        assertEquals(64, c and 0xff)
    }
}
