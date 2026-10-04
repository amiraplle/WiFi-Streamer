package com.example.model

import android.media.AudioFormat
import android.os.Build

data class AudioStreamFormat(
    val sampleRate: Int = 44100,
    val bitDepth: Int = 16,
    val channelCount: Int = 2
) {
    val bytesPerSample: Int
        get() = bitDepth / 8

    val frameSizeBytes: Int
        get() = bytesPerSample * channelCount

    val bitrateKbps: Int
        get() = (sampleRate.toLong() * bitDepth * channelCount / 1000L).toInt()

    val androidEncoding: Int
        get() = when (bitDepth) {
            24 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioFormat.ENCODING_PCM_24BIT_PACKED
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
            else -> AudioFormat.ENCODING_PCM_16BIT
        }

    val androidChannelConfig: Int
        get() = when (channelCount) {
            1 -> AudioFormat.CHANNEL_IN_MONO
            else -> AudioFormat.CHANNEL_IN_STEREO
        }

    val displayName: String
        get() = "${sampleRate / 1000.0} kHz • $bitDepth-bit • ${if (channelCount == 2) "Stereo" else "Mono"}"

    companion object {
        val FORMAT_44K_16BIT_STEREO = AudioStreamFormat(44100, 16, 2)
        val FORMAT_48K_16BIT_STEREO = AudioStreamFormat(48000, 16, 2)
        val FORMAT_44K_24BIT_STEREO = AudioStreamFormat(44100, 24, 2)
        val FORMAT_48K_24BIT_STEREO = AudioStreamFormat(48000, 24, 2)

        val ALL_PRESETS = listOf(
            FORMAT_44K_16BIT_STEREO,
            FORMAT_48K_16BIT_STEREO,
            FORMAT_44K_24BIT_STEREO,
            FORMAT_48K_24BIT_STEREO
        )
    }
}
