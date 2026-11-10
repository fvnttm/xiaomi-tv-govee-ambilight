package local.ambilight

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTest {
    private val base = Settings(
        enabled = true, reverse = false, sides = 4, filterSubtitles = true, offset = 0, espIp = "", espName = "",
        ledCount = 72, brightness = 100, smoothing = Smoothing.STANDARD, blackFadeMs = 0, glide = true,
        sampling = Sampling.BALANCED,
        fadeOutMs = 2000, fadeInMs = 1000, wall = 0, whitePoint = 1, gainR = 100, gainG = 100, gainB = 100,
    )

    @Test
    fun neverAddsLightAndKeepsTheStrongestChannelFull() {
        for (wall in Calibration.walls.indices) for (white in Calibration.whitePoints.indices) {
            val g = Calibration.gains(base.copy(wall = wall, whitePoint = white, gainG = 80))
            assertEquals(1f, g.max(), 1e-6f)
            assertTrue(g.all { it in 0f..1f })
        }
    }

    @Test
    fun beigeWallGetsRelativelyMoreBlue() {
        val white = Calibration.gains(base)
        val beige = Calibration.gains(base.copy(wall = 2))
        assertTrue("blue vs red: ${beige[2] / beige[0]} > ${white[2] / white[0]}", beige[2] / beige[0] > white[2] / white[0])
    }

    @Test
    fun userBalanceScalesItsChannel() {
        val neutralWhiteWall = base.copy(whitePoint = 2, wall = 0)
        val a = Calibration.gains(neutralWhiteWall)
        val b = Calibration.gains(neutralWhiteWall.copy(gainR = 50))
        assertEquals(a[0] / 2, b[0], 0.01f)
        assertArrayEquals(floatArrayOf(a[1], a[2]), floatArrayOf(b[1], b[2]), 1e-6f)
    }
}
