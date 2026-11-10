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
        val filled = IntArray(1)
        var c = PixelDaemon.remember(recent, filled, 0, 0, 0x404040, 0)
        assertEquals("the first reading fills all", 0x404040, c)
        c = PixelDaemon.remember(recent, filled, 0, 1, 0x606060, c) // a small bright object on one point
        assertEquals(0x484848, c)
        c = PixelDaemon.remember(recent, filled, 0, 2, 0x404040, c)
        assertEquals(0x484848, c)
    }

    @Test
    fun aCutReplacesAllPointsAtOnce() {
        val recent = IntArray(4)
        val filled = IntArray(1)
        var c = PixelDaemon.remember(recent, filled, 0, 0, 0xc8c8c8, 0)
        c = PixelDaemon.remember(recent, filled, 0, 1, 0x000000, c)
        assertEquals("no slow mean towards black", 0x000000, c)
    }
}
