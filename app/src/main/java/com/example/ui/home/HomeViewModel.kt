package com.example.ui.home

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.domain.audio.AudioDeviceCapabilityDetector
import com.example.model.AudioSourceType
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

    private val _telemetry = MutableStateFlow(StreamTelemetry())
    val telemetry: StateFlow<StreamTelemetry> = _telemetry.asStateFlow()

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
        StreamingService.projectionResultCode = resultCode
        StreamingService.projectionIntentData = data

        val startIntent = Intent(context, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START
            putExtra("result_code", resultCode)
            if (data != null) {
                putExtra("intent_data", data)
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(startIntent)
        } else {
            context.startService(startIntent)
        }
    }

    fun stopStreaming(context: Context) {
        val stopIntent = Intent(context, StreamingService::class.java).apply {
            action = StreamingService.ACTION_STOP
        }
        context.startService(stopIntent)
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

    fun setEqPreset(presetName: String, gains: FloatArray) {
        prefsRepo.updateEqPreset(presetName, gains)
        StreamingService.instance?.updateDspSettings(userPreferences.value)
    }

    fun setEqBand(bandIndex: Int, gainDb: Float) {
        prefsRepo.updateEqBand(bandIndex, gainDb)
        StreamingService.instance?.updateDspSettings(userPreferences.value)
    }

    fun setSoftLimiter(enabled: Boolean) {
        prefsRepo.updateSoftLimiter(enabled)
        StreamingService.instance?.updateDspSettings(userPreferences.value)
    }

    fun setBassBoost(percent: Int) {
        prefsRepo.updateBassBoost(percent)
        StreamingService.instance?.updateDspSettings(userPreferences.value)
    }

    fun setTrebleClarity(percent: Int) {
        prefsRepo.updateTrebleClarity(percent)
        StreamingService.instance?.updateDspSettings(userPreferences.value)
    }
}
