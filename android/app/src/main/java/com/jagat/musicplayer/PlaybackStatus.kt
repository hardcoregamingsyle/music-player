package com.jagat.musicplayer

/**
 * Lets the Activity show live playback state without binding to the service.
 * Written by PlaybackService, polled by MainActivity while visible.
 */
object PlaybackStatus {
    @Volatile var trackName: String? = null
        private set
    @Volatile var isPlaying: Boolean = false
        private set
    @Volatile var isPaused: Boolean = false
        private set
    @Volatile var hasTracks: Boolean = true
        private set

    fun update(trackName: String?, playing: Boolean, paused: Boolean, hasTracks: Boolean = true) {
        this.trackName = trackName
        this.isPlaying = playing
        this.isPaused = paused
        this.hasTracks = hasTracks
    }
}
