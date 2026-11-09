package local.ambilight

import android.os.SystemClock
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Sends colours to WLED with DDP (UDP port 4048), and switches WLED on and off through its JSON API.
 * Frames and the on/off requests must not be sent from the main thread.
 *
 * [onReachable] reports whether WLED answered the last on/off request: UDP itself gives no answer.
 */
class Ddp(private val onReachable: (ip: String, reachable: Boolean) -> Unit) {
    /** WLED's address; frames go nowhere while it is empty. */
    @Volatile var ip = ""

    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null
    private var addressIp = ""
    private var seq = 0
    private var buf = ByteArray(HEADER_SIZE + LEDS_PER_PACKET * 3)
    private val packet = DatagramPacket(buf, 0)

    private var lastFrame = IntArray(0)
    private var repeats = 0
    private var lastSentAt = 0L

    /** One thread, so on/off requests reach WLED in the order they were made. */
    private val http = Executors.newSingleThreadExecutor { r -> Thread(r, "wled-http").apply { isDaemon = true } }

    private fun open(): Boolean {
        val target = ip
        if (target.isEmpty()) return false
        if (target != addressIp || address == null) {
            address = try {
                InetAddress.getByName(target)
            } catch (e: Exception) {
                Log.w(TAG, "DDP: bad address $target: ${e.message}")
                null
            }
            addressIp = target
            lastFrame = IntArray(0) // a new device has seen nothing yet
        }
        if (socket?.isClosed != false) {
            socket = try {
                // IPTOS_LOWDELAY: the high-priority queue on Linux and Wi-Fi WMM
                DatagramSocket().apply { trafficClass = 0x10 }
            } catch (e: Exception) {
                Log.w(TAG, "DDP: cannot create socket", e)
                null
            }
        }
        return address != null && socket != null
    }

    /**
     * Sends one frame, one 0xRRGGBB per LED. A frame equal to the last one is sent twice more (in case a packet
     * was lost) and then only every [KEEPALIVE_MS], which keeps WLED in realtime mode: a still picture costs
     * 4 packets a second instead of 120.
     *
     * A strip longer than [LEDS_PER_PACKET] is split into several packets with the DDP data offset set; only the
     * last one has the push flag, so WLED shows the frame once.
     */
    @Synchronized
    fun sendFrame(leds: IntArray) {
        if (!open()) return
        val now = SystemClock.elapsedRealtime()
        if (leds.contentEquals(lastFrame)) {
            if (repeats >= REPEATS && now - lastSentAt < KEEPALIVE_MS) return
            repeats++
        } else {
            if (lastFrame.size != leds.size) lastFrame = IntArray(leds.size)
            leds.copyInto(lastFrame)
            repeats = 0
        }
        lastSentAt = now

        val n = leds.size
        var first = 0
        while (first < n) {
            val count = minOf(LEDS_PER_PACKET, n - first)
            val dataLen = count * 3
            val dataOffset = first * 3
            buf[0] = (if (first + count >= n) 0x41 else 0x40).toByte() // version 1, push on the last packet
            buf[1] = (seq % 15 + 1).toByte() // sequence 1..15 (0 means "not used")
            buf[2] = 0x01 // data type: RGB, 8 bits per channel
            buf[3] = 0x01 // destination: the display
            buf[4] = (dataOffset shr 24).toByte()
            buf[5] = (dataOffset shr 16).toByte()
            buf[6] = (dataOffset shr 8).toByte()
            buf[7] = dataOffset.toByte()
            buf[8] = (dataLen shr 8).toByte()
            buf[9] = dataLen.toByte()
            var p = HEADER_SIZE
            for (i in first until first + count) {
                val c = leds[i]
                buf[p++] = (c shr 16).toByte()
                buf[p++] = (c shr 8).toByte()
                buf[p++] = c.toByte()
            }
            try {
                packet.setData(buf, 0, HEADER_SIZE + dataLen)
                packet.address = address
                packet.port = PORT
                socket?.send(packet)
            } catch (e: Exception) {
                if (now - lastErrorAt > 5_000) {
                    lastErrorAt = now
                    Log.w(TAG, "DDP send failed: ${e.message}")
                }
                return
            }
            first += count
        }
        seq++
    }

    private var lastErrorAt = 0L

    fun sendBlack(ledCount: Int) = sendFrame(IntArray(ledCount))

    /**
     * Calibration pattern in picture zones: zone 1 red, 2 yellow (shows the direction), 8 green, 14 purple.
     * It goes through the same direction and offset mapping as the picture, so with the right settings the
     * colours appear next to the numbers 1, 8 and 14 on the preview.
     */
    fun sendTest(ledCount: Int, reverse: Boolean, offset: Int) {
        val leds = IntArray(ledCount)
        for (i in 0 until ledCount) {
            val colour = when (((i + 0.5f) * SEGMENTS / ledCount).toInt().coerceIn(0, SEGMENTS - 1)) {
                0 -> 0xFF0000
                1 -> 0xFFFF00
                7 -> 0x00FF00
                SEGMENTS - 1 -> 0xFF00FF
                else -> 0x050510 // dim blue, so the rest of the strip shows it is in the test
            }
            leds[((if (reverse) ledCount - 1 - i else i) + offset).mod(ledCount)] = colour
        }
        sendFrame(leds)
    }

    fun setOff() = postState("""{"on":false,"bri":0}""")

    fun setOn() = postState("""{"on":true,"bri":255,"transition":0}""")

    private fun postState(json: String) {
        val target = ip
        if (target.isEmpty()) return
        try {
            http.execute {
                val ok = Wled.postState(target, json)
                if (!ok) Log.w(TAG, "WLED $target did not take $json")
                onReachable(target, ok)
            }
        } catch (e: RejectedExecutionException) {
            // close() already ran: the service is being destroyed
        }
    }

    /** Closes the socket after the queued on/off requests have been sent. */
    fun close() {
        http.shutdown()
        synchronized(this) {
            runCatching { socket?.close() }
            socket = null
        }
    }

    companion object {
        const val PORT = 4048
        const val HEADER_SIZE = 10
        /** 1440 bytes of colour per packet, the most WLED takes in one DDP packet. */
        const val LEDS_PER_PACKET = 480
        /** An unchanged frame is sent this many more times... */
        const val REPEATS = 2
        /** ...and then this often. WLED leaves realtime mode 2.5 s after the last packet. */
        const val KEEPALIVE_MS = 250L
    }
}
