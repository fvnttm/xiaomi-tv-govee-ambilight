package local.ambilight

import android.os.IBinder
import android.os.Parcel

/**
 * Binder client for the MediaTek render HAL (`vendor.mediatek.hardware.render.IRender`), the service the TV's own
 * ExtService uses. Only the shell user (or system) may call it, so this runs inside app_process: PixelDaemon and
 * the two probes.
 *
 * Every parcelable argument is written as: 1 (non-null), its size in bytes including the size field, its fields.
 * Replies come back the same way.
 */
object RenderHal {
    private const val DESC = "vendor.mediatek.hardware.render.IRender"
    private const val TX_CLOSE_CONTROLLER = 5
    private const val TX_GET_PIXEL_INFO = 49
    private const val TX_OPEN_CONTROLLER = 69

    private lateinit var hal: IBinder

    /** Looks the service up; false if this TV doesn't have it. */
    fun connect(): Boolean {
        val service = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "$DESC/default") as IBinder? ?: return false
        hal = service
        return true
    }

    /** Transacts and returns the reply, positioned after the exception header. The caller recycles it. */
    fun call(code: Int, write: (Parcel) -> Unit): Parcel {
        val data = Parcel.obtain(hal)
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESC)
            write(data)
            hal.transact(code, data, reply, 0)
        } finally {
            data.recycle()
        }
        reply.readException()
        return reply
    }

    /** Like [call], with the whole reply as ints: for the probes, which print replies of unknown layout. */
    fun ints(code: Int, write: (Parcel) -> Unit): IntArray {
        val reply = call(code, write)
        try {
            return IntArray(reply.dataAvail() / 4) { reply.readInt() }
        } finally {
            reply.recycle()
        }
    }

    fun openController(): Int {
        val r = call(TX_OPEN_CONTROLLER) {
            it.writeInt(1) // non-null ST_OPEN_CONTROLLER_PARAMS
            it.writeInt(4) // its size: it has no fields
        }
        try {
            r.readInt() // non-null RetOpenController
            r.readInt() // size
            val eRet = r.readInt()
            val controller = r.readInt()
            check(eRet == 0) { "OpenController failed: $eRet" }
            return controller
        } finally {
            r.recycle()
        }
    }

    fun closeController(controller: Int) {
        call(TX_CLOSE_CONTROLLER) { it.writeInt(controller) }.recycle()
    }

    fun writePixelRequest(p: Parcel, controller: Int, x: Int, y: Int) {
        p.writeInt(controller)
        p.writeInt(1)  // non-null ST_PIXEL_POSITION
        p.writeInt(12) // size: size field + x + y
        p.writeInt(x)
        p.writeInt(y)
    }

    /**
     * GetPixelInfo as 0xRRGGBB, or null if the HAL reports an error. The answer is the pixel at the position the
     * HAL latched at the last displayed frame, normally the one asked for in the previous call (see PixelDaemon).
     */
    fun pixel(controller: Int, x: Int, y: Int): Int? {
        val r = call(TX_GET_PIXEL_INFO) { writePixelRequest(it, controller, x, y) }
        try {
            r.readInt() // non-null RetGetPixelInfo
            r.readInt() // size
            if (r.readInt() != 0) return null // eRet
            r.readInt() // non-null ST_PIXEL_INFO
            r.readInt() // size
            r.readInt() // non-null position
            r.readInt() // position size
            r.readInt() // x
            r.readInt() // y
            val red = r.readInt() and 0xffff
            val green = r.readInt() and 0xffff
            val blue = r.readInt() and 0xffff
            return (red.coerceAtMost(255) shl 16) or (green.coerceAtMost(255) shl 8) or blue.coerceAtMost(255)
        } finally {
            r.recycle()
        }
    }

    /** GetPixelInfo's raw reply: [2] eRet, [7],[8] position, [9],[10],[11] red, green, blue. */
    fun pixelRaw(controller: Int, x: Int, y: Int): IntArray =
        ints(TX_GET_PIXEL_INFO) { writePixelRequest(it, controller, x, y) }
}
