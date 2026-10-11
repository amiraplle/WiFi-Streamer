package com.example.ui.browser

import android.app.Application
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.domain.safety.ActiveStreamProcess
import com.example.domain.safety.StreamSafetyCoordinator
import com.example.model.AudioSourceType
import com.example.model.CaptureStatus
import com.example.model.StreamTelemetry
import com.example.model.StreamingState
import com.example.service.StreamingService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class YouTubePreset(
    val title: String,
    val artist: String,
    val query: String,
    val emoji: String,
    val category: String
)

class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val prefsRepo = AppContainer.getPreferences(application)
    val userPreferences = prefsRepo.userPreferences

    val activeSafetyProcess: StateFlow<ActiveStreamProcess> = StreamSafetyCoordinator.activeProcess
    val safetyNotice: StateFlow<String?> = StreamSafetyCoordinator.safetyEventNotice

    private val _telemetry = MutableStateFlow(StreamTelemetry())
    val telemetry: StateFlow<StreamTelemetry> = _telemetry.asStateFlow()

    // Search and Input state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchResults = MutableStateFlow<List<YouTubeTrack>>(emptyList())
    val searchResults: StateFlow<List<YouTubeTrack>> = _searchResults.asStateFlow()

    private val _isResolvingStream = MutableStateFlow(false)
    val isResolvingStream: StateFlow<Boolean> = _isResolvingStream.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    // Native ExoPlayer instance with digital PCM pipe
    val player = DirectYouTubePlayer(application) { pcmBytes ->
        feedAudioPcm(pcmBytes)
    }

    val playerStatus: StateFlow<ExoPlayerStatus> = player.status
    val currentTrack: StateFlow<YouTubeTrack?> = player.currentTrack
    val currentPositionMs: StateFlow<Long> = player.currentPositionMs
    val durationMs: StateFlow<Long> = player.durationMs
    val playerError: StateFlow<String?> = player.errorMessage
    val isPhoneSpeakerEnabled: StateFlow<Boolean> = player.isPhoneSpeakerEnabled

    val curatedPresets = listOf(
        YouTubePreset("Lofi Hip Hop Radio", "Lofi Girl", "Lofi Hip Hop Radio beats to relax/study to", "☕", "Chill & Focus"),
        YouTubePreset("Synthwave Radio", "ChilledCow / Lofi", "Synthwave radio chill synth", "🌆", "Retro Waves"),
        YouTubePreset("Deep Focus Ambient", "Brainwave Music", "Deep focus ambient study music", "🧠", "Audiophile"),
        YouTubePreset("Jazz Cafe BGM", "Cafe Music BGM", "Smooth Jazz Cafe music piano", "🎷", "Acoustic"),
        YouTubePreset("Classical Essentials", "HALIDONMUSIC", "Best of Classical Music Mozart Beethoven", "🎻", "Hi-Res Classical"),
        YouTubePreset("Electronic Dance", "Monstercat", "Monstercat Best Electronic Music", "⚡", "Energy"),
        YouTubePreset("Acoustic Guitar Melodies", "Acoustic Music", "Acoustic guitar instrumental calm", "🎸", "Organic Sound")
    )

    private var searchJob: Job? = null

    init {
        // Poll service telemetry flow periodically when service is alive
        viewModelScope.launch {
            while (isActive) {
                val service = StreamingService.instance
                if (service != null) {
                    _telemetry.value = service.telemetry.value
                } else {
                    val p = userPreferences.value
                    if (_telemetry.value.streamingState != StreamingState.IDLE) {
                        _telemetry.value = StreamTelemetry(
                            streamingState = StreamingState.IDLE,
                            captureStatus = CaptureStatus.IDLE,
                            format = p.audioFormat,
                            audioSource = p.audioSource,
                            targetHost = p.targetHost,
                            targetPort = p.targetPort,
                            volumePercent = p.transmissionVolume,
                            isMuted = p.isMuted
                        )
                    }
                }
                delay(300)
            }
        }

        // Perform initial curated search to populate feed
        performSearch("Lofi Hip Hop Radio")
    }

    fun onSearchQueryChanged(newQuery: String) {
        _searchQuery.value = newQuery
    }

    fun performSearch(query: String = _searchQuery.value) {
        val q = query.trim()
        if (q.isBlank()) return
        _searchQuery.value = q

        // Direct URL check: if user pasted a YouTube / YouTube Music URL
        val directVideoId = YouTubeStreamResolver.extractVideoId(q)
        if (directVideoId != null) {
            playVideoById(directVideoId, q)
            return
        }

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _isSearching.value = true
            _statusMessage.value = "Searching YouTube Music..."
            val results = YouTubeStreamResolver.searchTracks(q)
            _searchResults.value = results
            _isSearching.value = false
            _statusMessage.value = if (results.isEmpty()) "No tracks found. Try another query or paste a URL." else null
        }
    }

    fun playTrack(track: YouTubeTrack) {
        playVideoById(track.id, track.title, track)
    }

    fun playVideoById(videoId: String, title: String, existingTrack: YouTubeTrack? = null) {
        viewModelScope.launch {
            _isResolvingStream.value = true
            _statusMessage.value = "Resolving high-quality ExoPlayer stream..."

            val resolved = YouTubeStreamResolver.resolveAudioStream(videoId)
            _isResolvingStream.value = false

            if (resolved != null) {
                val (streamUrl, track) = resolved
                val finalTrack = existingTrack?.copy(streamUrl = streamUrl) ?: track
                _statusMessage.value = "Streaming: ${finalTrack.title}"

                // Ensure background transmission pipeline is engaged
                val appContext = getApplication<Application>()
                if (_telemetry.value.streamingState != StreamingState.STREAMING) {
                    startStreaming(appContext)
                }

                player.playStream(streamUrl, finalTrack)
            } else {
                _statusMessage.value = "Could not resolve stream for '$title'. Retrying..."
                Toast.makeText(getApplication(), "Could not extract audio stream. Please try another track.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun togglePlayPause() {
        player.togglePlayPause()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    fun startStreaming(context: Context, resultCode: Int = 0, data: Intent? = null) {
        StreamSafetyCoordinator.startBrowserStream(context, resultCode, data)
    }

    fun stopStreaming(context: Context) {
        player.pause()
        StreamSafetyCoordinator.stopCurrentStream(context)
    }

    fun setVolume(volume: Int) {
        prefsRepo.updateVolume(volume)
        StreamingService.instance?.setVolume(volume)
    }

    fun toggleMute() {
        val newMute = !userPreferences.value.isMuted
        prefsRepo.setMuted(newMute)
        StreamingService.instance?.setMuted(newMute)
        player.setMuted(newMute)
    }

    fun togglePhoneSpeaker() {
        player.togglePhoneSpeaker()
    }

    fun feedAudioPcm(bytes: ByteArray) {
        val service = StreamingService.instance
        if (service != null && service.telemetry.value.streamingState == StreamingState.STREAMING) {
            service.feedExternalPcm(bytes)
        }
    }

    fun dismissSafetyNotice() {
        StreamSafetyCoordinator.dismissNotice()
    }

    override fun onCleared() {
        super.onCleared()
        player.release()
    }
}
