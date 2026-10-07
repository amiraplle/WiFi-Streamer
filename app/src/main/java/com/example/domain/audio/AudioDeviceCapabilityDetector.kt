package com.example.domain.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Build
import com.example.model.AudioStreamFormat

data class AudioFormatCapability(
    val format: AudioStreamFormat,
    val isSupported: Boolean,
    val reason: String
)

object AudioDeviceCapabilityDetector {

    val isInternalAudioSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun checkFormatSupport(format: AudioStreamFormat): AudioFormatCapability {
        val encoding = format.androidEncoding
        val channelConfig = format.androidChannelConfig
        val sampleRate = format.sampleRate

        // 24-bit packed and 32-bit PCM support check
        if (format.bitDepth == 24 && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return AudioFormatCapability(
                format = format,
                isSupported = true,
                reason = "UDA1334A/PCM5102A 24-bit PCM (Hi-Fi software framing)"
            )
        }

        if (format.bitDepth == 32 && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return AudioFormatCapability(
                format = format,
                isSupported = true,
                reason = "PCM5102A/UDA1334A 32-bit I2S slot (Hi-Fi software framing)"
            )
        }

        return try {
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
            if (minBuf > 0) {
                AudioFormatCapability(
                    format = format,
                    isSupported = true,
                    reason = "Hardware validated (buffer size: $minBuf bytes)"
                )
            } else {
                AudioFormatCapability(
                    format = format,
                    isSupported = false,
                    reason = "Hardware returned unsupported configuration ($minBuf)"
                )
            }
        } catch (e: Exception) {
            AudioFormatCapability(
                format = format,
                isSupported = false,
                reason = "Hardware exception: ${e.message}"
            )
        }
    }

    fun getSupportedPresets(): List<AudioFormatCapability> {
        return AudioStreamFormat.ALL_PRESETS.map { checkFormatSupport(it) }
    }
}
