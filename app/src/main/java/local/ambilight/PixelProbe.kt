package local.ambilight

import android.os.IBinder
import android.os.Parcel

/**
 * Diagnostic for the render HAL pixel read, run as shell:
 *   CLASSPATH=<apk> app_process /system/bin local.ambilight.PixelProbe <delayMs> <repeats> x1,y1 x2,y2 ...
 * Reads the given points in turn, waiting delayMs between reads, and prints each value with its timing.
 */
object PixelProbe {
    private const val DESC = "vendor.mediatek.hardware.render.IRender"

    @JvmStatic
    fun main(args: Array<String>) {
        val delay = args[0].toLong()
        val repeats = args[1].toInt()
        val points = args.drop(2).map { p -> p.split(',').let { Pair(it[0].toInt(), it[1].toInt()) } }
        val hal = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "$DESC/default") as IBinder
        fun call(code: Int, write: (Parcel) -> Unit): Parcel {
            val d = Parcel.obtain(hal)
            val r = Parcel.obtain()
            d.writeInterfaceToken(DESC)
            write(d)
            hal.transact(code, d, r, 0)
            d.recycle()
            r.readException()
            return r
        }
        fun open(): Int {
            val o = call(69) { it.writeInt(1); it.writeInt(4) }
            o.readInt(); o.readInt(); o.readInt()
            return o.readInt().also { o.recycle() }
        }
        fun read(ctrl: Int, x: Int, y: Int): String {
            val r = call(49) { it.writeInt(ctrl); it.writeInt(1); it.writeInt(12); it.writeInt(x); it.writeInt(y) }
            repeat(9) { r.readInt() }
            return "%02x%02x%02x".format(r.readInt() and 0xff, r.readInt() and 0xff, r.readInt() and 0xff).also { r.recycle() }
        }
        if (delay < 0) {
            // Parallel test: one controller per point, set all, wait a frame, read all.
            val ctrls = points.map { open() }
            println("controllers: $ctrls")
            repeat(repeats) { rep ->
                points.forEachIndexed { k, (x, y) -> read(ctrls[k], x, y) }
                Thread.sleep(-delay)
                println("r$rep " + points.mapIndexed { k, (x, y) -> "[$x,$y]=${read(ctrls[k], x, y)}" }.joinToString(" "))
            }
            ctrls.forEach { c -> call(5) { it.writeInt(c) }.recycle() }
            return
        }
        val c = open()
        val t0 = System.nanoTime()
        repeat(repeats) { rep ->
            val line = StringBuilder("r$rep")
            for ((x, y) in points) {
                val r = call(49) { it.writeInt(c); it.writeInt(1); it.writeInt(12); it.writeInt(x); it.writeInt(y) }
                r.readInt(); r.readInt()
                val eRet = r.readInt()
                r.readInt(); r.readInt(); r.readInt(); r.readInt()
                val rx = r.readInt()
                val ry = r.readInt()
                val red = r.readInt() and 0xffff
                val green = r.readInt() and 0xffff
                val blue = r.readInt() and 0xffff
                r.recycle()
                line.append(" [%d,%d]%s=%02x%02x%02x(pos %d,%d) t=%d".format(x, y, if (eRet == 0) "" else "!$eRet", red, green, blue, rx, ry, (System.nanoTime() - t0) / 1_000_000))
                if (delay > 0) Thread.sleep(delay)
            }
            println(line)
        }
        call(5) { it.writeInt(c) }.recycle()
    }
}
