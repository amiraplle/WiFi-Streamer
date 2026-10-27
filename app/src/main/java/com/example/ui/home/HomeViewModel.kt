package com.example.ui.home

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.domain.audio.AudioDeviceCapabilityDetector
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

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val prefsRepo = AppContainer.getPreferences(application)
    val userPreferences = prefsRepo.userPreferences

    private val playerManager = AppContainer.getPlayerManager(application)
    val playerUiState = playerManager.uiState

    private val _telemetry = MutableStateFlow(StreamTelemetry())
    val telemetry: StateFlow<StreamTelemetry> = _telemetry.asStateFlow()

    val activeSafetyProcess: StateFlow<ActiveStreamProcess> = StreamSafetyCoordinator.activeProcess
    val safetyNotice: StateFlow<String?> = StreamSafetyCoordinator.safetyEventNotice

    val isInternalAudioSupported = AudioDeviceCapabilityDetector.isInternalAudioSupported

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

    fun startStreaming(context: Context, resultCode: Int, data: Intent?) {
        // Immediately close / stop direct player transmission so screen capture has exclusive access
        try {
            playerManager.stopPlayback(stopService = false)
        } catch (_: Exception) {}

        // Ensure preference reflects System Audio
        prefsRepo.updateAudioSource(AudioSourceType.INTERNAL_AUDIO)

        StreamSafetyCoordinator.startSystemStream(context, resultCode, data)
    }

    fun stopPlayerDirectTransmission() {
        playerManager.stopPlayback()
    }

    fun stopStreaming(context: Context) {
        StreamSafetyCoordinator.stopCurrentStream(context)
    }

    fun dismissSafetyNotice() {
        StreamSafetyCoordinator.dismissNotice()
    }

    fun reconnect() {
        val service = StreamingService.instance
        if (service != null) {
            service.reconnect()
        }
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

    fun selectAudioSource(source: AudioSourceType) {
        prefsRepo.updateAudioSource(source)
    }

    fun setDspEnabled(enabled: Boolean) {
        prefsRepo.updateDspEnabled(enabled)
        StreamingService.instance?.updateDspSettings(userPreferences.value)
    }

    fun setSoftLimiter(enabled: Boolean) {
        prefsRepo.updateSoftLimiter(enabled)
        StreamingService.instance?.updateDspSettings(userPreferences.value)
    }

    fun setLimiterTrim(trimDb: Float) {
        prefsRepo.updateLimiterTrim(trimDb)
        StreamingService.instance?.updateDspSettings(userPreferences.value)
    }

    fun setAirBandEnabled(enabled: Boolean) {
        prefsRepo.updateAirBandEnabled(enabled)
        StreamingService.instance?.updateDspSettings(userPreferences.value)
    }

    fun setAirBandGain(gainDb: Float) {
        prefsRepo.updateAirBandGain(gainDb)
        StreamingService.instance?.updateDspSettings(userPreferences.value)
    }

    fun setBassPunchEnabled(enabled: Boolean) {
        prefsRepo.updateBassPunchEnabled(enabled)
        StreamingService.instance?.updateDspSettings(userPreferences.value)
    }

    fun setBassPunchIntensity(intensity: Int) {
        prefsRepo.updateBassPunchIntensity(intensity)
        StreamingService.instance?.updateDspSettings(userPreferences.value)
    }

    fun setSubBassCut(enabled: Boolean) {
        prefsRepo.updateSubBassCut(enabled)
        StreamingService.instance?.updateDspSettings(userPreferences.value)
    }

    fun setStereoWidenerEnabled(enabled: Boolean) {
        prefsRepo.updateStereoWidener(enabled)
        StreamingService.instance?.updateDspSettings(userPreferences.value)
    }

    fun setStereoWidth(widthPercent: Int) {
        prefsRepo.updateStereoWidth(widthPercent)
        StreamingService.instance?.updateDspSettings(userPreferences.value)
    }

    fun setMutePhoneWhileStreaming(enabled: Boolean) {
        prefsRepo.updateMutePhoneWhileStreaming(enabled)
    }

    val supportedFormatCapabilities = AudioDeviceCapabilityDetector.getSupportedPresets()

    fun updateAudioFormat(format: AudioStreamFormat) {
        prefsRepo.updateAudioFormat(format.sampleRate, format.bitDepth, format.channelCount)
    }

    fun updateTarget(host: String, port: Int) {
        prefsRepo.updateTarget(host, port)
    }
}
