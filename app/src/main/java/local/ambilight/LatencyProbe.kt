package local.ambilight

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Measures the delay from a black/white flip on screen (the "Test opóźnienia" screen) to:
 *  - read: the first zone read that sees the new picture;
 *  - output: the colours sent to the strip reaching 90 % of the way to the new picture.
 * The screen and the service run in one process, so both sides use SystemClock.elapsedRealtime().
 * The time is taken at the start of the frame that draws the flip, so the panel shows it ~1 frame later.
 */
object LatencyProbe {
    data class Result(
        val running: Boolean = false,
        val flips: Int = 0,
        val read: List<Long> = emptyList(),
        val output: List<Long> = emptyList(),
    ) {
        fun describe(): String {
            fun stats(v: List<Long>) =
                if (v.isEmpty()) "brak" else "mediana ${v.sorted()[v.size / 2]} ms, maks. ${v.max()} ms"
            return "Odczyt obrazu: ${stats(read)} (${read.size}/$flips)\n" +
                "Kolor na taśmę (90%): ${stats(output)} (${output.size}/$flips)"
        }
    }

    val result = MutableStateFlow(Result())

    @Volatile private var active = false
    @Volatile private var flipAt = 0L
    @Volatile private var white = false
    @Volatile private var readPending = false
    @Volatile private var outputPending = false

    fun start() = synchronized(this) {
        active = true
        readPending = false
        outputPending = false
        result.value = Result(running = true)
    }

    /** The screen has just turned white ([toWhite]) or black. */
    fun flip(toWhite: Boolean) = synchronized(this) {
        if (!active) return@synchronized
        flipAt = SystemClock.elapsedRealtime()
        white = toWhite
        readPending = true
        outputPending = true
        result.value = result.value.copy(flips = result.value.flips + 1)
    }

    fun stop() = synchronized(this) {
        active = false
        result.value = result.value.copy(running = false)
        Log.i(TAG, "latency test: " + result.value.describe().replace('\n', ' '))
    }

    /** Zone colours as read from the picture (0xRRGGBB); called for every daemon line. */
    fun onRead(zones: IntArray) {
        if (!readPending) return
        val seen = zones.any { c -> if (white) maxChannel(c) >= 200 else maxChannel(c) <= 30 }
        if (seen) record(read = true)
    }

    /** Mean brightness (0..1, before the LED curve) of what is being sent to the strip. */
    fun onOutput(level: Float) {
        if (!outputPending) return
        if (if (white) level >= 0.9f else level <= 0.1f) record(read = false)
    }

    private fun record(read: Boolean) = synchronized(this) {
        val pending = if (read) readPending else outputPending
        if (!active || !pending) return@synchronized
        val ms = SystemClock.elapsedRealtime() - flipAt
        val r = result.value
        if (read) {
            readPending = false
            result.value = r.copy(read = r.read + ms)
        } else {
            outputPending = false
            result.value = r.copy(output = r.output + ms)
        }
    }

    private fun maxChannel(c: Int) = maxOf(c shr 16 and 0xff, c shr 8 and 0xff, c and 0xff)
}
