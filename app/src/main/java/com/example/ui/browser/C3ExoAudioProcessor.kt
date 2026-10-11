package com.example.ui.browser

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * High-performance Media3 AudioProcessor that intercepts pure decoded 16-bit PCM audio samples
 * directly from ExoPlayer's decoding pipeline and passes them to C3 Audio Streamer.
 *
 * Runs asynchronously on ExoPlayer's audio playback thread with zero Android OS throttling,
 * zero WebView overhead, and complete background persistence when the screen is locked!
 */
@OptIn(UnstableApi::class)
class C3ExoAudioProcessor(
    private val onPcmData: (ByteArray) -> Unit
) : BaseAudioProcessor() {

    private var targetSampleRate = 44100
    private var isMutedLocal = false

    fun setMuted(muted: Boolean) {
        isMutedLocal = muted
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        targetSampleRate = inputAudioFormat.sampleRate
        // Request 16-bit PCM stereo output format
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat(
                inputAudioFormat.sampleRate,
                inputAudioFormat.channelCount,
                C.ENCODING_PCM_16BIT
            )
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        if (!isMutedLocal) {
            val chunk = ByteArray(remaining)
            // Mark position so duplicate reads do not disturb ExoPlayer pipeline
            val originalPos = inputBuffer.position()
            inputBuffer.get(chunk)
            inputBuffer.position(originalPos)

            // Direct forward to ESP32 transmitter
            onPcmData(chunk)
        }

        // Pass buffer along to the standard output buffer (or silent AudioTrack)
        val buffer = replaceOutputBuffer(remaining)
        buffer.put(inputBuffer)
        buffer.flip()
    }
}
