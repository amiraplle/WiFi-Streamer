package com.example.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.AppContainer
import com.example.domain.audio.AudioDeviceCapabilityDetector
import com.example.domain.audio.AudioFormatCapability
import com.example.model.AudioStreamFormat
import com.example.model.BufferLatencyPreset
import com.example.model.HeaderMode
import com.example.model.ProtocolMode

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefsRepo = AppContainer.getPreferences(application)
    val userPreferences = prefsRepo.userPreferences

    val supportedFormatCapabilities: List<AudioFormatCapability> =
        AudioDeviceCapabilityDetector.getSupportedPresets()

    fun updateAudioFormat(format: AudioStreamFormat) {
        prefsRepo.updateAudioFormat(format.sampleRate, format.bitDepth, format.channelCount)
    }

    fun updateHeaderMode(mode: HeaderMode) {
        prefsRepo.updateHeaderMode(mode)
    }

    fun updateBufferPreset(preset: BufferLatencyPreset) {
        prefsRepo.updateBufferPreset(preset)
    }

    fun updateNetworkSettings(
        udpPort: Int,
        timeoutMs: Int,
        autoReconnect: Boolean,
        maxRetries: Int
    ) {
        prefsRepo.updateNetworkSettings(udpPort, timeoutMs, autoReconnect, maxRetries)
    }

    fun updateRatePacing(enabled: Boolean) {
        prefsRepo.updateRatePacing(enabled)
    }

    fun updateWifiQos(enabled: Boolean) {
        prefsRepo.updateWifiQos(enabled)
    }

    fun updateMutePhoneWhileStreaming(enabled: Boolean) {
        prefsRepo.updateMutePhoneWhileStreaming(enabled)
    }
}
