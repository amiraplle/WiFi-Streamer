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
    val sampleRate: Int = 48000,
    val bitDepth: Int = 16,
    val channels: Int = 2,
    val artworkBytes: ByteArray? = null
) {
    val durationFormatted: String
        get() {
            if (durationMs <= 0) return "--:--"
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }

    val formatFormatted: String
        get() = "${sampleRate / 1000.0}k • $bitDepth-bit • ${if (channels == 2) "Stereo" else "Mono"}"

    val formatDisplay: String
        get() {
            val lowerMime = mimeType.lowercase()
            val lowerUri = uri.toString().lowercase()
            val lowerTitle = title.lowercase()
            return when {
                lowerMime.contains("opus") || lowerUri.endsWith(".opus") || lowerUri.contains(".opus?") || lowerTitle.contains(".opus") -> "OPUS"
                lowerMime.contains("flac") || lowerUri.endsWith(".flac") || lowerTitle.contains(".flac") -> "FLAC"
                lowerMime.contains("wav") || lowerUri.endsWith(".wav") || lowerTitle.contains(".wav") -> "WAV"
                lowerMime.contains("mp3") || lowerUri.endsWith(".mp3") || lowerTitle.contains(".mp3") -> "MP3"
                lowerMime.contains("aac") || lowerMime.contains("mp4") || lowerUri.endsWith(".m4a") || lowerUri.endsWith(".aac") -> "AAC"
                lowerMime.contains("ogg") || lowerUri.endsWith(".ogg") || lowerTitle.contains(".ogg") -> "OGG"
                else -> mimeType.substringAfterLast("/").uppercase().takeIf { it.isNotBlank() && it != "OCTET-STREAM" && it != "*" } ?: "AUDIO"
            }
        }

    val formatBadge: String
        get() = "$formatDisplay • ${sampleRate / 1000.0}k • $bitDepth-bit • ${if (channels == 2) "Stereo" else "Mono"}"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioTrack) return false
        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
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
    val outputFormatInfo: String = "16-bit • 48.0 kHz (Target)",
    val isStreamingActive: Boolean = false,
    val errorMessage: String? = null
)
