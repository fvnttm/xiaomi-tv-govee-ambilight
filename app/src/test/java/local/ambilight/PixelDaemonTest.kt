package local.ambilight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PixelDaemonTest {
    @Test
    fun regionCommands() {
        assertEquals(PixelDaemon.Rect(100, 200, 1380, 920), PixelDaemon.parseRegion("R 100 200 1280 720", 3840, 2160))
        assertEquals("kept inside the panel", PixelDaemon.Rect(3000, 0, 3840, 50), PixelDaemon.parseRegion("R 3000 -5 2000 55", 3840, 2160))
        assertNull("whole picture", PixelDaemon.parseRegion("R", 3840, 2160))
        assertNull(PixelDaemon.parseRegion("R 1 2 x 4", 3840, 2160))
        assertNull(PixelDaemon.parseRegion("R 1 2 0 4", 3840, 2160))
    }

    @Test
    fun segmentColourIsTheMeanOfItsPoints() {
        val recent = IntArray(4)
        val seen = IntArray(1)
        var c = PixelDaemon.remember(recent, seen, 0, 0, 0x404040)
        assertEquals("the first reading fills all", 0x404040, c)
        c = PixelDaemon.remember(recent, seen, 0, 1, 0x606060) // a small bright object on one point
        assertEquals(0x484848, c)
        c = PixelDaemon.remember(recent, seen, 0, 2, 0x404040)
        assertEquals(0x484848, c)
    }

    /** The flicker seen on the TV: a still picture whose two points differ a lot (bright window, dark wall). */
    @Test
    fun differentPointsOfAStillPictureGiveAStillColour() {
        val recent = IntArray(2)
        val seen = IntArray(1)
        val out = (0 until 20).map { round -> PixelDaemon.remember(recent, seen, 0, round % 2, if (round % 2 == 0) 0xf0f0f0 else 0x101010) }
        assertEquals("steady from the second round on", setOf(0x808080), out.drop(1).toSet())
    }

    @Test
    fun aCutAtOnePointReplacesAllPointsAtOnce() {
        val recent = IntArray(2)
        val seen = IntArray(1)
        PixelDaemon.remember(recent, seen, 0, 0, 0xc8c8c8)
        PixelDaemon.remember(recent, seen, 0, 1, 0xb4b4b4)
        val c = PixelDaemon.remember(recent, seen, 0, 0, 0x000000)
        assertEquals("no slow mean towards black", 0x000000, c)
    }
}
