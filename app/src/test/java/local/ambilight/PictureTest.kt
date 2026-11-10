package local.ambilight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureTest {
    private val FULL = floatArrayOf(1f, 1f, 1f)

    private fun frames(zoneValue: Float, count: Int, dither: Picture.Dither?): List<Int> {
        val zones = Array(14) { FloatArray(3) { zoneValue } }
        val out = IntArray(4)
        return List(count) {
            Picture.leds(zones, out, reverse = false, offset = 0, gains = FULL, dither = dither)
            out[0] shr 16 and 0xff
        }
    }

    @Test
    fun ditherHitsInBetweenLevelsOnAverage() {
        // 40 of 255 in the picture is 6.27 of 255 in light: between two PWM steps.
        val target = 255f * (40f / 255f) * (40f / 255f)
        val plain = frames(40f, 120, null)
        assertTrue("rounded without dither", plain.all { it == 6 })
        val dithered = frames(40f, 120, Picture.Dither())
        assertEquals(target, dithered.average().toFloat(), 0.02f)
        assertTrue("only neighbouring levels", dithered.all { it == 6 || it == 7 })
    }

    @Test
    fun blackStaysBlack() {
        assertTrue(frames(0f, 30, Picture.Dither()).all { it == 0 })
    }

    @Test
    fun zonesMixInLight() {
        // Half way between a red zone and a black one: half the light, not half the picture value.
        val zones = Array(2) { FloatArray(3) }
        zones[0][0] = 255f
        val out = IntArray(4) // LED 1 sits a quarter from zone 0, LED 2 a quarter from zone 1 (smoothstep)
        Picture.leds(zones, out, reverse = false, offset = 0, gains = FULL)
        val r1 = out[1] shr 16 and 0xff
        val r2 = out[2] shr 16 and 0xff
        assertEquals(255f, (r1 + r2).toFloat(), 1f)
    }
}
