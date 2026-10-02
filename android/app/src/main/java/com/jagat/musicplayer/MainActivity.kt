package com.jagat.musicplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var library: MusicLibrary
    private lateinit var statusText: TextView
    private lateinit var playPauseButton: Button
    private lateinit var manualHost: EditText
    private lateinit var syncLog: TextView
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusTick = object : Runnable {
        override fun run() {
            refreshStatusFromService()
            statusHandler.postDelayed(this, 1000)
        }
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            library.pickedFolderUri = uri
            statusText.text = "Extra folder linked."
        }
    }

    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        library = MusicLibrary(this)
        statusText = findViewById(R.id.statusText)
        playPauseButton = findViewById(R.id.playPauseButton)
        manualHost = findViewById(R.id.manualHost)
        syncLog = findViewById(R.id.syncLog)
        manualHost.setText(SyncStatus.manualHost(this))

        playPauseButton.setOnClickListener {
            sendServiceAction(PlaybackService.ACTION_PLAY_PAUSE)
        }
        findViewById<Button>(R.id.skipButton).setOnClickListener {
            sendServiceAction(PlaybackService.ACTION_SKIP)
        }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            sendServiceAction(PlaybackService.ACTION_STOP)
        }
        findViewById<Button>(R.id.pickFolderButton).setOnClickListener {
            pickFolder.launch(null)
        }
        findViewById<Button>(R.id.syncButton).setOnClickListener {
            triggerSyncNow()
        }

        maybeRequestNotificationPermission()
        schedulePeriodicSync()
    }

    override fun onResume() {
        super.onResume()
        statusHandler.post(statusTick)
        // The 15-minute background job is best-effort (Doze/battery managers
        // delay it), so also sync whenever the app is opened, at most every 2 min.
        if (SyncStatus.claimAutoSync(this, 2 * 60 * 1000L)) {
            SyncStatus.log(this, "Auto-sync on open")
            enqueueSyncOnce()
        }
    }

    override fun onPause() {
        super.onPause()
        statusHandler.removeCallbacks(statusTick)
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun refreshStatusFromService() {
        val track = PlaybackStatus.trackName
        statusText.text = when {
            !PlaybackStatus.hasTracks -> "No tracks found yet.\nPick a folder or sync from your laptop."
            track == null -> "Not playing.\nTap Play to start shuffling your music."
            PlaybackStatus.isPaused -> "Paused:\n$track"
            PlaybackStatus.isPlaying -> "Playing:\n$track"
            else -> "Loading:\n$track"
        }
        playPauseButton.text = if (PlaybackStatus.isPlaying) "Pause" else "Play"

        val log = SyncStatus.readLog(this)
        if (log.isNotBlank()) {
            val newestFirst = log.split("\n").reversed().joinToString("\n")
            val shown = "Sync log (newest first):\n$newestFirst"
            if (syncLog.text.toString() != shown) syncLog.text = shown
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // No network constraint on purpose: WorkManager's CONNECTED means "network
    // with validated internet", so a LAN-only or no-internet Wi-Fi would never
    // run the job at all. The worker checks for Wi-Fi itself and logs the result.
    private fun schedulePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SyncWorker.UNIQUE_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun triggerSyncNow() {
        SyncStatus.setManualHost(this, manualHost.text.toString())
        SyncStatus.log(this, "Sync requested")
        enqueueSyncOnce()
    }

    private fun enqueueSyncOnce() {
        WorkManager.getInstance(this).enqueueUniqueWork(
            SyncWorker.UNIQUE_ONE_TIME_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>().build()
        )
    }
}
