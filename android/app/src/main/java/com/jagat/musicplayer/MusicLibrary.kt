package com.jagat.musicplayer

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

data class Track(val displayName: String, val uri: Uri)

/**
 * Combines two sources of mp3s: the app's own folder (auto-filled by [SyncWorker]
 * from the laptop) and one optional folder the user picked by hand via SAF.
 */
class MusicLibrary(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val syncDir: File
        get() {
            val dir = File(context.getExternalFilesDir(null), "synced_music")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    var pickedFolderUri: Uri?
        get() = prefs.getString(KEY_FOLDER_URI, null)?.let { Uri.parse(it) }
        set(value) {
            prefs.edit().putString(KEY_FOLDER_URI, value?.toString()).apply()
        }

    fun listTracks(): List<Track> {
        val tracks = mutableListOf<Track>()

        syncDir.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".mp3") }
            ?.sortedBy { it.name }
            ?.forEach { tracks.add(Track(it.name, Uri.fromFile(it))) }

        pickedFolderUri?.let { treeUri ->
            val tree = DocumentFile.fromTreeUri(context, treeUri)
            tree?.listFiles()
                ?.filter { it.isFile && (it.name ?: "").lowercase().endsWith(".mp3") }
                ?.sortedBy { it.name }
                ?.forEach { doc -> tracks.add(Track(doc.name ?: "track", doc.uri)) }
        }

        return tracks
    }

    companion object {
        private const val PREFS_NAME = "music_player_prefs"
        private const val KEY_FOLDER_URI = "picked_folder_uri"
    }
}
