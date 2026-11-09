package local.ambilight

import local.ambilight.ColourEngine.Companion.FAST_MS
import local.ambilight.ColourEngine.Companion.SLOW_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColourEngineTest {
    private val zones = 14
    private val engine = ColourEngine(zones)
    private val out = Array(zones) { FloatArray(3) }

    private fun at(t: Long, zone: Int): FloatArray {
        engine.current(t, out)
        return out[zone]
    }

    /** Every zone read once, in order, one per 20 ms from [t0]; returns the time after the last one. */
    private fun readAll(colour: Int, t0: Long): Long {
        for (z in 0 until zones) engine.onReading(z, colour, t0 + z * 20L)
        return t0 + zones * 20L
    }

    @Test
    fun fadeToBlackEndsExactlyAfterFastTime() {
        engine.onReading(0, 0xc8c8c8, 0)
        assertEquals(200f, at(1_000, 0)[0], 0.01f)
        engine.onReading(0, 0x000000, 1_000)
        val half = at(1_000 + FAST_MS / 2, 0)[0]
        assertTrue("half way it is between: $half", half in 1f..199f)
        assertEquals(0f, at(1_000 + FAST_MS, 0)[0], 0f)
    }

    @Test
    fun colourMovesInAStraightLine() {
        engine.onReading(0, 0xc80000, 0)
        engine.onReading(0, 0x0000c8, 1_000)
        for (t in listOf(1_002L, 1_007L, 1_013L, 1_018L)) {
            val c = at(t, 0)
            assertEquals("red + blue stays 200 at $t", 200f, c[0] + c[2], 0.01f)
            assertEquals(0f, c[1], 0.01f)
        }
    }

    @Test
    fun smallChangesAreSpreadOut() {
        engine.onReading(0, 0x646464, 0)
        engine.onReading(0, 0x686868, 1_000) // +4: noise
        assertTrue("not there after the fast time", at(1_000 + FAST_MS, 0)[0] < 103.5f)
        assertEquals(104f, at(1_000 + SLOW_MS, 0)[0], 0.01f)
    }

    @Test
    fun blackGateHasHysteresis() {
        engine.onReading(0, 0x0c0c0c, 0) // 12: starts dark, stays off
        assertEquals(0f, at(500, 0)[0], 0f)
        engine.onReading(0, 0x141414, 500) // 20: lights
        assertEquals(20f, at(1_000, 0)[0], 0.01f)
        engine.onReading(0, 0x0c0c0c, 1_000) // 12: still lit
        assertEquals(12f, at(1_500, 0)[0], 0.01f)
        engine.onReading(0, 0x090909, 1_500) // 9: off
        assertEquals(0f, at(2_000, 0)[0], 0f)
    }

    @Test
    fun cutToBlackDarkensZonesNotReadYet() {
        val t = readAll(0x969696, 0)
        engine.onReading(0, 0x000000, t + 1_000)
        assertEquals("one jump is not a cut", 150f, at(t + 1_000 + FAST_MS, 5)[0], 0.01f)
        engine.onReading(1, 0x000000, t + 1_020)
        assertEquals("zone 5 dark after the second jump", 0f, at(t + 1_020 + FAST_MS, 5)[0], 0f)
        assertEquals(0f, at(t + 1_020 + FAST_MS, 13)[0], 0f)
    }

    @Test
    fun cutToBrighterKeepsEachZonesHue() {
        val t = readAll(0x281408, 0) // 40, 20, 8
        engine.onReading(0, 0xa0502a, t + 1_000)
        engine.onReading(1, 0xa0502a, t + 1_020)
        val c = at(t + 1_020 + FAST_MS, 7)
        assertTrue("brighter: ${c[0]}", c[0] > 100f)
        assertEquals("same hue (r:g)", 2f, c[0] / c[1], 0.1f)
    }

    @Test
    fun readingAfterACutWinsOverThePrediction() {
        val t = readAll(0x969696, 0)
        engine.onReading(0, 0x000000, t + 1_000)
        engine.onReading(1, 0x000000, t + 1_020)
        engine.onReading(2, 0x0000c8, t + 1_040) // zone 2 really turned blue
        assertEquals(200f, at(t + 1_040 + FAST_MS, 2)[2], 0.01f)
    }

    @Test
    fun zonesCatchingUpWithACutAreNotASecondCut() {
        var t = readAll(0x282828, 0) + 1_000 // 40
        for (z in 0 until zones) { // the whole picture turns to 120, read zone by zone
            engine.onReading(z, 0x787878, t)
            t += 20
        }
        for (z in 0 until zones) {
            assertEquals("zone $z stays at its reading", 120f, at(t + 500, z)[0], 0.01f)
        }
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
