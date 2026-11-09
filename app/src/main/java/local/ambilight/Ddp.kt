package local.ambilight

import android.os.SystemClock
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Sends colours to WLED on an ESP32 with DDP (UDP port 4048), and switches WLED on and off through its
 * JSON API. Frames and the on/off requests must not be sent from the main thread.
 */
class Ddp(private val config: Config) {
    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private var lastResolvedIp: String = ""
    private var seq = 0

    private var packetBuf = ByteArray(0)

    private var framesSent = 0
    private var statsStart = SystemClock.elapsedRealtime()

    /** One thread, so on/off requests reach WLED in the order they were made. */
    private val http = Executors.newSingleThreadExecutor { r -> Thread(r, "wled-http").apply { isDaemon = true } }

    private fun ensureSocket(): DatagramSocket? {
        val ip = config.espIp
        if (ip != lastResolvedIp || targetAddress == null) {
            try {
                targetAddress = InetAddress.getByName(ip)
                lastResolvedIp = ip
            } catch (e: Exception) {
                Log.w(TAG, "DDP: cannot resolve IP $ip: ${e.message}")
            }
        }
        if (socket == null || socket?.isClosed == true) {
            socket = try {
                DatagramSocket().apply {
                    trafficClass = 0x10 // IPTOS_LOWDELAY: high priority queue on Linux & Wi-Fi WMM
                }
            } catch (e: Exception) {
                Log.w(TAG, "DDP: cannot create socket", e)
                null
            }
        }
        return socket
    }

    /**
     * Sends one frame of RGB values, one per LED. A strip longer than [LEDS_PER_PACKET] is split into several
     * packets with the DDP data offset set; only the last one has the push flag, so WLED shows the frame once.
     */
    @Synchronized
    fun sendFrame(targets: IntArray) {
        val n = config.ledCount
        if (targets.size != n) return

        val sock = ensureSocket() ?: return
        val addr = targetAddress ?: return

        var first = 0
        while (first < n) {
            val count = minOf(LEDS_PER_PACKET, n - first)
            val dataLen = count * 3
            val size = HEADER_SIZE + dataLen
            if (packetBuf.size < size) packetBuf = ByteArray(HEADER_SIZE + LEDS_PER_PACKET * 3)
            val last = first + count >= n
            val dataOffset = first * 3

            // 10-byte DDP v1 header
            packetBuf[0] = (if (last) 0x41 else 0x40).toByte() // V1 (0x40) | Push (0x01) on the last packet
            packetBuf[1] = (seq % 15 + 1).toByte() // sequence 1..15 (0 means "not used")
            packetBuf[2] = 0x01.toByte() // data type: RGB, 8 bits per channel
            packetBuf[3] = 0x01.toByte() // destination: the display
            packetBuf[4] = (dataOffset shr 24 and 0xff).toByte()
            packetBuf[5] = (dataOffset shr 16 and 0xff).toByte()
            packetBuf[6] = (dataOffset shr 8 and 0xff).toByte()
            packetBuf[7] = (dataOffset and 0xff).toByte()
            packetBuf[8] = (dataLen shr 8 and 0xff).toByte()
            packetBuf[9] = (dataLen and 0xff).toByte()

            var ptr = HEADER_SIZE
            for (i in first until first + count) {
                val c = targets[i]
                packetBuf[ptr++] = (c shr 16 and 0xff).toByte()
                packetBuf[ptr++] = (c shr 8 and 0xff).toByte()
                packetBuf[ptr++] = (c and 0xff).toByte()
            }

            try {
                sock.send(DatagramPacket(packetBuf, size, addr, PORT))
            } catch (e: Exception) {
                Log.w(TAG, "DDP send failed: ${e.message}")
                return
            }
            first += count
        }
        seq++
        framesSent++

        val now = SystemClock.elapsedRealtime()
        if (now - statsStart >= 1000) {
            val fps = Math.round(framesSent * 1000.0 / (now - statsStart)).toInt()
            Status.set { it.copy(strip = "WLED · $fps kl./s (${config.espIp})") }
            framesSent = 0
            statsStart = now
        }
    }

    /** All LEDs black. */
    fun sendBlack() {
        sendFrame(IntArray(config.ledCount))
    }

    /**
     * Calibration pattern in picture zones: zone 1 red, 2 yellow (shows the direction), 8 green, 14 purple.
     * It goes through the same direction and offset mapping as the picture, so with the right settings the
     * colours appear next to the numbers 1, 8 and 14 on the preview.
     */
    fun sendTest(reverse: Boolean, offset: Int) {
        val n = config.ledCount
        val mapped = IntArray(n)
        val offsetLeds = offset.mod(n)
        for (i in 0 until n) {
            val zone = ((i + 0.5f) * SEGMENTS / n).toInt().coerceIn(0, SEGMENTS - 1)
            val colour = when (zone) {
                0 -> 0xFF0000
                1 -> 0xFFFF00
                7 -> 0x00FF00
                SEGMENTS - 1 -> 0xFF00FF
                else -> 0x050510 // dim blue, so the rest of the strip shows it is in the test
            }
            mapped[((if (reverse) n - 1 - i else i) + offsetLeds).mod(n)] = colour
        }
        sendFrame(mapped)
    }

    fun setOff() = postState("""{"on":false,"bri":0}""")

    fun setOn() = postState("""{"on":true,"bri":255,"transition":0}""")

    private fun postState(json: String) {
        val ip = config.espIp
        val request = Runnable {
            try {
                val conn = URL("http://$ip/json/state").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 1000
                conn.readTimeout = 1000
                conn.doOutput = true
                conn.outputStream.use { it.write(json.toByteArray()) }
                val code = conn.responseCode
                conn.disconnect()
                if (code != 200) Log.w(TAG, "WLED $json -> HTTP $code")
            } catch (e: Exception) {
                Log.w(TAG, "WLED $json failed: ${e.message}")
            }
        }
        try {
            http.execute(request)
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
    }
}
