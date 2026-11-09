package local.ambilight

import kotlin.math.abs

/** Pure colour maths between a zone reading and the strip: saturation, the subtitle filter, zones -> LEDs. */
object Picture {
    private const val SATURATION = 1.25f

    /** A little more saturation; very dark colours keep theirs, so noise in black doesn't turn colourful. */
    fun enhance(rgb: Int): Int {
        val r = (rgb shr 16 and 0xff).toFloat()
        val g = (rgb shr 8 and 0xff).toFloat()
        val b = (rgb and 0xff).toFloat()
        val sat = 1f + (SATURATION - 1f) * ((maxOf(r, g, b) - 8f) / 24f).coerceIn(0f, 1f)
        val luma = 0.299f * r + 0.587f * g + 0.114f * b
        fun ch(v: Float) = (luma + (v - luma) * sat).toInt().coerceIn(0, 255)
        return (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    /**
     * With 4 sides and 14 zones, subtitles sit over the two middle zones of the bottom edge (11 and 12). When one
     * of them looks like subtitle text, it is replaced in [raw] by a blend of its neighbours along the bottom.
     * Returns whether anything was replaced.
     */
    fun filterSubtitles(raw: IntArray): Boolean {
        if (raw.size != 14) return false
        val luma = ColourEngine::luma
        // A bright scene (daylight, snow, a white flash) along the top: bright bottom zones are picture, not text.
        if ((luma(raw[3]) + luma(raw[4]) + luma(raw[5]) + luma(raw[6])) / 4f > 180f) return false

        val left = raw[10]
        val right = raw[13]
        val background = maxOf(luma(left), luma(right))
        val sub11 = isSubtitle(raw[11], background)
        val sub12 = isSubtitle(raw[12], background)
        when {
            sub11 && sub12 -> {
                raw[11] = blend(left, right, 0.67f)
                raw[12] = blend(left, right, 0.33f)
            }
            sub11 -> raw[11] = blend(left, raw[12], 0.60f)
            sub12 -> raw[12] = blend(right, raw[11], 0.60f)
        }
        return sub11 || sub12
    }

    /** Bright, clearly brighter than the picture beside it, and white/grey or yellow. */
    private fun isSubtitle(rgb: Int, backgroundLuma: Float): Boolean {
        val r = rgb shr 16 and 0xff
        val g = rgb shr 8 and 0xff
        val b = rgb and 0xff
        val l = ColourEngine.luma(rgb)
        if (l < 110f || l < backgroundLuma * 1.4f + 35f) return false
        val white = maxOf(r, g, b) - minOf(r, g, b) <= 40
        val yellow = r >= 150 && g >= 130 && b <= 110 && r - b >= 45 && abs(r - g) <= 45
        return white || yellow
    }

    private fun blend(c1: Int, c2: Int, w1: Float): Int {
        fun ch(shift: Int) = ((c1 shr shift and 0xff) * w1 + (c2 shr shift and 0xff) * (1f - w1)).toInt().coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * Fills [out] (one 0xRRGGBB per LED) from the zone colours (picture values 0..255): smooth (Hermite)
     * interpolation between the two nearest zones, the LED curve, then [brightness] (percent). LED 0 of the path
     * lands at [offset]; [reverse] runs the path the other way.
     */
    fun leds(zones: Array<FloatArray>, out: IntArray, reverse: Boolean, offset: Int, brightness: Int) {
        val n = out.size
        val count = zones.size
        for (i in 0 until n) {
            val u = ((i + 0.5f) / n * count - 0.5f).mod(count.toFloat())
            val i0 = u.toInt().mod(count)
            val i1 = (i0 + 1) % count
            val f = u - u.toInt()
            val t = f * f * (3f - 2f * f)
            var rgb = 0
            for (c in 0 until 3) {
                val v = Math.round(zones[i0][c] * (1f - t) + zones[i1][c] * t).coerceIn(0, 255)
                rgb = (rgb shl 8) or (ColourEngine.OUTPUT[v] * brightness + 50) / 100
            }
            out[((if (reverse) n - 1 - i else i) + offset).mod(n)] = rgb
        }
    }
}
