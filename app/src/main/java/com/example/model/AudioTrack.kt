package com.example.model

import android.net.Uri

data class AudioTrack(
    val id: String,
    val title: String,
    val artist: String = "Unknown Artist",
    val album: String = "",
    val durationMs: Long = 0L,
    val uri: Uri,
    val mimeType: String = "",
    val sampleRate: Int = 44100,
    val bitDepth: Int = 16,
    val channels: Int = 2
) {
    val durationFormatted: String
        get() {
            if (durationMs <= 0) return "--:--"
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }

    val formatBadge: String
        get() = "${sampleRate / 1000.0}k • $bitDepth-bit • ${if (channels == 2) "Stereo" else "Mono"}"
}

enum class PlayerState {
    IDLE,
    BUFFERING,
    PLAYING,
    PAUSED,
    ERROR
}

enum class PlayerRepeatMode {
    OFF,
    ALL,
    ONE
}

data class PlayerUiState(
    val currentTrack: AudioTrack? = null,
    val playlist: List<AudioTrack> = emptyList(),
    val currentIndex: Int = -1,
    val playerState: PlayerState = PlayerState.IDLE,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isShuffle: Boolean = false,
    val repeatMode: PlayerRepeatMode = PlayerRepeatMode.OFF,
    val sourceFormatInfo: String = "No audio loaded",
    val outputFormatInfo: String = "16-bit • 44.1 kHz (Target)",
    val isStreamingActive: Boolean = false,
    val errorMessage: String? = null
)
