package com.example.ui.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ExoPlayerStatus {
    IDLE,
    BUFFERING,
    PLAYING,
    PAUSED,
    ENDED,
    ERROR
}

/**
 * Native ExoPlayer controller equipped with C3ExoAudioProcessor.
 * Decodes YouTube Music AAC/Opus streams to pure 16-bit PCM and delivers to ESP32 without speaker playback.
 */
class DirectYouTubePlayer(
    private val context: Context,
    private val onPcmDataReady: (ByteArray) -> Unit
) {
    private val TAG = "DirectYouTubePlayer"

    private var exoPlayer: ExoPlayer? = null
    private var c3AudioProcessor: C3ExoAudioProcessor? = null

    private val _status = MutableStateFlow(ExoPlayerStatus.IDLE)
    val status: StateFlow<ExoPlayerStatus> = _status.asStateFlow()

    private val _currentTrack = MutableStateFlow<YouTubeTrack?>(null)
    val currentTrack: StateFlow<YouTubeTrack?> = _currentTrack.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Control whether phone speaker plays audio alongside the client device stream
    private val _isPhoneSpeakerEnabled = MutableStateFlow(false)
    val isPhoneSpeakerEnabled: StateFlow<Boolean> = _isPhoneSpeakerEnabled.asStateFlow()

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            exoPlayer?.let { player ->
                if (player.isPlaying) {
                    _currentPositionMs.value = player.currentPosition
                    _durationMs.value = player.duration.coerceAtLeast(0L)
                }
            }
            progressHandler.postDelayed(this, 500)
        }
    }

    @OptIn(UnstableApi::class)
    private fun initPlayerIfNeeded() {
        if (exoPlayer != null) return

        val processor = C3ExoAudioProcessor { pcmBytes ->
            onPcmDataReady(pcmBytes)
        }
        c3AudioProcessor = processor

        val renderersFactory = object : DefaultRenderersFactory(context.applicationContext) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink? {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf<AudioProcessor>(processor))
                    .build()
            }
        }

        exoPlayer = ExoPlayer.Builder(context.applicationContext, renderersFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true // handle audio focus
            )
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build().apply {
                // By default keep local device speaker inaudible (0.001f) so music streams exclusively to ESP32 DAC
                // If user enables phone speaker, volume is set to 1.0f for dual playback
                volume = if (_isPhoneSpeakerEnabled.value) 1.0f else 0.001f

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_IDLE -> _status.value = ExoPlayerStatus.IDLE
                            Player.STATE_BUFFERING -> _status.value = ExoPlayerStatus.BUFFERING
                            Player.STATE_READY -> {
                                _status.value = if (playWhenReady) ExoPlayerStatus.PLAYING else ExoPlayerStatus.PAUSED
                                _durationMs.value = duration.coerceAtLeast(0L)
                            }
                            Player.STATE_ENDED -> _status.value = ExoPlayerStatus.ENDED
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            _status.value = ExoPlayerStatus.PLAYING
                        } else if (_status.value != ExoPlayerStatus.BUFFERING && _status.value != ExoPlayerStatus.IDLE) {
                            _status.value = ExoPlayerStatus.PAUSED
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "ExoPlayer playback error: ${error.message}", error)
                        _status.value = ExoPlayerStatus.ERROR
                        _errorMessage.value = error.localizedMessage ?: "Playback error"
                    }
                })
            }

        progressHandler.post(progressRunnable)
    }

    fun playStream(streamUrl: String, track: YouTubeTrack) {
        initPlayerIfNeeded()
        _errorMessage.value = null
        _currentTrack.value = track
        _status.value = ExoPlayerStatus.BUFFERING

        try {
            val mediaItem = MediaItem.fromUri(streamUrl)
            exoPlayer?.apply {
                stop()
                clearMediaItems()
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ExoPlayer: ${e.message}", e)
            _status.value = ExoPlayerStatus.ERROR
            _errorMessage.value = "Failed to open stream: ${e.message}"
        }
    }

    fun resume() {
        exoPlayer?.play()
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun togglePlayPause() {
        exoPlayer?.let {
            if (it.isPlaying) {
                it.pause()
            } else {
                it.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
    }

    fun setMuted(muted: Boolean) {
        c3AudioProcessor?.setMuted(muted)
    }

    fun setPhoneSpeakerEnabled(enabled: Boolean) {
        _isPhoneSpeakerEnabled.value = enabled
        exoPlayer?.volume = if (enabled) 1.0f else 0.001f
    }

    fun togglePhoneSpeaker() {
        setPhoneSpeakerEnabled(!_isPhoneSpeakerEnabled.value)
    }

    fun stop() {
        exoPlayer?.stop()
        _status.value = ExoPlayerStatus.IDLE
    }

    fun release() {
        progressHandler.removeCallbacks(progressRunnable)
        try {
            exoPlayer?.release()
        } catch (_: Exception) {}
        exoPlayer = null
        c3AudioProcessor = null
        _status.value = ExoPlayerStatus.IDLE
    }
}
