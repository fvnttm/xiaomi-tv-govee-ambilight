package local.ambilight

import android.os.SystemClock
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class Ddp(private val config: Config) {
    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private var lastResolvedIp: String = ""
    private var seq: Byte = 0

    private var packetBuf = ByteArray(HEADER_SIZE + config.ledCount * 3)

    private var framesSent = 0
    private var statsStart = SystemClock.elapsedRealtime()

    init {
        try {
            socket = DatagramSocket()
        } catch (e: Exception) {
            Log.e(TAG, "DDP: failed to create DatagramSocket", e)
        }
    }

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
            socket = try { DatagramSocket() } catch (e: Exception) { null }
        }
        return socket
    }

    /**
     * Blasts a single DDP packet with RGB values over UDP to WLED.
     */
    fun sendFrame(targets: IntArray) {
        val n = config.ledCount
        if (targets.size != n) return

        if (packetBuf.size != HEADER_SIZE + n * 3) {
            packetBuf = ByteArray(HEADER_SIZE + n * 3)
        }

        val sock = ensureSocket() ?: return
        val addr = targetAddress ?: return

        val dataLen = n * 3

        // 10-byte DDP v1 packet header
        packetBuf[0] = 0x41.toByte() // Flags: V1 (0x40) | Push (0x01)
        packetBuf[1] = seq++        // Sequence number (0-255)
        packetBuf[2] = 0x01.toByte() // Data Type: 1 (RGB)
        packetBuf[3] = 0x01.toByte() // Destination ID: 1 (Display)
        packetBuf[4] = 0             // Offset (32-bit big-endian)
        packetBuf[5] = 0
        packetBuf[6] = 0
        packetBuf[7] = 0
        packetBuf[8] = ((dataLen shr 8) and 0xff).toByte()
        packetBuf[9] = (dataLen and 0xff).toByte()

        var ptr = HEADER_SIZE
        for (i in 0 until n) {
            val tCol = targets[i]
            packetBuf[ptr++] = ((tCol shr 16) and 0xff).toByte()
            packetBuf[ptr++] = ((tCol shr 8) and 0xff).toByte()
            packetBuf[ptr++] = (tCol and 0xff).toByte()
        }

        try {
            val packet = DatagramPacket(packetBuf, packetBuf.size, addr, PORT)
            sock.send(packet)
            framesSent++
        } catch (e: Exception) {
            Log.w(TAG, "DDP send failed", e)
        }

        val now = SystemClock.elapsedRealtime()
        if (now - statsStart >= 1000) {
            val fps = Math.round(framesSent * 1000.0 / (now - statsStart)).toInt()
            Status.set { it.copy(strip = "WLED · $fps FPS (${config.espIp})") }
            framesSent = 0
            statsStart = now
        }
    }

    /** Instantly turns off all LEDs when TV goes into standby or Ambilight is disabled. */
    fun sendBlack() {
        val n = config.ledCount
        val black = IntArray(n) { 0 }
        sendFrame(black)
    }

    /** Sends a calibration test pattern lighting up 1 (red), 2 (yellow), 8 (green), 14 (purple). */
    fun sendTest(reverse: Boolean, offset: Int) {
        val n = config.ledCount
        val segments = 14
        val ledsPerSeg = n / segments
        val pattern = IntArray(n)

        for (s in 0 until segments) {
            val color = when (s) {
                0 -> 0xFF0000 // Seg 1: Red
                1 -> 0xFFFF00 // Seg 2: Yellow (direction)
                7 -> 0x00FF00 // Seg 8: Green
                13 -> 0xFF00FF // Seg 14: Purple
                else -> 0x050510 // Dim blue ambient for the rest
            }
            val start = s * ledsPerSeg
            val end = if (s == segments - 1) n else (s + 1) * ledsPerSeg
            for (idx in start until end) {
                pattern[idx] = color
            }
        }

        val mapped = IntArray(n)
        val offsetLeds = offset.mod(n)
        for (i in 0 until n) {
            val out = ((if (reverse) n - 1 - i else i) + offsetLeds).mod(n)
            mapped[out] = pattern[i]
        }
        sendFrame(mapped)
    }

    fun setOff() {
        kotlin.concurrent.thread(isDaemon = true) {
            runCatching {
                val url = java.net.URL("http://${config.espIp}/json/state")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 1000
                conn.readTimeout = 1000
                conn.doOutput = true
                conn.outputStream.use { it.write("{\"on\":false,\"bri\":0,\"ps\":1}".toByteArray()) }
                conn.responseCode
                conn.disconnect()
            }
        }
    }

    fun setOn() {
        kotlin.concurrent.thread(isDaemon = true) {
            runCatching {
                val url = java.net.URL("http://${config.espIp}/json/state")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 1000
                conn.readTimeout = 1000
                conn.doOutput = true
                conn.outputStream.use { it.write("{\"on\":true,\"bri\":255}".toByteArray()) }
                conn.responseCode
                conn.disconnect()
            }
        }
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        const val PORT = 4048
        const val HEADER_SIZE = 10
    }
}
