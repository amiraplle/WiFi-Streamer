package com.example.ui.browser

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.domain.safety.ActiveStreamProcess
import com.example.domain.safety.StreamSafetyCoordinator
import com.example.model.AudioSourceType
import com.example.model.AudioStreamFormat
import com.example.model.CaptureStatus
import com.example.model.StreamTelemetry
import com.example.model.StreamingState
import com.example.service.StreamingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class MusicQuickLink(
    val title: String,
    val url: String,
    val iconEmoji: String,
    val subtitle: String
)

class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val prefsRepo = AppContainer.getPreferences(application)
    val userPreferences = prefsRepo.userPreferences

    val activeSafetyProcess: StateFlow<ActiveStreamProcess> = StreamSafetyCoordinator.activeProcess
    val safetyNotice: StateFlow<String?> = StreamSafetyCoordinator.safetyEventNotice

    private val _telemetry = MutableStateFlow(StreamTelemetry())
    val telemetry: StateFlow<StreamTelemetry> = _telemetry.asStateFlow()

    // Browser navigation state
    private val _currentUrl = MutableStateFlow("https://music.youtube.com")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _pageTitle = MutableStateFlow("YouTube Music")
    val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    private val _artistName = MutableStateFlow("Smart Audio Stream")
    val artistName: StateFlow<String> = _artistName.asStateFlow()

    private val _artworkUrl = MutableStateFlow<String?>(null)
    val artworkUrl: StateFlow<String?> = _artworkUrl.asStateFlow()

    // Mode: "Compact Smart Artwork" vs "Full Web Browser"
    private val _isSmartArtworkMode = MutableStateFlow(false)
    val isSmartArtworkMode: StateFlow<Boolean> = _isSmartArtworkMode.asStateFlow()

    // Lite Mode (Audio-First, Lightweight Reader / Data-Saver)
    private val _isLiteMode = MutableStateFlow(false)
    val isLiteMode: StateFlow<Boolean> = _isLiteMode.asStateFlow()

    // Ad & Telemetry Blocker (Blocks ads, promo modals, and auto-skips YouTube Music video/audio ads)
    private val _isAdBlockEnabled = MutableStateFlow(true)
    val isAdBlockEnabled: StateFlow<Boolean> = _isAdBlockEnabled.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val quickLinks = listOf(
        MusicQuickLink("YT Music", "https://music.youtube.com", "🎵", "Audiophile UI"),
        MusicQuickLink("YouTube", "https://m.youtube.com", "▶️", "Media Streams"),
        MusicQuickLink("SoundCloud", "https://m.soundcloud.com", "☁️", "Electronic / Indie"),
        MusicQuickLink("Radio Paradise", "https://radioparadise.com", "📻", "Lossless FLAC"),
        MusicQuickLink("Bandcamp", "https://bandcamp.com", "💿", "Direct Artist Audio")
    )

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
    }

    fun startStreaming(context: Context, resultCode: Int = 0, data: Intent? = null) {
        StreamSafetyCoordinator.startBrowserStream(context, resultCode, data)
    }

    fun stopStreaming(context: Context) {
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
    }

    fun navigateTo(url: String) {
        val formatted = if (!url.startsWith("http://") && !url.startsWith("https://")) {
            if (url.contains(".") && !url.contains(" ")) {
                "https://$url"
            } else {
                "https://www.google.com/search?q=" + java.net.URLEncoder.encode(url, "UTF-8")
            }
        } else {
            url
        }
        _currentUrl.value = formatted
    }

    fun setPageInfo(title: String, url: String, artwork: String? = null) {
        _currentUrl.value = url
        _pageTitle.value = title.ifBlank { "Smart Web Audio" }
        _artworkUrl.value = artwork

        // Extract simplified artist/site name
        val domain = try {
            val uri = java.net.URI(url)
            uri.host?.replace("www.", "")?.replace("m.", "") ?: "Web Source"
        } catch (_: Exception) {
            "Web Source"
        }
        _artistName.value = domain
    }

    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }

    fun toggleSmartArtworkMode() {
        _isSmartArtworkMode.value = !_isSmartArtworkMode.value
    }

    fun toggleLiteMode() {
        _isLiteMode.value = !_isLiteMode.value
    }

    fun toggleAdBlock() {
        _isAdBlockEnabled.value = !_isAdBlockEnabled.value
    }

    fun clearCache(webView: android.webkit.WebView?) {
        webView?.clearCache(true)
        android.webkit.WebStorage.getInstance().deleteAllData()
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
}
