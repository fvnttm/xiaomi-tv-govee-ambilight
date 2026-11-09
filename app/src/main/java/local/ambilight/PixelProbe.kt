package local.ambilight

/**
 * Diagnostic for the render HAL pixel read, run as shell:
 *   CLASSPATH=<apk> app_process /system/bin local.ambilight.PixelProbe <delayMs> <repeats> x1,y1 x2,y2 ...
 * Reads the given points in turn, waiting delayMs between reads, and prints each value with its timing.
 * A negative delay runs the parallel test: one controller per point, ask for all, wait -delayMs, read all.
 */
object PixelProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val delay = args[0].toLong()
        val repeats = args[1].toInt()
        val points = args.drop(2).map { p -> p.split(',').let { Pair(it[0].toInt(), it[1].toInt()) } }
        check(RenderHal.connect()) { "render HAL not found" }

        fun hex(r: IntArray) = "%02x%02x%02x".format(
            r.getOrElse(9) { 0 } and 0xffff, r.getOrElse(10) { 0 } and 0xffff, r.getOrElse(11) { 0 } and 0xffff,
        )

        if (delay < 0) {
            val controllers = points.map { RenderHal.openController() }
            println("controllers: $controllers")
            repeat(repeats) { rep ->
                points.forEachIndexed { k, (x, y) -> RenderHal.pixelRaw(controllers[k], x, y) }
                Thread.sleep(-delay)
                println("r$rep " + points.mapIndexed { k, (x, y) ->
                    "[$x,$y]=${hex(RenderHal.pixelRaw(controllers[k], x, y))}"
                }.joinToString(" "))
            }
            controllers.forEach { RenderHal.closeController(it) }
            return
        }

        val controller = RenderHal.openController()
        val t0 = System.nanoTime()
        repeat(repeats) { rep ->
            val line = StringBuilder("r$rep")
            for ((x, y) in points) {
                val r = RenderHal.pixelRaw(controller, x, y)
                val eRet = r.getOrElse(2) { -1 }
                line.append(" [%d,%d]%s=%s(pos %d,%d) t=%d".format(
                    x, y, if (eRet == 0) "" else "!$eRet", hex(r), r.getOrElse(7) { -1 }, r.getOrElse(8) { -1 },
                    (System.nanoTime() - t0) / 1_000_000,
                ))
                if (delay > 0) Thread.sleep(delay)
            }
            println(line)
        }
        RenderHal.closeController(controller)
    }
}
