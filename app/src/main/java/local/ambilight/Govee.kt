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
    private val shown = IntArray(SEGMENTS) { -1 }
    private val sentAt = LongArray(SEGMENTS)
    private var applied: Mode? = null

    private var gatt: BluetoothGatt? = null
    private var writer: BluetoothGattCharacteristic? = null
    @Volatile private var ready = false
    private var readyLatch = CountDownLatch(1)
    private val writeDone = Semaphore(0)
    private var lastWriteAt = 0L
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
     * One command: the segment furthest from what the strip shows, plus every segment that wants nearly the
     * same colour and also needs a change. The group gets exactly the leading segment's colour and the grouping
     * tolerance is below the change threshold, so a sent group never looks out of date again (no ping-pong).
     */
    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val want = synchronized(targets) { targets.copyOf() }
        var best = -1
        var bestError = 0
        for (j in 0 until SEGMENTS) {
            val error = if (shown[j] < 0) 255 else distance(want[j], shown[j])
            val stale = now - sentAt[j] > REFRESH_MS
            if ((error > threshold(want[j]) || (stale && error > 0) || shown[j] < 0) && error >= bestError) {
                bestError = error
                best = j
            } else if (stale && best < 0) {
                best = j // repaint an unchanged segment now and then, in case a command was lost
            }
        }
        if (best < 0) {
            if (now - lastWriteAt > 2000) write(packet(byteArrayOf(0xaa.toByte(), 0x01)))
            Thread.sleep(10)
            return
        }
        val colour = want[best]
        var mask = 1 shl best
        for (j in 0 until SEGMENTS) {
            if (j != best && distance(want[j], colour) <= SAME_COLOUR && (shown[j] < 0 || distance(shown[j], colour) > SAME_COLOUR)) {
                mask = mask or (1 shl j)
            }
        }
        val wait = WRITE_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastWriteAt)
        if (wait > 0) Thread.sleep(wait)
        if (write(segment(colour, mask), noResponse = true)) {
            for (j in 0 until SEGMENTS) if (mask and (1 shl j) != 0) {
                shown[j] = colour
                sentAt[j] = now
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

        /** ~38 commands/s, below the ~45/s the strip was measured to process. */
        private const val WRITE_INTERVAL_MS = 26L
        /** Segments whose wanted colours differ by at most this much per channel share one command. */
        private const val SAME_COLOUR = 10
        /** An unchanged segment is repainted after this long, in case a command was lost. */
        private const val REFRESH_MS = 15_000L

        /** A segment is resent only when it is off by more than this: 14 when dark, about 40 at full brightness. */
        private fun threshold(colour: Int) =
            14 + maxOf(colour shr 16 and 0xff, colour shr 8 and 0xff, colour and 0xff) / 10

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
