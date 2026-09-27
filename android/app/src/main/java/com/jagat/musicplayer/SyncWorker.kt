package com.jagat.musicplayer

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONArray
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.net.URLEncoder

/**
 * Looks for the laptop's LAN broadcast (see desktop/player.py) for a few
 * seconds, and if found, pulls down any .mp3 the phone doesn't have yet.
 * Only runs on Wi-Fi (see the NetworkType.UNMETERED constraint where this is
 * scheduled) so it never touches mobile data, and does nothing at all when
 * the laptop app isn't running/broadcasting.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val (host, port) = discoverServer() ?: return Result.success()

        return try {
            val library = MusicLibrary(applicationContext)
            val manifest = fetchManifest(host, port)
            val dir = library.syncDir
            for ((name, size) in manifest) {
                val target = File(dir, name)
                if (!target.exists() || target.length() != size) {
                    downloadFile(host, port, name, target)
                }
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun discoverServer(): Pair<String, Int>? {
        return try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(BROADCAST_PORT))
                soTimeout = DISCOVERY_TIMEOUT_MS
            }.use { socket ->
                val buffer = ByteArray(256)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val parts = String(packet.data, 0, packet.length).split(":")
                val host = packet.address.hostAddress
                val port = parts.getOrNull(1)?.toIntOrNull()
                if (parts.getOrNull(0) == "MUSICSYNC" && host != null && port != null) {
                    Pair(host, port)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchManifest(host: String, port: Int): List<Pair<String, Long>> {
        val conn = URL("http://$host:$port/manifest").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            obj.getString("name") to obj.getLong("size")
        }
    }

    private fun downloadFile(host: String, port: Int, name: String, target: File) {
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val conn = URL("http://$host:$port/file/$encoded").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 15000
        val tmp = File(target.parentFile, "${target.name}.part")
        conn.inputStream.use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
        conn.disconnect()
        tmp.renameTo(target)
    }

    companion object {
        private const val BROADCAST_PORT = 8722
        private const val DISCOVERY_TIMEOUT_MS = 4000
        const val UNIQUE_PERIODIC_NAME = "music_sync_periodic"
        const val UNIQUE_ONE_TIME_NAME = "music_sync_now"
    }
}
