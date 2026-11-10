package local.ambilight

/**
 * Per-channel gains for the strip, so its light looks on the wall the way the picture does:
 *  - the wall's own tint is divided out (a beige wall swallows blue, so blue gets relatively more);
 *  - the white point: warm, neutral or cool;
 *  - the strip's own balance, set by eye with the white test.
 * The result is normalised so the strongest channel stays at full power: calibration only ever takes light away.
 */
object Calibration {
    class Wall(val label: String, val rgb: Int)
    class WhitePoint(val label: String, val r: Float, val g: Float, val b: Float)

    val walls = listOf(
        Wall("biała", 0xEDEDE8),
        Wall("ciepła biel", 0xEFE6D6),
        Wall("beżowa", 0xD9C7A8),
        Wall("szara", 0xA9ADB2),
        Wall("niebieska", 0x9FB4C8),
        Wall("zielona", 0xA9BFA4),
    )

    val whitePoints = listOf(
        WhitePoint("ciepła", 1f, 0.86f, 0.68f),
        WhitePoint("neutralna", 1f, 0.96f, 0.92f),
        WhitePoint("zimna", 0.9f, 0.95f, 1f),
    )

    /** Gains 0..1 for R, G and B. */
    fun gains(s: Settings): FloatArray {
        val wall = walls[s.wall.coerceIn(walls.indices)].rgb
        val w = floatArrayOf((wall shr 16 and 0xff).toFloat(), (wall shr 8 and 0xff).toFloat(), (wall and 0xff).toFloat())
        val darkest = w.min()
        val p = whitePoints[s.whitePoint.coerceIn(whitePoints.indices)]
        val white = floatArrayOf(p.r, p.g, p.b)
        val user = floatArrayOf(s.gainR / 100f, s.gainG / 100f, s.gainB / 100f)
        val g = FloatArray(3) { c -> darkest / w[c] * white[c] * user[c] }
        val top = g.max()
        return if (top <= 0f) floatArrayOf(1f, 1f, 1f) else FloatArray(3) { c -> g[c] / top }
    }
}
