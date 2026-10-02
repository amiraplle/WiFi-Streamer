package com.example.domain.audio

import java.util.Arrays

object PcmAudioProcessor {

    /**
     * Applies volume scaling and mute in-place directly on the raw PCM byte buffer.
     * ZERO allocations occur during this hot path.
     */
    fun processInPlace(
        buffer: ByteArray,
        length: Int,
        bitDepth: Int,
        volumePercent: Int,
        isMuted: Boolean
    ) {
        if (isMuted || volumePercent <= 0) {
            Arrays.fill(buffer, 0, length, 0.toByte())
            return
        }

        if (volumePercent >= 100) {
            return // No processing needed for full volume
        }

        val volumeFactor = volumePercent / 100f

        when (bitDepth) {
            16 -> {
                var i = 0
                while (i + 1 < length) {
                    val low = buffer[i].toInt() and 0xFF
                    val high = buffer[i + 1].toInt()
                    val sample = ((high shl 8) or low).toShort()
                    val scaled = (sample * volumeFactor).toInt().coerceIn(-32768, 32767).toShort()
                    buffer[i] = (scaled.toInt() and 0xFF).toByte()
                    buffer[i + 1] = ((scaled.toInt() shr 8) and 0xFF).toByte()
                    i += 2
                }
            }
            24 -> {
                var i = 0
                while (i + 2 < length) {
                    val b0 = buffer[i].toInt() and 0xFF
                    val b1 = buffer[i + 1].toInt() and 0xFF
                    val b2 = buffer[i + 2].toInt()
                    var sample = (b2 shl 16) or (b1 shl 8) or b0
                    if ((sample and 0x800000) != 0) {
                        sample = sample or 0xFF000000.toInt()
                    }
                    val scaled = (sample * volumeFactor).toInt().coerceIn(-8388608, 8388607)
                    buffer[i] = (scaled and 0xFF).toByte()
                    buffer[i + 1] = ((scaled shr 8) and 0xFF).toByte()
                    buffer[i + 2] = ((scaled shr 16) and 0xFF).toByte()
                    i += 3
                }
            }
        }
    }

    /**
     * Fast peak detection to determine if the buffer contains pure silence or near-silence.
     */
    fun isBufferSilent(buffer: ByteArray, length: Int, bitDepth: Int, threshold: Int = 10): Boolean {
        if (length == 0) return true

        var maxSample = 0
        when (bitDepth) {
            16 -> {
                var i = 0
                while (i + 1 < length) {
                    val low = buffer[i].toInt() and 0xFF
                    val high = buffer[i + 1].toInt()
                    val sample = Math.abs(((high shl 8) or low).toShort().toInt())
                    if (sample > maxSample) {
                        maxSample = sample
                        if (maxSample > threshold) return false
                    }
                    i += 2
                }
            }
            24 -> {
                var i = 0
                while (i + 2 < length) {
                    val b0 = buffer[i].toInt() and 0xFF
                    val b1 = buffer[i + 1].toInt() and 0xFF
                    val b2 = buffer[i + 2].toInt()
                    var sample = (b2 shl 16) or (b1 shl 8) or b0
                    if ((sample and 0x800000) != 0) {
                        sample = sample or 0xFF000000.toInt()
                    }
                    val absVal = Math.abs(sample)
                    if (absVal > maxSample) {
                        maxSample = absVal
                        if (maxSample > (threshold shl 8)) return false
                    }
                    i += 3
                }
            }
        }
        return maxSample <= threshold
    }
}
