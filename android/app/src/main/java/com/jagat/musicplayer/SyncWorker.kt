package com.jagat.musicplayer

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONArray
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
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
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        SyncStatus.log(ctx, "Sync started")

        val host = findServer(ctx)
        if (host == null) {
            SyncStatus.log(ctx, "Laptop not found. Is MusicPlayer running on the laptop, on the same Wi-Fi?")
            return Result.success()
        }

        try {
            val manifest = fetchManifest(host, 5000, 5000)
            SyncStatus.log(ctx, "Laptop has ${manifest.size} songs")
            val dir = MusicLibrary(ctx).syncDir
            var downloaded = 0
            for ((rawName, size) in manifest) {
                val name = File(rawName).name
                val target = File(dir, name)
                if (!target.exists() || target.length() != size) {
                    downloadFile(host, name, target)
                    downloaded++
                    SyncStatus.log(ctx, "Got $name")
                }
            }
            SyncStatus.setLastGoodHost(ctx, host)
            SyncStatus.log(ctx, if (downloaded == 0) "Up to date" else "Done: $downloaded new")
        } catch (e: Exception) {
            SyncStatus.log(ctx, "Failed: ${describe(e)}")
        }
        // Always success: the periodic job retries next cycle on its own, and a
        // retry storm of failed attempts would only spam the log.
        return Result.success()
    }

    private fun findServer(ctx: Context): String? {
        val tried = linkedSetOf<String>()
        for (candidate in listOf(SyncStatus.manualHost(ctx), SyncStatus.lastGoodHost(ctx))) {
            if (candidate.isNotBlank() && tried.add(candidate)) {
                SyncStatus.log(ctx, "Trying $candidate")
                if (isLaptop(ctx, candidate, verbose = true)) return candidate
            }
        }
        scanSubnet(ctx)?.let {
            SyncStatus.log(ctx, "Found laptop at $it")
            return it
        }
        SyncStatus.log(ctx, "Listening for laptop broadcast...")
        listenForBroadcast()?.let {
            if (isLaptop(ctx, it, verbose = true)) {
                SyncStatus.log(ctx, "Heard laptop at $it")
                return it
            }
        }
        return null
    }

    private fun isLaptop(ctx: Context, host: String, verbose: Boolean): Boolean = try {
        fetchManifest(host, 2000, 2000)
        true
    } catch (e: Exception) {
        if (verbose) SyncStatus.log(ctx, "$host: ${describe(e)}")
        false
    }

    private fun describe(e: Exception): String {
        val base = "${e.javaClass.simpleName}: ${e.message}".take(90)
        return if (e is SocketTimeoutException) "$base (Windows Firewall blocking port $PORT?)" else base
    }

    private fun scanSubnet(ctx: Context): String? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val link = cm.getLinkProperties(cm.activeNetwork)
        val addr = link?.linkAddresses?.firstOrNull { it.address is Inet4Address }
        if (addr == null) {
            SyncStatus.log(ctx, "No Wi-Fi/LAN address on this phone - is Wi-Fi on?")
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

        val pool = Executors.newFixedThreadPool(48)
        try {
            val futures = hosts.map { h -> pool.submit(Callable<String?> { if (portOpen(h, 450)) h else null }) }
            for (f in futures) {
                val found = f.get() ?: continue
                if (isLaptop(ctx, found, verbose = true)) return found
            }
        } finally {
            pool.shutdownNow()
        }
        SyncStatus.log(ctx, "Nothing answered on port $PORT in that range")
        return null
    }

    private fun dotted(i: Int) = "${(i ushr 24) and 0xFF}.${(i ushr 16) and 0xFF}.${(i ushr 8) and 0xFF}.${i and 0xFF}"

    private fun portOpen(host: String, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, PORT), timeoutMs) }
        true
    } catch (e: Exception) {
        false
    }

    private fun listenForBroadcast(): String? {
        // Many phones drop incoming Wi-Fi broadcast packets while the screen is
        // off; a short high-perf Wi-Fi lock for just this window helps. The
        // timeout must exceed the laptop's 5 s broadcast interval or we can
        // miss it entirely.
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "musicplayer:sync")
        wifiLock?.acquire()
        try {
            return DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(BROADCAST_PORT))
                soTimeout = DISCOVERY_TIMEOUT_MS
            }.use { socket ->
                val packet = DatagramPacket(ByteArray(256), 256)
                socket.receive(packet)
                val parts = String(packet.data, 0, packet.length).split(":")
                if (parts.getOrNull(0) == "MUSICSYNC") packet.address.hostAddress else null
            }
        } catch (e: Exception) {
            return null
        } finally {
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

    private fun downloadFile(host: String, name: String, target: File) {
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val conn = URL("http://$host:$PORT/file/$encoded").openConnection() as HttpURLConnection
        val tmp = File(target.parentFile, "${target.name}.part")
        try {
            conn.connectTimeout = 5000
            conn.readTimeout = 15000
            conn.inputStream.use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw java.io.IOException("could not save $name")
        } finally {
            conn.disconnect()
            if (tmp.exists()) tmp.delete()
        }
    }

    companion object {
        private const val PORT = 8721
        private const val BROADCAST_PORT = 8722
        private const val DISCOVERY_TIMEOUT_MS = 7000
        const val UNIQUE_PERIODIC_NAME = "music_sync_periodic"
        const val UNIQUE_ONE_TIME_NAME = "music_sync_now"
    }
}
