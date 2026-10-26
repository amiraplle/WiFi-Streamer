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

    fun updateBitPerfectMode(enabled: Boolean) {
        prefsRepo.updateBitPerfectMode(enabled)
    }

    fun updateDitherEnabled(enabled: Boolean) {
        prefsRepo.updateDitherEnabled(enabled)
    }

    fun updateKeepAliveSilence(enabled: Boolean) {
        prefsRepo.updateKeepAliveSilence(enabled)
    }

    fun updateLimiterTrim(trimDb: Float) {
        prefsRepo.updateLimiterTrim(trimDb)
        com.example.service.StreamingService.instance?.updateDspSettings(userPreferences.value)
    }

    fun updateMutePhoneWhileStreaming(enabled: Boolean) {
        prefsRepo.updateMutePhoneWhileStreaming(enabled)
    }

    fun updateDspEnabled(enabled: Boolean) {
        prefsRepo.updateDspEnabled(enabled)
        com.example.service.StreamingService.instance?.updateDspSettings(userPreferences.value)
    }

    fun updateSoftLimiter(enabled: Boolean) {
        prefsRepo.updateSoftLimiter(enabled)
        com.example.service.StreamingService.instance?.updateDspSettings(userPreferences.value)
    }

    fun updateAirBandEnabled(enabled: Boolean) {
        prefsRepo.updateAirBandEnabled(enabled)
        com.example.service.StreamingService.instance?.updateDspSettings(userPreferences.value)
    }

    fun updateAirBandGain(gainDb: Float) {
        prefsRepo.updateAirBandGain(gainDb)
        com.example.service.StreamingService.instance?.updateDspSettings(userPreferences.value)
    }

    fun updateBassPunchEnabled(enabled: Boolean) {
        prefsRepo.updateBassPunchEnabled(enabled)
        com.example.service.StreamingService.instance?.updateDspSettings(userPreferences.value)
    }

    fun updateBassPunchIntensity(intensity: Int) {
        prefsRepo.updateBassPunchIntensity(intensity)
        com.example.service.StreamingService.instance?.updateDspSettings(userPreferences.value)
    }

    fun updateSubBassCut(enabled: Boolean) {
        prefsRepo.updateSubBassCut(enabled)
        com.example.service.StreamingService.instance?.updateDspSettings(userPreferences.value)
    }

    fun updateStereoWidener(enabled: Boolean) {
        prefsRepo.updateStereoWidener(enabled)
        com.example.service.StreamingService.instance?.updateDspSettings(userPreferences.value)
    }

    fun updateStereoWidth(widthPercent: Int) {
        prefsRepo.updateStereoWidth(widthPercent)
        com.example.service.StreamingService.instance?.updateDspSettings(userPreferences.value)
    }

    fun updateBassBoost(percent: Int) {
        prefsRepo.updateBassBoost(percent)
        com.example.service.StreamingService.instance?.updateDspSettings(userPreferences.value)
    }

    fun updateTrebleClarity(percent: Int) {
        prefsRepo.updateTrebleClarity(percent)
        com.example.service.StreamingService.instance?.updateDspSettings(userPreferences.value)
    }

    fun updateEqPreset(name: String, gains: FloatArray) {
        prefsRepo.updateEqPreset(name, gains)
        com.example.service.StreamingService.instance?.updateDspSettings(userPreferences.value)
    }

    fun updateEqBand(bandIndex: Int, gainDb: Float) {
        prefsRepo.updateEqBand(bandIndex, gainDb)
        com.example.service.StreamingService.instance?.updateDspSettings(userPreferences.value)
    }
}
