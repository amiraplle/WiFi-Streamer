package com.example.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.example.model.AudioSourceType
import com.example.model.AudioStreamFormat
import com.example.model.BufferLatencyPreset
import com.example.model.HeaderMode
import com.example.model.ProtocolMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UserPreferences(
    val targetHost: String = "c3music.local",
    val targetPort: Int = 50005,
    val protocolMode: ProtocolMode = ProtocolMode.RAW_UDP,
    val audioSource: AudioSourceType = AudioSourceType.INTERNAL_AUDIO,
    val sampleRate: Int = 48000,
    val bitDepth: Int = 16,
    val channelCount: Int = 2,
    val headerMode: HeaderMode = HeaderMode.AUTO,
    val bufferPreset: BufferLatencyPreset = BufferLatencyPreset.BALANCED,
    val targetBufferMs: Int = 120,
    val transmissionVolume: Int = 100,
    val isMuted: Boolean = false,
    val autoReconnect: Boolean = true,
    val maxReconnectRetries: Int = 15,
    val connectionTimeoutMs: Int = 5000,
    val ratePacing: Boolean = true,
    val keepAliveSilence: Boolean = true,
    val bitPerfectMode: Boolean = false,
    val ditherEnabled: Boolean = true,
    val dspEnabled: Boolean = false,
    val softLimiterEnabled: Boolean = true,
    val limiterTrimDb: Float = 0f,
    val airBandEnabled: Boolean = false,
    val airBandGainDb: Float = 0f,
    val bassPunchEnabled: Boolean = false,
    val bassPunchIntensity: Int = 50,
    val subBassCutEnabled: Boolean = false,
    val stereoWidenerEnabled: Boolean = false,
    val stereoWidthPercent: Int = 125,
    val mutePhoneWhileStreaming: Boolean = false,
    val wifiQosEnabled: Boolean = true
) {
    val audioFormat: AudioStreamFormat
        get() = AudioStreamFormat(sampleRate, bitDepth, channelCount)
}

class UserPreferencesRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("c3_streamer_prefs", Context.MODE_PRIVATE)

    private val _userPreferences = MutableStateFlow(loadPreferences())
    val userPreferences: StateFlow<UserPreferences> = _userPreferences.asStateFlow()

    private fun loadPreferences(): UserPreferences {
        return UserPreferences(
            targetHost = prefs.getString("target_host", "c3music.local") ?: "c3music.local",
            targetPort = prefs.getInt("target_port", 50005),
            protocolMode = try {
                ProtocolMode.valueOf(prefs.getString("protocol_mode", ProtocolMode.RAW_UDP.name) ?: ProtocolMode.RAW_UDP.name)
            } catch (_: Exception) {
                ProtocolMode.RAW_UDP
            },
            audioSource = run {
                val rawSource = prefs.getString("audio_source", null)
                when (rawSource) {
                    AudioSourceType.INTERNAL_AUDIO.name -> AudioSourceType.INTERNAL_AUDIO
                    AudioSourceType.MICROPHONE.name -> AudioSourceType.MICROPHONE
                    AudioSourceType.DIRECT_AUDIO.name -> AudioSourceType.DIRECT_AUDIO
                    else -> AudioSourceType.INTERNAL_AUDIO
                }
            },
            sampleRate = prefs.getInt("sample_rate", 48000),
            bitDepth = prefs.getInt("bit_depth", 16),
            channelCount = prefs.getInt("channel_count", 2),
            headerMode = try {
                HeaderMode.valueOf(prefs.getString("header_mode", HeaderMode.AUTO.name) ?: HeaderMode.AUTO.name)
            } catch (_: Exception) {
                HeaderMode.AUTO
            },
            bufferPreset = try {
                BufferLatencyPreset.valueOf(prefs.getString("buffer_preset", BufferLatencyPreset.BALANCED.name) ?: BufferLatencyPreset.BALANCED.name)
            } catch (_: Exception) {
                BufferLatencyPreset.BALANCED
            },
            targetBufferMs = prefs.getInt("target_buffer_ms", 120).coerceIn(15, 300),
            transmissionVolume = prefs.getInt("transmission_volume", 100),
            isMuted = prefs.getBoolean("is_muted", false),
            autoReconnect = prefs.getBoolean("auto_reconnect", true),
            maxReconnectRetries = prefs.getInt("max_reconnect_retries", 15),
            connectionTimeoutMs = prefs.getInt("conn_timeout_ms", 5000),
            ratePacing = prefs.getBoolean("rate_pacing", true),
            keepAliveSilence = prefs.getBoolean("keep_alive_silence", true),
            bitPerfectMode = prefs.getBoolean("bit_perfect_mode", false),
            ditherEnabled = prefs.getBoolean("dither_enabled", true),
            dspEnabled = prefs.getBoolean("dsp_enabled", false),
            softLimiterEnabled = prefs.getBoolean("soft_limiter_enabled", true),
            limiterTrimDb = prefs.getFloat("limiter_trim_db", 0f),
            airBandEnabled = prefs.getBoolean("air_band_enabled", false),
            airBandGainDb = prefs.getFloat("air_band_gain_db", 0f),
            bassPunchEnabled = prefs.getBoolean("bass_punch_enabled", false),
            bassPunchIntensity = prefs.getInt("bass_punch_intensity", 50),
            subBassCutEnabled = prefs.getBoolean("sub_bass_cut_enabled", false),
            stereoWidenerEnabled = prefs.getBoolean("stereo_widener_enabled", false),
            stereoWidthPercent = prefs.getInt("stereo_width_percent", 125),
            mutePhoneWhileStreaming = prefs.getBoolean("mute_phone_while_streaming", false),
            wifiQosEnabled = prefs.getBoolean("wifi_qos_enabled", true)
        )
    }

    fun updateTarget(host: String, port: Int) {
        prefs.edit().putString("target_host", host).putInt("target_port", port).apply()
        _userPreferences.value = _userPreferences.value.copy(targetHost = host, targetPort = port)
    }

    fun updateAudioSource(source: AudioSourceType) {
        prefs.edit()
            .putString("audio_source", source.name)
            .putBoolean("user_explicit_internal_audio", source == AudioSourceType.INTERNAL_AUDIO)
            .apply()
        _userPreferences.value = _userPreferences.value.copy(audioSource = source)
    }

    fun updateAudioFormat(format: AudioStreamFormat) {
        updateAudioFormat(format.sampleRate, format.bitDepth, format.channelCount)
    }

    fun updateAudioFormat(sampleRate: Int, bitDepth: Int, channelCount: Int) {
        prefs.edit()
            .putInt("sample_rate", sampleRate)
            .putInt("bit_depth", bitDepth)
            .putInt("channel_count", channelCount)
            .apply()
        _userPreferences.value = _userPreferences.value.copy(
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            channelCount = channelCount
        )
    }

    fun updateHeaderMode(mode: HeaderMode) {
        prefs.edit().putString("header_mode", mode.name).apply()
        _userPreferences.value = _userPreferences.value.copy(headerMode = mode)
    }

    fun updateProtocolMode(mode: ProtocolMode) {
        prefs.edit().putString("protocol_mode", mode.name).apply()
        _userPreferences.value = _userPreferences.value.copy(protocolMode = mode)
    }

    fun updateBufferPreset(preset: BufferLatencyPreset) {
        prefs.edit()
            .putString("buffer_preset", preset.name)
            .putInt("target_buffer_ms", preset.durationMs)
            .apply()
        _userPreferences.value = _userPreferences.value.copy(
            bufferPreset = preset,
            targetBufferMs = preset.durationMs
        )
    }

    fun updateTargetBufferMs(ms: Int) {
        val clamped = ms.coerceIn(15, 300)
        val matchedPreset = when {
            clamped <= 75 -> BufferLatencyPreset.LOW_LATENCY
            clamped <= 200 -> BufferLatencyPreset.BALANCED
            else -> BufferLatencyPreset.SAFE
        }
        prefs.edit()
            .putInt("target_buffer_ms", clamped)
            .putString("buffer_preset", matchedPreset.name)
            .apply()
        _userPreferences.value = _userPreferences.value.copy(
            targetBufferMs = clamped,
            bufferPreset = matchedPreset
        )
    }

    fun updateVolume(volume: Int) {
        val clamped = volume.coerceIn(0, 100)
        prefs.edit().putInt("transmission_volume", clamped).apply()
        _userPreferences.value = _userPreferences.value.copy(transmissionVolume = clamped)
    }

    fun setMuted(muted: Boolean) {
        prefs.edit().putBoolean("is_muted", muted).apply()
        _userPreferences.value = _userPreferences.value.copy(isMuted = muted)
    }

    fun updateRatePacing(enabled: Boolean) {
        prefs.edit().putBoolean("rate_pacing", enabled).apply()
        _userPreferences.value = _userPreferences.value.copy(ratePacing = enabled)
    }

    fun updateWifiQos(enabled: Boolean) {
        prefs.edit().putBoolean("wifi_qos_enabled", enabled).apply()
        _userPreferences.value = _userPreferences.value.copy(wifiQosEnabled = enabled)
    }

    fun updateBitPerfectMode(enabled: Boolean) {
        prefs.edit().putBoolean("bit_perfect_mode", enabled).apply()
        _userPreferences.value = _userPreferences.value.copy(bitPerfectMode = enabled)
    }

    fun updateDitherEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("dither_enabled", enabled).apply()
        _userPreferences.value = _userPreferences.value.copy(ditherEnabled = enabled)
    }

    fun updateKeepAliveSilence(enabled: Boolean) {
        prefs.edit().putBoolean("keep_alive_silence", enabled).apply()
        _userPreferences.value = _userPreferences.value.copy(keepAliveSilence = enabled)
    }

    fun updateNetworkSettings(
        udpPort: Int,
        timeoutMs: Int,
        autoReconnect: Boolean,
        maxRetries: Int
    ) {
        prefs.edit()
            .putInt("target_port", udpPort)
            .putInt("conn_timeout_ms", timeoutMs)
            .putBoolean("auto_reconnect", autoReconnect)
            .putInt("max_reconnect_retries", maxRetries)
            .apply()
        _userPreferences.value = _userPreferences.value.copy(
            targetPort = udpPort,
            connectionTimeoutMs = timeoutMs,
            autoReconnect = autoReconnect,
            maxReconnectRetries = maxRetries
        )
    }

    fun updateDspEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("dsp_enabled", enabled).apply()
        _userPreferences.value = _userPreferences.value.copy(dspEnabled = enabled)
    }

    fun updateSoftLimiter(enabled: Boolean) {
        prefs.edit().putBoolean("soft_limiter_enabled", enabled).apply()
        _userPreferences.value = _userPreferences.value.copy(softLimiterEnabled = enabled)
    }

    fun updateLimiterTrim(trimDb: Float) {
        val clamped = trimDb.coerceIn(-12f, 12f)
        prefs.edit().putFloat("limiter_trim_db", clamped).apply()
        _userPreferences.value = _userPreferences.value.copy(limiterTrimDb = clamped)
    }

    fun updateMutePhoneWhileStreaming(enabled: Boolean) {
        prefs.edit().putBoolean("mute_phone_while_streaming", enabled).apply()
        _userPreferences.value = _userPreferences.value.copy(mutePhoneWhileStreaming = enabled)
    }

    fun updateAirBandEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("air_band_enabled", enabled).apply()
        _userPreferences.value = _userPreferences.value.copy(airBandEnabled = enabled)
    }

    fun updateAirBandGain(gainDb: Float) {
        val clamped = gainDb.coerceIn(0f, 6f)
        prefs.edit().putFloat("air_band_gain_db", clamped).apply()
        _userPreferences.value = _userPreferences.value.copy(airBandGainDb = clamped)
    }

    fun updateBassPunchEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("bass_punch_enabled", enabled).apply()
        _userPreferences.value = _userPreferences.value.copy(bassPunchEnabled = enabled)
    }

    fun updateBassPunchIntensity(intensity: Int) {
        val clamped = intensity.coerceIn(0, 100)
        prefs.edit().putInt("bass_punch_intensity", clamped).apply()
        _userPreferences.value = _userPreferences.value.copy(bassPunchIntensity = clamped)
    }

    fun updateSubBassCut(enabled: Boolean) {
        prefs.edit().putBoolean("sub_bass_cut_enabled", enabled).apply()
        _userPreferences.value = _userPreferences.value.copy(subBassCutEnabled = enabled)
    }

    fun updateStereoWidener(enabled: Boolean) {
        prefs.edit().putBoolean("stereo_widener_enabled", enabled).apply()
        _userPreferences.value = _userPreferences.value.copy(stereoWidenerEnabled = enabled)
    }

    fun updateStereoWidth(widthPercent: Int) {
        val clamped = widthPercent.coerceIn(100, 150)
        prefs.edit().putInt("stereo_width_percent", clamped).apply()
        _userPreferences.value = _userPreferences.value.copy(stereoWidthPercent = clamped)
    }
}
