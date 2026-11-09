package local.ambilight

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Govee H605C over BLE. The strip takes about 45 commands/s, and one command sets one colour on any set of its
 * 15 segments, so each command covers every segment that currently wants (nearly) the same colour, picking
 * first the segment furthest from what the strip shows.
 */
@SuppressLint("MissingPermission")
class Govee(private val context: Context, private val config: Config) {
    enum class Mode { STREAM, CAMERA, OFF, TEST }

    @Volatile var mode = Mode.CAMERA

    private val targets = IntArray(SEGMENTS)
    private val current = Array(SEGMENTS) { FloatArray(3) }
    private val shown = IntArray(SEGMENTS) { -1 }
    private val sentAt = LongArray(SEGMENTS)
    private var lastBest = -1
    private var applied: Mode? = null

    private var gatt: BluetoothGatt? = null
    private var writer: BluetoothGattCharacteristic? = null
    @Volatile private var ready = false
    private var readyLatch = CountDownLatch(1)
    private val writeDone = Semaphore(0)
    private var lastWriteAt = 0L
    private var lastTickAt = 0L
    private var writes = 0
    private var statsStart = 0L

    @Volatile private var running = false
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "govee", isDaemon = true) { loop() }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        disconnect()
    }

    /** Colours in strip order (segment 0..14). */
    fun setTargets(colours: IntArray) = synchronized(targets) { colours.copyInto(targets) }

    private fun loop() {
        while (running) {
            try {
                when (val m = mode) {
                    Mode.CAMERA, Mode.OFF -> {
                        if (applied == m) {
                            Thread.sleep(200)
                            continue
                        }
                        if (!connect()) {
                            Thread.sleep(5000)
                            continue
                        }
                        if (m == Mode.CAMERA) {
                            write(power(true))
                            write(packet(unhex(config.cameraFrame)))
                        } else {
                            write(power(false))
                        }
                        applied = m
                        Thread.sleep(500)
                        disconnect() // leaves the strip free for the Govee app on a phone
                        Status.set { it.copy(strip = if (m == Mode.CAMERA) "oddana kamerze Govee" else "wyłączona (TV w czuwaniu)") }
                    }
                    Mode.STREAM, Mode.TEST -> {
                        if (!connect()) {
                            Thread.sleep(5000)
                            continue
                        }
                        if (applied != m) {
                            write(power(true))
                            // Full brightness every time the strip is taken over: the strip-wide value and the
                            // per-segment one, which the camera mode or the Govee app may have left lower.
                            write(packet(byteArrayOf(0x33, 0x04, FULL_BRIGHTNESS)))
                            write(segmentBrightness(FULL_BRIGHTNESS.toInt(), (1 shl SEGMENTS) - 1))
                            shown.fill(-1)
                            lastBest = -1
                            for (j in 0 until SEGMENTS) current[j].fill(0f)
                            applied = m
                        }
                        if (m == Mode.TEST) testPattern() else tick()
                    }
                }
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                Log.w(TAG, "strip loop", e)
                disconnect()
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    /**
     * Advances smooth colour transitions and sends updates over BLE. Segments glide continuously
     * towards the targets, and those wanting similar shades are grouped into a single BLE packet.
     */
    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastTickAt == 0L) 0.016f else ((now - lastTickAt).coerceIn(1, 50)) / 1000f
        lastTickAt = now
        val want = synchronized(targets) { targets.copyOf() }

        // 1. Advance interpolated 'current' directly towards fresh targets (zero input buffer lag)
        for (j in 0 until SEGMENTS) {
            val target = want[j]
            val tR = (target shr 16 and 0xff).toFloat()
            val tG = (target shr 8 and 0xff).toFloat()
            val tB = (target and 0xff).toFloat()

            val cur = current[j]
            if (shown[j] < 0) {
                cur[0] = tR
                cur[1] = tG
                cur[2] = tB
            } else {
                val dr = tR - cur[0]
                val dg = tG - cur[1]
                val db = tB - cur[2]
                val dist = maxOf(abs(dr), abs(dg), abs(db))

                if (dist < 0.6f) {
                    cur[0] = tR
                    cur[1] = tG
                    cur[2] = tB
                } else {
                    val curMax = maxOf(cur[0], cur[1], cur[2])
                    val tgtMax = maxOf(tR, tG, tB)
                    val isDimming = tgtMax < curMax

                    // Responsive speed: 13.0f attack (~75ms response, zero lag), smooth 5.0f-8.0f release
                    val speed = if (isDimming) {
                        if (curMax <= 30f) maxOf(dist * 5.0f, 22f) else maxOf(dist * 8.0f, 35f)
                    } else {
                        maxOf(dist * 13.0f, 45f)
                    }

                    val maxStep = minOf(dist, minOf(24f, speed * dt))
                    val scale = if (dist > 0f) maxStep / dist else 0f
                    cur[0] += dr * scale
                    cur[1] += dg * scale
                    cur[2] += db * scale
                }
            }
        }

        val curRgb = IntArray(SEGMENTS) { j ->
            (current[j][0].toInt().coerceIn(0, 255) shl 16) or
            (current[j][1].toInt().coerceIn(0, 255) shl 8) or
            current[j][2].toInt().coerceIn(0, 255)
        }

        // 2. Select segment with starvation-free round-robin priority scoring
        var best = -1
        var bestScore = -1
        val start = (lastBest + 1) % SEGMENTS
        for (i in 0 until SEGMENTS) {
            val j = (start + i) % SEGMENTS
            val c = curRgb[j]
            val curMax = maxChannel(c)
            val sendThreshold = if (curMax <= 25) 1 else SEND_THRESHOLD
            val error = if (shown[j] < 0) 255 else distance(c, shown[j])
            val ageMs = (now - sentAt[j]).coerceAtLeast(0)
            val stale = shown[j] >= 0 && (ageMs > REFRESH_MS) && error > 0

            if (error >= sendThreshold || stale || shown[j] < 0) {
                // Score combines color error with wait time to prevent segment starvation
                val score = error * 2 + (ageMs / 15).toInt().coerceAtMost(60)
                if (score > bestScore) {
                    bestScore = score
                    best = j
                }
            }
        }

        if (best < 0) {
            if (now - lastWriteAt > 2000) write(packet(byteArrayOf(0xaa.toByte(), 0x01)))
            Thread.sleep(2)
            return
        }
        lastBest = best

        // 3. Dynamic multi-segment grouping:
        // Groups similar segments to multiply effective per-segment framerate by 3x-4x
        val colour = curRgb[best]
        val colMax = maxChannel(colour)
        val groupTol = when {
            colMax <= 15 -> 1
            colMax <= 35 -> 3
            colMax <= 70 -> 6
            else -> 10
        }
        var mask = 1 shl best
        for (j in 0 until SEGMENTS) {
            if (j != best) {
                val distToColour = distance(curRgb[j], colour)
                val errorOnStrip = if (shown[j] < 0) 255 else distance(curRgb[j], shown[j])
                if (distToColour <= groupTol && errorOnStrip >= distToColour) {
                    mask = mask or (1 shl j)
                }
            }
        }

        val wait = WRITE_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastWriteAt)
        if (wait > 0) Thread.sleep(wait)
        if (write(segment(colour, mask), noResponse = true)) {
            val sendTime = SystemClock.elapsedRealtime()
            for (j in 0 until SEGMENTS) if (mask and (1 shl j) != 0) {
                shown[j] = colour
                sentAt[j] = sendTime
            }
        }
    }

    /**
     * Segment 1 red, 2 yellow (which way the strip runs), 8 green, 14 (the last) magenta, the rest dark:
     * shows where the strip starts and which way it goes round the screen.
     */
    private fun testPattern() {
        write(segment(0x000000, (1 shl SEGMENTS) - 1))
        write(segment(0xff0000, 1 shl 0))
        write(segment(0xffc000, 1 shl 1))
        write(segment(0x00ff00, 1 shl 7))
        write(segment(0xff00ff, 1 shl (SEGMENTS - 1)))
        Thread.sleep(1500)
    }

    private fun connect(): Boolean {
        if (ready && gatt != null) return true
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Status.set { it.copy(strip = "czekam na uprawnienia Bluetooth") }
            return false
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Status.set { it.copy(strip = "Bluetooth w TV jest wyłączony") }
            return false
        }
        // Connect to the device as found by a scan, never by bare MAC: the strip uses a random static address,
        // and after a reboot the stack would otherwise assume a public address and never reach it.
        val device = scan(adapter)
        if (device == null) {
            Status.set { it.copy(strip = "nie widzę taśmy: może trzyma ją Govee Home na telefonie") }
            return false
        }
        config.goveeMac = device.address
        disconnect()
        Status.set { it.copy(strip = "łączenie z ${device.address}…") }
        readyLatch = CountDownLatch(1)
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        readyLatch.await(10, TimeUnit.SECONDS)
        if (!ready) {
            disconnect()
            Status.set { it.copy(strip = "taśma nie odpowiada (może łączy się z nią telefon)") }
            return false
        }
        Status.set { it.copy(strip = "połączona") }
        write(packet(byteArrayOf(0xaa.toByte(), 0x05))) // current mode; a camera-mode reply is remembered
        return true
    }

    /** The remembered strip, or any H605C when none is remembered yet. */
    private fun scan(adapter: BluetoothAdapter): BluetoothDevice? {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return null
        val scanner = adapter.bluetoothLeScanner ?: return null
        val known = config.goveeMac
        Status.set { it.copy(strip = "szukam taśmy Govee…") }
        var found: BluetoothDevice? = null
        val done = CountDownLatch(1)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: ""
                val match = if (known != null) result.device.address == known else name.contains("H605C", ignoreCase = true)
                if (match) {
                    found = result.device
                    done.countDown()
                }
            }
        }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        done.await(12, TimeUnit.SECONDS)
        scanner.stopScan(cb)
        return found
    }

    private fun disconnect() {
        ready = false
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        writer = null
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ready = false
                readyLatch.countDown()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE_UUID)
            writer = service?.getCharacteristic(WRITE_UUID)
            val notify = service?.getCharacteristic(NOTIFY_UUID)
            val cccd = notify?.getDescriptor(CCCD_UUID)
            if (notify != null && cccd != null) {
                g.setCharacteristicNotification(notify, true)
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                markReady()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = markReady()

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDone.release()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            // aa 05 00 …: the strip is in camera (video) mode; 33 05 00 … with the same settings restores it.
            if (value.size >= 19 && value[0] == 0xaa.toByte() && value[1] == 0x05.toByte() && value[2] == 0x00.toByte()) {
                config.cameraFrame = hex(byteArrayOf(0x33) + value.copyOfRange(1, 19))
            }
        }

        private fun markReady() {
            ready = writer != null
            readyLatch.countDown()
        }
    }

    /** One GATT write; Android allows one outstanding operation, so wait for its callback. */
    private fun write(p: ByteArray, noResponse: Boolean = false): Boolean {
        val g = gatt ?: return false
        val c = writer ?: return false
        val type = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        writeDone.drainPermits()
        var rc = g.writeCharacteristic(c, p, type)
        var tries = 0
        while (rc != BluetoothStatusCodes.SUCCESS && tries++ < 100) {
            Thread.sleep(2)
            rc = g.writeCharacteristic(c, p, type)
        }
        if (rc != BluetoothStatusCodes.SUCCESS) return false
        lastWriteAt = SystemClock.elapsedRealtime()
        val ok = writeDone.tryAcquire(1, TimeUnit.SECONDS)
        writes++
        if (lastWriteAt - statsStart >= 1000) {
            val perSecond = writes * 1000 / (lastWriteAt - statsStart).coerceAtLeast(1)
            Status.set { it.copy(strip = "połączona · $perSecond komend/s") }
            writes = 0
            statsStart = lastWriteAt
        }
        return ok
    }

    companion object {
        /**
         * Govee documents 15 segments for the H605C, but on this strip mask bit 14 lights nothing and segment 14
         * meets segment 1 at the start corner, so 14 segments go round the screen.
         */
        const val SEGMENTS = 14

        private val SERVICE_UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1910")
        private val WRITE_UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d2b11")
        private val NOTIFY_UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d2b10")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * 15 ms interval = ~66 commands/s, maxing out the BLE channel bandwidth to match panel refresh.
         */
        private const val WRITE_INTERVAL_MS = 15L

        /** Send updates when a segment's interpolated color differs from the strip by at least 2 units. */
        private const val SEND_THRESHOLD = 2

        /** An unchanged segment is repainted after this long, in case a command was lost. */
        private const val REFRESH_MS = 15_000L

        private fun maxChannel(colour: Int) =
            maxOf(colour shr 16 and 0xff, colour shr 8 and 0xff, colour and 0xff)

        private fun distance(a: Int, b: Int) = maxOf(
            abs((a shr 16 and 0xff) - (b shr 16 and 0xff)),
            abs((a shr 8 and 0xff) - (b shr 8 and 0xff)),
            abs((a and 0xff) - (b and 0xff)),
        )

        /** The strip reports brightness in percent (aa 04 64 = 100 %). */
        private const val FULL_BRIGHTNESS: Byte = 0x64

        private fun power(on: Boolean) = packet(byteArrayOf(0x33, 0x01, if (on) 0x01 else 0x00))

        /** 33 05 15 02 LL maskLo maskHi: brightness (percent) of a set of segments. */
        private fun segmentBrightness(percent: Int, mask: Int) =
            packet(byteArrayOf(0x33, 0x05, 0x15, 0x02, percent.toByte(), mask.toByte(), (mask shr 8).toByte()))

        /** 33 05 15 01 RR GG BB 00 00 00 00 00 maskLo maskHi: one colour on a set of segments. */
        private fun segment(rgb: Int, mask: Int) = packet(
            byteArrayOf(
                0x33, 0x05, 0x15, 0x01,
                (rgb shr 16).toByte(), (rgb shr 8).toByte(), rgb.toByte(),
                0, 0, 0, 0, 0,
                mask.toByte(), (mask shr 8).toByte(),
            )
        )

        /** Pads to 20 bytes; the last byte is the XOR of the first 19. */
        private fun packet(body: ByteArray): ByteArray {
            val p = ByteArray(20)
            body.copyInto(p, endIndex = minOf(body.size, 19))
            var x = 0
            for (i in 0 until 19) x = x xor (p[i].toInt() and 0xff)
            p[19] = x.toByte()
            return p
        }

        private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}
