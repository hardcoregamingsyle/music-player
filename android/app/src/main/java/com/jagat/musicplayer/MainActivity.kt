package com.jagat.musicplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            library.pickedFolderUri = uri
            updateStatus()
        }
    }

    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        library = MusicLibrary(this)
        statusText = findViewById(R.id.statusText)

        findViewById<Button>(R.id.startButton).setOnClickListener {
            ContextCompat.startForegroundService(this, Intent(this, PlaybackService::class.java))
        }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, PlaybackService::class.java))
        }
        findViewById<Button>(R.id.pickFolderButton).setOnClickListener {
            pickFolder.launch(null)
        }
        findViewById<Button>(R.id.syncButton).setOnClickListener {
            triggerSyncNow()
        }

        maybeRequestNotificationPermission()
        schedulePeriodicSync()
        updateStatus()
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
            .setRequiredNetworkType(NetworkType.UNMETERED)
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
            .setRequiredNetworkType(NetworkType.UNMETERED)
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

    private fun updateStatus() {
        val folderChosen = library.pickedFolderUri != null
        statusText.text = if (folderChosen) {
            "Extra folder linked. Synced music + that folder will shuffle-play."
        } else {
            "No extra folder linked yet. Synced music from your laptop will shuffle-play."
        }
    }
}
