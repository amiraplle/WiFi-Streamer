package com.example.model

import android.media.AudioFormat
import android.os.Build

data class AudioStreamFormat(
    val sampleRate: Int = 48000,
    val bitDepth: Int = 16,
    val channelCount: Int = 2
) {
    val bytesPerSample: Int
        get() = 2

    val frameSizeBytes: Int
        get() = 4

    val bitrateKbps: Int
        get() = 1536

    val androidEncoding: Int
        get() = AudioFormat.ENCODING_PCM_16BIT

    val androidChannelConfig: Int
        get() = AudioFormat.CHANNEL_IN_STEREO

    val displayName: String
        get() = "48.0 kHz • 16-bit • Stereo"

    val dacCompatibility: String
        get() = "PCM5102A & UDA1334A (16-bit 48 kHz Standard)"

    companion object {
        val FORMAT_48K_16BIT_STEREO = AudioStreamFormat(48000, 16, 2)

        // Aliases for compatibility
        val FORMAT_48K_24BIT_STEREO = FORMAT_48K_16BIT_STEREO
        val FORMAT_48K_32BIT_STEREO = FORMAT_48K_16BIT_STEREO

        val ALL_PRESETS = listOf(
            FORMAT_48K_16BIT_STEREO
        )
    }
}

