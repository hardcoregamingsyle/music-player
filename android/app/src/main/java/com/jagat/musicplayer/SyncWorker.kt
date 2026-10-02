package com.jagat.musicplayer

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Finds the laptop (see desktop/player.py) and pulls down any .mp3 the phone
 * doesn't have yet. Every step is written to [SyncStatus] so the app can show
 * what happened -- sync used to fail silently, which made it undiagnosable.
 *
 * Discovery order, most to least reliable:
 *   1. the address typed into the app / the one that worked last time
 *   2. a scan of the phone's own Wi-Fi subnet for the laptop's port
 *   3. listening for the laptop's UDP broadcast (many phones drop these)
 *
 * All traffic is pinned to the Wi-Fi/Ethernet network for the duration, so a
 * phone that prefers mobile data (or a VPN) still reaches the laptop.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // One sync at a time: the periodic job and "Sync now" can overlap, and
        // REPLACE doesn't stop a run that is already blocked on the network.
        synchronized(LOCK) { runSync(applicationContext) }
        Result.success()
    }

    private fun runSync(ctx: Context) {
        SyncStatus.log(ctx, "--- sync (build ${SyncStatus.versionName(ctx)}) ---")

        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.let { lanNetwork(it) }
        if (cm == null || network == null) {
            SyncStatus.log(ctx, "No Wi-Fi connection. Connect the phone to your home Wi-Fi.")
            return
        }

        cm.bindProcessToNetwork(network)
        try {
            val host = findServer(ctx, cm.getLinkProperties(network))
            if (host == null) {
                SyncStatus.log(ctx, "Laptop not found. Is MusicPlayer running on the laptop, on the same Wi-Fi?")
                return
            }
            download(ctx, host)
        } catch (e: Exception) {
            SyncStatus.log(ctx, "Failed: ${describe(e)}")
        } finally {
            cm.bindProcessToNetwork(null)
        }
    }

    private fun download(ctx: Context, host: String) {
        val manifest = try {
            fetchManifest(host, 5000, 5000)
        } catch (e: Exception) {
            SyncStatus.log(ctx, "Could not read song list from $host: ${describe(e)}")
            return
        }
        SyncStatus.setLastGoodHost(ctx, host)
        SyncStatus.log(ctx, "Laptop has ${manifest.size} songs")

        val dir = MusicLibrary(ctx).syncDir
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }

        var ok = 0
        var failed = 0
        for ((rawName, size) in manifest) {
            if (isStopped) {
                SyncStatus.log(ctx, "Stopped")
                return
            }
            val name = File(rawName).name
            val target = File(dir, name)
            if (target.exists() && target.length() == size) continue
            try {
                downloadFile(host, name, size, target)
                ok++
                SyncStatus.log(ctx, "Got $name")
            } catch (e: Exception) {
                failed++
                SyncStatus.log(ctx, "Skipped $name: ${describe(e)}")
            }
        }
        SyncStatus.log(ctx, if (ok == 0 && failed == 0) "Up to date" else "Done: $ok new, $failed failed")
    }

    @Suppress("DEPRECATION")
    private fun lanNetwork(cm: ConnectivityManager): Network? = cm.allNetworks.firstOrNull { n ->
        val caps = cm.getNetworkCapabilities(n)
        caps != null &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }

    private fun findServer(ctx: Context, link: LinkProperties?): String? {
        val tried = linkedSetOf<String>()
        for (candidate in listOf(SyncStatus.manualHost(ctx), SyncStatus.lastGoodHost(ctx))) {
            if (candidate.isNotBlank() && tried.add(candidate)) {
                SyncStatus.log(ctx, "Trying $candidate")
                if (isLaptop(ctx, candidate, verbose = true)) return candidate
            }
        }
        scanSubnet(ctx, link)?.let {
            SyncStatus.log(ctx, "Found laptop at $it")
            return it
        }
        SyncStatus.log(ctx, "Listening for laptop broadcast...")
        listenForBroadcast(ctx)?.let {
            if (isLaptop(ctx, it, verbose = true)) {
                SyncStatus.log(ctx, "Heard laptop at $it")
                return it
            }
        }
        return null
    }

    private fun isLaptop(ctx: Context, host: String, verbose: Boolean): Boolean = try {
        fetchManifest(host, 2500, 2500)
        true
    } catch (e: Exception) {
        if (verbose) SyncStatus.log(ctx, "$host: ${describe(e)}")
        false
    }

    private fun describe(e: Exception): String {
        var root: Throwable = e
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val raw = "${e.javaClass.simpleName}: ${e.message} ${root.message ?: ""}".replace('\n', ' ')
        val hint = when {
            raw.contains("ECONNREFUSED") -> "refused - MusicPlayer isn't running on the laptop"
            raw.contains("ENETUNREACH") || raw.contains("EHOSTUNREACH") ->
                "unreachable - other network, or router isolates Wi-Fi devices"
            raw.contains("ETIMEDOUT") || raw.contains("timed out") || raw.contains("Timeout") ->
                "timed out - Windows Firewall blocking port $PORT?"
            else -> null
        }
        val short = "${e.javaClass.simpleName}: ${e.message}".take(70)
        return if (hint != null) "$hint ($short)" else short
    }

    private fun scanSubnet(ctx: Context, link: LinkProperties?): String? {
        val addr = link?.linkAddresses?.firstOrNull { it.address is Inet4Address }
        if (addr == null) {
            SyncStatus.log(ctx, "Wi-Fi has no IPv4 address yet")
            return null
        }
        val b = addr.address.address
        val ipInt = ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
        val prefix = addr.prefixLength.coerceIn(22, 30)
        val mask = -1 shl (32 - prefix)
        val network = ipInt and mask
        val broadcast = network or mask.inv()
        val hosts = ((network + 1) until broadcast).filter { it != ipInt }.map { dotted(it) }
        SyncStatus.log(ctx, "Scanning ${dotted(network)}/$prefix from ${dotted(ipInt)}")

        // A fast pass, then a slow one for phones whose Wi-Fi is in power-save
        // and answers connection attempts late.
        for (timeoutMs in intArrayOf(600, 1800)) {
            scanPass(ctx, hosts, timeoutMs)?.let { return it }
        }
        SyncStatus.log(ctx, "Nothing answered on port $PORT in that range")
        return null
    }

    private fun scanPass(ctx: Context, hosts: List<String>, timeoutMs: Int): String? {
        val pool = Executors.newFixedThreadPool(64)
        try {
            val futures = hosts.map { h -> pool.submit(Callable<String?> { if (portOpen(h, timeoutMs)) h else null }) }
            for (f in futures) {
                val found = f.get() ?: continue
                if (isLaptop(ctx, found, verbose = true)) return found
            }
        } finally {
            pool.shutdownNow()
        }
        return null
    }

    private fun dotted(i: Int) = "${(i ushr 24) and 0xFF}.${(i ushr 16) and 0xFF}.${(i ushr 8) and 0xFF}.${i and 0xFF}"

    private fun portOpen(host: String, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, PORT), timeoutMs) }
        true
    } catch (e: Exception) {
        false
    }

    private fun listenForBroadcast(ctx: Context): String? {
        // Many phones drop incoming Wi-Fi broadcast packets while the screen is
        // off; a short high-perf Wi-Fi lock for just this window helps.
        val wifiManager = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "musicplayer:sync")
        wifiLock?.acquire()
        val socket = DatagramSocket(null)
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(BROADCAST_PORT))
            val deadline = System.currentTimeMillis() + DISCOVERY_TIMEOUT_MS
            val packet = DatagramPacket(ByteArray(256), 256)
            while (true) {
                val left = (deadline - System.currentTimeMillis()).toInt()
                if (left <= 0) return null
                socket.soTimeout = left
                packet.length = 256
                socket.receive(packet)
                val parts = String(packet.data, 0, packet.length).split(":")
                if (parts.getOrNull(0) == "MUSICSYNC") return packet.address.hostAddress
            }
        } catch (e: Exception) {
            SyncStatus.log(ctx, "Broadcast: ${describe(e)}")
            return null
        } finally {
            socket.close()
            wifiLock?.release()
        }
    }

    private fun fetchManifest(host: String, connectMs: Int, readMs: Int): List<Pair<String, Long>> {
        val conn = URL("http://$host:$PORT/manifest").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(text)
            return (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                obj.getString("name") to obj.getLong("size")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadFile(host: String, name: String, expectedSize: Long, target: File) {
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val conn = URL("http://$host:$PORT/file/$encoded").openConnection() as HttpURLConnection
        val tmp = File(target.parentFile, "${target.name}.${System.nanoTime()}.part")
        try {
            conn.connectTimeout = 5000
            conn.readTimeout = 15000
            conn.inputStream.use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
            if (tmp.length() != expectedSize) {
                throw IOException("incomplete download (${tmp.length()} of $expectedSize bytes)")
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw IOException("could not save $name")
        } finally {
            conn.disconnect()
            if (tmp.exists()) tmp.delete()
        }
    }

    companion object {
        private val LOCK = Any()
        private const val PORT = 8721
        private const val BROADCAST_PORT = 8722
        private const val DISCOVERY_TIMEOUT_MS = 8000
        const val UNIQUE_PERIODIC_NAME = "music_sync_periodic"
        const val UNIQUE_ONE_TIME_NAME = "music_sync_now"
    }
}
