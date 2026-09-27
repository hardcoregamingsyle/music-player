package com.jagat.musicplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var library: MusicLibrary
    private lateinit var statusText: TextView
    private lateinit var playPauseButton: Button
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
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun schedulePeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SyncWorker.UNIQUE_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun triggerSyncNow() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            SyncWorker.UNIQUE_ONE_TIME_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
        statusText.text = "Syncing..."
    }
}
