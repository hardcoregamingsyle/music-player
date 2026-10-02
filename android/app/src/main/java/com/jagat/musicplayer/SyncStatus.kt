package com.jagat.musicplayer

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sync used to fail with no feedback at all. This keeps a short, persistent
 * log (and the laptop address to use) so the UI can show exactly what the
 * last sync attempt did.
 */
object SyncStatus {
    private const val PREFS = "sync_status"
    private const val KEY_LOG = "log"
    private const val KEY_MANUAL_HOST = "manual_host"
    private const val KEY_LAST_GOOD_HOST = "last_good_host"
    private const val KEY_LAST_AUTO = "last_auto_sync_ms"
    private const val MAX_LINES = 12

    @Synchronized
    fun log(context: Context, message: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val lines = (prefs.getString(KEY_LOG, "") ?: "")
            .split("\n")
            .filter { it.isNotBlank() }
            .takeLast(MAX_LINES - 1) + "$stamp $message"
        prefs.edit().putString(KEY_LOG, lines.joinToString("\n")).apply()
    }

    fun readLog(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LOG, "") ?: ""

    fun manualHost(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MANUAL_HOST, "") ?: ""

    fun setManualHost(context: Context, host: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOST, host.trim()).apply()
    }

    /** True at most once per [minGapMs]; used to auto-sync when the app is opened. */
    @Synchronized
    fun claimAutoSync(context: Context, minGapMs: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_AUTO, 0L) < minGapMs) return false
        prefs.edit().putLong(KEY_LAST_AUTO, now).apply()
        return true
    }

    fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    fun lastGoodHost(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_GOOD_HOST, "") ?: ""

    fun setLastGoodHost(context: Context, host: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_GOOD_HOST, host).apply()
    }
}
