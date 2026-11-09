package local.ambilight

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

data class WledDevice(val name: String, val ip: String, val leds: Int)

/** WLED's JSON API (plain HTTP on the local network) and finding WLED devices with mDNS. Blocking calls. */
object Wled {
    private const val TIMEOUT_MS = 1500
    private const val MAX_INFO_BYTES = 64 * 1024

    fun isIpv4(s: String): Boolean {
        val parts = s.split('.')
        return parts.size == 4 && parts.all { p -> p.length in 1..3 && p.all(Char::isDigit) && p.toInt() <= 255 }
    }

    /** Name and LED count from /json/info, or null if nothing answers there like WLED. */
    fun info(ip: String): WledDevice? = try {
        val conn = URL("http://$ip/json/info").openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        try {
            // Whatever answers at this address is not trusted: WLED's info is a few kB, so read no more than 64.
            val body = ByteArray(MAX_INFO_BYTES + 1)
            var length = 0
            conn.inputStream.use { input ->
                while (length < body.size) {
                    val n = input.read(body, length, body.size - length)
                    if (n < 0) break
                    length += n
                }
            }
            if (length > MAX_INFO_BYTES) {
                null
            } else {
                val json = JSONObject(String(body, 0, length))
                WledDevice(json.optString("name", "WLED"), ip, json.getJSONObject("leds").getInt("count"))
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    /** POSTs to /json/state; true if WLED answered 200. */
    fun postState(ip: String, json: String): Boolean = try {
        val conn = URL("http://$ip/json/state").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.doOutput = true
        try {
            conn.outputStream.use { it.write(json.toByteArray()) }
            conn.responseCode == 200
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        false
    }

    /**
     * WLED devices announcing themselves on the network (mDNS service `_wled._tcp`), as a growing list.
     * Collect it for as long as the search should run.
     */
    fun discover(context: Context): Flow<List<WledDevice>> = callbackFlow {
        val nsd = context.getSystemService(NsdManager::class.java)
        val found = LinkedHashMap<String, WledDevice>()
        // Before Android 14 only one resolve may run at a time, so found services wait their turn.
        val queue = ArrayDeque<NsdServiceInfo>()
        var resolving = false
        val lock = Any()

        fun next() {
            val service = synchronized(lock) {
                if (resolving || queue.isEmpty()) return
                resolving = true
                queue.removeFirst()
            }
            @Suppress("DEPRECATION")
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = done()

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val ip = info.host?.hostAddress
                    if (ip != null && isIpv4(ip)) thread(name = "wled-info") {
                        val device = Wled.info(ip) ?: WledDevice(info.serviceName, ip, 0)
                        trySend(synchronized(lock) { found[ip] = device; found.values.toList() })
                    }
                    done()
                }

                private fun done() {
                    synchronized(lock) { resolving = false }
                    next()
                }
            })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(lock) { queue.addLast(info) }
                next()
            }
        }
        nsd.discoverServices("_wled._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }
}
