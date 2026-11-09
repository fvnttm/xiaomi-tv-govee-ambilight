package local.ambilight

/**
 * Diagnostics for other render HAL calls that could make the picture reading faster. Run as the shell user:
 *   CLASSPATH=<apk> app_process /system/bin local.ambilight.HalProbe <mode> [args]
 * Modes:
 *   info                    panel fps, vsync info, output latency, local-dimming block counts (types 0..3)
 *   ldm <type> <count>      read the local-dimming block data <count> times, timing each call
 *   vsync <count>           time WaitVsync
 *   pipe <count> <x1,y1> <x2,y2>
 *                           WaitVsync, then ask for x1,y1 and x2,y2 in turn, one call per frame; prints the asked
 *                           point, the returned position and colour, to see whether the answer names its point
 * Unknown replies are printed as raw ints. Turn Ambilight off first, so the daemon doesn't share the latch.
 */
object HalProbe {
    private var controller = 0

    @JvmStatic
    fun main(args: Array<String>) {
        check(RenderHal.connect()) { "render HAL not found" }
        controller = RenderHal.openController()
        println("controller=$controller")
        try {
            when (args.getOrNull(0)) {
                "info" -> info()
                "ldm" -> ldm(args[1].toInt(), args[2].toInt())
                "vsync" -> vsync(args[1].toInt())
                "pipe" -> pipe(args[1].toInt(), point(args[2]), point(args[3]))
                else -> println("modes: info | ldm <type> <count> | vsync <count> | pipe <count> <x,y> <x,y>")
            }
        } finally {
            RenderHal.closeController(controller)
        }
    }

    private fun point(s: String) = s.split(',').let { Pair(it[0].toInt(), it[1].toInt()) }

    private fun info() {
        println("GetPanelFps          " + call(44) { it.writeInt(controller) }.joinToString(" "))
        println("GetVsyncInfo         " + call(60) { it.writeInt(controller) }.joinToString(" ") + "   (ints; longs are 2 ints)")
        println("GetOutputPathLatency " + call(41) { it.writeInt(controller) }.joinToString(" "))
        for (type in 0..3) {
            val r = call(38) {
                it.writeInt(controller)
                it.writeInt(1)  // non-null ST_LDM_BLOCK_NUM_INFO
                it.writeInt(16) // size: size + type + width + height
                it.writeInt(type)
                it.writeInt(0)
                it.writeInt(0)
            }
            println("GetLdmBlockNum t=$type  " + r.joinToString(" ") + "   (… eRet, 1, size, type, width, height)")
        }
    }

    private fun ldm(type: Int, count: Int) {
        val num = call(38) {
            it.writeInt(controller); it.writeInt(1); it.writeInt(16); it.writeInt(type); it.writeInt(0); it.writeInt(0)
        }
        val w = num.getOrElse(6) { 0 }
        val h = num.getOrElse(7) { 0 }
        val n = maxOf(1, w * h)
        println("blocks ${w}x$h (eRet=${num.getOrNull(2)})")
        repeat(count) { k ->
            val t0 = System.nanoTime()
            val r = call(37) {
                it.writeInt(controller)
                it.writeInt(1) // non-null ST_LDM_BLOCK_DATA_INFO
                val start = it.dataPosition()
                it.writeInt(0) // size, fixed below
                it.writeInt(type)
                it.writeCharArray(CharArray(n))
                it.writeLong(n.toLong())
                val end = it.dataPosition()
                it.setDataPosition(start); it.writeInt(end - start); it.setDataPosition(end)
            }
            val ms = (System.nanoTime() - t0) / 1e6
            // reply: 1, size, eRet, 1, size, type, arrayLen, values..., size(long)
            val eRet = r.getOrNull(2)
            val len = r.getOrNull(6) ?: -1
            val values = if (len > 0 && r.size >= 7 + len) r.copyOfRange(7, 7 + len) else IntArray(0)
            println("#$k %.1f ms eRet=$eRet len=$len".format(ms) +
                if (values.isEmpty()) " raw=" + r.take(24).joinToString(" ") else
                    " min=${values.min()} max=${values.max()} first=" + values.take(16).joinToString(","))
            if (k == count - 1 && values.isNotEmpty() && w > 0) {
                for (y in 0 until h) println("  " + (0 until w).joinToString(" ") { x -> "%4d".format(values[y * w + x]) })
            }
        }
    }

    private fun waitVsync(): Long {
        val t0 = System.nanoTime()
        call(161) { it.writeInt(controller) }
        return System.nanoTime() - t0
    }

    private fun vsync(count: Int) {
        var last = System.nanoTime()
        repeat(count) {
            val waited = waitVsync()
            val now = System.nanoTime()
            println("waited %.2f ms, since last %.2f ms".format(waited / 1e6, (now - last) / 1e6))
            last = now
        }
    }

    private fun pipe(count: Int, a: Pair<Int, Int>, b: Pair<Int, Int>) {
        repeat(count) { k ->
            val p = if (k % 2 == 0) a else b
            waitVsync()
            val t = System.nanoTime()
            val r = RenderHal.pixelRaw(controller, p.first, p.second)
            // 1, size, eRet, 1, size, 1, size, x, y, R, G, B
            println("#$k asked ${p.first},${p.second}  pos ${r.getOrNull(7)},${r.getOrNull(8)}  rgb %02x%02x%02x  t=%.1f ms".format(
                (r.getOrElse(9) { 0 } and 0xffff).coerceAtMost(255),
                (r.getOrElse(10) { 0 } and 0xffff).coerceAtMost(255),
                (r.getOrElse(11) { 0 } and 0xffff).coerceAtMost(255),
                (System.nanoTime() - t) / 1e6))
        }
    }

    private fun call(code: Int, write: (android.os.Parcel) -> Unit) = RenderHal.ints(code, write)
}
