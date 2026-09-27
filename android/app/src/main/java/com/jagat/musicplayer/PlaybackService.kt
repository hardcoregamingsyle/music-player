package com.jagat.musicplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver

/**
 * Foreground media-playback service: keeps playing across screen-off / lock
 * screen because it's a foreground service tied to a visible notification,
 * not because it holds its own wake lock. Android's audio pipeline keeps the
 * CPU awake only while a track is actually rendering, so idle/no-track states
 * cost nothing extra battery-wise.
 */
class PlaybackService : Service() {

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var library: MusicLibrary
    private var mediaPlayer: MediaPlayer? = null
    private var currentTrack: Track? = null
    private var lastTrackUriString: String? = null
    private var isPaused = false

    override fun onCreate() {
        super.onCreate()
        library = MusicLibrary(this)
        createNotificationChannel()

        mediaSession = MediaSessionCompat(this, "MusicPlayerSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { resumeOrStart() }
                override fun onPause() { pausePlayback() }
                override fun onSkipToNext() { playNextTrack() }
                override fun onStop() { stopSelf() }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> if (isPaused || mediaPlayer == null) resumeOrStart() else pausePlayback()
            ACTION_SKIP -> playNextTrack()
            ACTION_STOP -> stopSelf()
            else -> resumeOrStart()
        }
        return START_STICKY
    }

    private fun resumeOrStart() {
        val mp = mediaPlayer
        if (mp != null && isPaused) {
            mp.start()
            isPaused = false
            updateSessionState(PlaybackStateCompat.STATE_PLAYING)
            startForeground(NOTIFICATION_ID, buildNotification())
        } else if (mp == null) {
            playNextTrack()
        }
    }

    private fun pausePlayback() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                isPaused = true
                updateSessionState(PlaybackStateCompat.STATE_PAUSED)
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }
    }

    private fun playNextTrack() {
        val tracks = library.listTracks()
        if (tracks.isEmpty()) {
            updateSessionState(PlaybackStateCompat.STATE_STOPPED)
            @Suppress("DEPRECATION")
            stopForeground(true)
            return
        }
        val choices = if (tracks.size > 1) {
            tracks.filter { it.uri.toString() != lastTrackUriString }
        } else {
            tracks
        }
        val next = choices.random()
        lastTrackUriString = next.uri.toString()
        currentTrack = next
        isPaused = false

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setOnCompletionListener { playNextTrack() }
            setOnErrorListener { _, _, _ -> playNextTrack(); true }
            setOnPreparedListener {
                it.start()
                updateSessionState(PlaybackStateCompat.STATE_PLAYING)
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            try {
                setDataSource(this@PlaybackService, next.uri)
                prepareAsync()
            } catch (e: Exception) {
                playNextTrack()
            }
        }
    }

    private fun updateSessionState(state: Int) {
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, 0, 1f)
                .build()
        )
    }

    private fun buildNotification(): Notification {
        val playPauseIntent = servicePendingIntent(ACTION_PLAY_PAUSE)
        val skipIntent = servicePendingIntent(ACTION_SKIP)
        val stopIntent = servicePendingIntent(ACTION_STOP)
        val playPauseLabel = if (isPaused) "Play" else "Pause"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTrack?.displayName ?: getString(R.string.app_name))
            .setContentText(if (isPaused) "Paused" else "Playing")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(!isPaused)
            .addAction(0, playPauseLabel, playPauseIntent)
            .addAction(0, "Skip", skipIntent)
            .addAction(0, "Stop", stopIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        mediaPlayer = null
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_PLAY_PAUSE = "com.jagat.musicplayer.PLAY_PAUSE"
        const val ACTION_SKIP = "com.jagat.musicplayer.SKIP"
        const val ACTION_STOP = "com.jagat.musicplayer.STOP"
        private const val CHANNEL_ID = "playback_channel"
        private const val NOTIFICATION_ID = 1
    }
}
