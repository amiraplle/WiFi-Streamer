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
    val httpPort: Int = 8080,
    val protocolMode: ProtocolMode = ProtocolMode.RAW_TCP_SERVER,
    val audioSource: AudioSourceType = AudioSourceType.INTERNAL_AUDIO,
    val sampleRate: Int = 44100,
    val bitDepth: Int = 16,
    val channelCount: Int = 2,
    val headerMode: HeaderMode = HeaderMode.AUTO,
    val bufferPreset: BufferLatencyPreset = BufferLatencyPreset.BALANCED,
    val transmissionVolume: Int = 100,
    val isMuted: Boolean = false,
    val autoReconnect: Boolean = true,
    val maxReconnectRetries: Int = 15,
    val connectionTimeoutMs: Int = 5000,
    val ratePacing: Boolean = true,
    val keepAliveSilence: Boolean = true,
    val amoledDarkTheme: Boolean = true,
    val dspEnabled: Boolean = false,
    val eqPresetName: String = "Flat",
    val eqBand0: Float = 0f,
    val eqBand1: Float = 0f,
    val eqBand2: Float = 0f,
    val eqBand3: Float = 0f,
    val eqBand4: Float = 0f,
    val softLimiterEnabled: Boolean = true,
    val bassBoostPercent: Int = 0,
    val trebleClarityPercent: Int = 0
) {
    val audioFormat: AudioStreamFormat
        get() = AudioStreamFormat(sampleRate, bitDepth, channelCount)

    val eqBandGains: FloatArray
        get() = floatArrayOf(eqBand0, eqBand1, eqBand2, eqBand3, eqBand4)
}

class UserPreferencesRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("c3_streamer_prefs", Context.MODE_PRIVATE)

    private val _userPreferences = MutableStateFlow(loadPreferences())
    val userPreferences: StateFlow<UserPreferences> = _userPreferences.asStateFlow()

    private fun loadPreferences(): UserPreferences {
        return UserPreferences(
            targetHost = prefs.getString("target_host", "c3music.local") ?: "c3music.local",
            targetPort = prefs.getInt("target_port", 50005),
            httpPort = prefs.getInt("http_port", 8080),
            protocolMode = try {
                ProtocolMode.valueOf(prefs.getString("protocol_mode", ProtocolMode.RAW_TCP_SERVER.name) ?: ProtocolMode.RAW_TCP_SERVER.name)
            } catch (_: Exception) {
                ProtocolMode.RAW_TCP_SERVER
            },
            audioSource = AudioSourceType.valueOf(
                prefs.getString("audio_source", AudioSourceType.INTERNAL_AUDIO.name) ?: AudioSourceType.INTERNAL_AUDIO.name
            ),
            sampleRate = prefs.getInt("sample_rate", 44100),
            bitDepth = prefs.getInt("bit_depth", 16),
            channelCount = prefs.getInt("channel_count", 2),
            headerMode = HeaderMode.valueOf(
                prefs.getString("header_mode", HeaderMode.AUTO.name) ?: HeaderMode.AUTO.name
            ),
            bufferPreset = BufferLatencyPreset.valueOf(
                prefs.getString("buffer_preset", BufferLatencyPreset.BALANCED.name) ?: BufferLatencyPreset.BALANCED.name
            ),
            transmissionVolume = prefs.getInt("transmission_volume", 100),
            isMuted = prefs.getBoolean("is_muted", false),
            autoReconnect = prefs.getBoolean("auto_reconnect", true),
            maxReconnectRetries = prefs.getInt("max_reconnect_retries", 15),
            connectionTimeoutMs = prefs.getInt("conn_timeout_ms", 5000),
            ratePacing = prefs.getBoolean("rate_pacing", true),
            keepAliveSilence = prefs.getBoolean("keep_alive_silence", true),
            amoledDarkTheme = prefs.getBoolean("amoled_dark_theme", true),
            dspEnabled = prefs.getBoolean("dsp_enabled", false),
            eqPresetName = prefs.getString("eq_preset_name", "Flat") ?: "Flat",
            eqBand0 = prefs.getFloat("eq_band_0", 0f),
            eqBand1 = prefs.getFloat("eq_band_1", 0f),
            eqBand2 = prefs.getFloat("eq_band_2", 0f),
            eqBand3 = prefs.getFloat("eq_band_3", 0f),
            eqBand4 = prefs.getFloat("eq_band_4", 0f),
            softLimiterEnabled = prefs.getBoolean("soft_limiter_enabled", true),
            bassBoostPercent = prefs.getInt("bass_boost_percent", 0),
            trebleClarityPercent = prefs.getInt("treble_clarity_percent", 0)
        )
    }

    fun updateTarget(host: String, port: Int) {
        prefs.edit().putString("target_host", host).putInt("target_port", port).apply()
        _userPreferences.value = _userPreferences.value.copy(targetHost = host, targetPort = port)
    }

    fun updateAudioSource(source: AudioSourceType) {
        prefs.edit().putString("audio_source", source.name).apply()
        _userPreferences.value = _userPreferences.value.copy(audioSource = source)
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
        prefs.edit().putString("buffer_preset", preset.name).apply()
        _userPreferences.value = _userPreferences.value.copy(bufferPreset = preset)
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

    fun updateNetworkSettings(
        tcpPort: Int,
        httpPort: Int,
        timeoutMs: Int,
        autoReconnect: Boolean,
        maxRetries: Int
    ) {
        prefs.edit()
            .putInt("target_port", tcpPort)
            .putInt("http_port", httpPort)
            .putInt("conn_timeout_ms", timeoutMs)
            .putBoolean("auto_reconnect", autoReconnect)
            .putInt("max_reconnect_retries", maxRetries)
            .apply()
        _userPreferences.value = _userPreferences.value.copy(
            targetPort = tcpPort,
            httpPort = httpPort,
            connectionTimeoutMs = timeoutMs,
            autoReconnect = autoReconnect,
            maxReconnectRetries = maxRetries
        )
    }

    fun updateAmoledDarkTheme(enabled: Boolean) {
        prefs.edit().putBoolean("amoled_dark_theme", enabled).apply()
        _userPreferences.value = _userPreferences.value.copy(amoledDarkTheme = enabled)
    }

    fun updateDspEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("dsp_enabled", enabled).apply()
        _userPreferences.value = _userPreferences.value.copy(dspEnabled = enabled)
    }

    fun updateEqPreset(presetName: String, gains: FloatArray) {
        val editor = prefs.edit().putString("eq_preset_name", presetName)
        if (gains.size >= 5) {
            editor.putFloat("eq_band_0", gains[0])
            editor.putFloat("eq_band_1", gains[1])
            editor.putFloat("eq_band_2", gains[2])
            editor.putFloat("eq_band_3", gains[3])
            editor.putFloat("eq_band_4", gains[4])
        }
        editor.apply()
        _userPreferences.value = _userPreferences.value.copy(
            eqPresetName = presetName,
            eqBand0 = if (gains.size > 0) gains[0] else 0f,
            eqBand1 = if (gains.size > 1) gains[1] else 0f,
            eqBand2 = if (gains.size > 2) gains[2] else 0f,
            eqBand3 = if (gains.size > 3) gains[3] else 0f,
            eqBand4 = if (gains.size > 4) gains[4] else 0f
        )
    }

    fun updateEqBand(bandIndex: Int, gainDb: Float) {
        val clamped = gainDb.coerceIn(-12f, 12f)
        val key = "eq_band_$bandIndex"
        prefs.edit().putFloat(key, clamped).putString("eq_preset_name", "Custom").apply()
        val current = _userPreferences.value
        _userPreferences.value = when (bandIndex) {
            0 -> current.copy(eqBand0 = clamped, eqPresetName = "Custom")
            1 -> current.copy(eqBand1 = clamped, eqPresetName = "Custom")
            2 -> current.copy(eqBand2 = clamped, eqPresetName = "Custom")
            3 -> current.copy(eqBand3 = clamped, eqPresetName = "Custom")
            4 -> current.copy(eqBand4 = clamped, eqPresetName = "Custom")
            else -> current
        }
    }

    fun updateSoftLimiter(enabled: Boolean) {
        prefs.edit().putBoolean("soft_limiter_enabled", enabled).apply()
        _userPreferences.value = _userPreferences.value.copy(softLimiterEnabled = enabled)
    }

    fun updateBassBoost(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        prefs.edit().putInt("bass_boost_percent", clamped).apply()
        _userPreferences.value = _userPreferences.value.copy(bassBoostPercent = clamped)
    }

    fun updateTrebleClarity(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        prefs.edit().putInt("treble_clarity_percent", clamped).apply()
        _userPreferences.value = _userPreferences.value.copy(trebleClarityPercent = clamped)
    }
}
