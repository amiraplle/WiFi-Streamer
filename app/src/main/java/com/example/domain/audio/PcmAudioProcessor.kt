package com.example.domain.audio

import java.util.Arrays

object PcmAudioProcessor {

    private var xorShiftState = 0x243F6A88

    /**
     * Fast zero-allocation pseudo-random generator producing
     * Triangular Probability Density Function (TPDF) dither in range [-1.0, 1.0].
     */
    private fun nextTpdfDither(): Float {
        xorShiftState = xorShiftState xor (xorShiftState shl 13)
        xorShiftState = xorShiftState xor (xorShiftState ushr 17)
        xorShiftState = xorShiftState xor (xorShiftState shl 5)
        val r1 = (xorShiftState and 0x7FFFFFFF) / 2147483648.0f

        xorShiftState = xorShiftState xor (xorShiftState shl 13)
        xorShiftState = xorShiftState xor (xorShiftState ushr 17)
        xorShiftState = xorShiftState xor (xorShiftState shl 5)
        val r2 = (xorShiftState and 0x7FFFFFFF) / 2147483648.0f

        return r1 - r2
    }

    /**
     * Applies optional DSP (EQ, bass boost, treble clarity, soft limiter)
     * and volume scaling/mute in-place directly on the raw PCM byte buffer.
     * ZERO allocations occur during this hot path.
     *
     * @param bitPerfectMode When true, completely bypasses all DSP and volume math for 100% bit-exact feed.
     * @param ditherEnabled When true, applies TPDF dither during volume scaling to prevent quantization distortion.
     */
    fun processInPlace(
        buffer: ByteArray,
        length: Int,
        bitDepth: Int,
        volumePercent: Int,
        isMuted: Boolean,
        dspEngine: AudioDspEngine? = null,
        channels: Int = 2,
        bitPerfectMode: Boolean = false,
        ditherEnabled: Boolean = true
    ) {
        if (isMuted || volumePercent <= 0) {
            Arrays.fill(buffer, 0, length, 0.toByte())
            return
        }

        // Bit-Perfect Mode: Pure direct bitstream, bypass all DSP, EQ, limiter, and volume math
        if (bitPerfectMode) {
            return
        }

        // Apply real-time EQ, tone shaping, and peak soft-limiter
        dspEngine?.process(buffer, length, bitDepth, channels)

        if (volumePercent >= 100) {
            return // No volume attenuation needed for full volume
        }

        val volumeFactor = volumePercent / 100f

        when (bitDepth) {
            16 -> {
                var i = 0
                while (i + 1 < length) {
                    val low = buffer[i].toInt() and 0xFF
                    val high = buffer[i + 1].toInt()
                    val sample = ((high shl 8) or low).toShort()
                    val dither = if (ditherEnabled) nextTpdfDither() else 0f
                    val scaled = (sample * volumeFactor + dither).toInt().coerceIn(-32768, 32767).toShort()
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
                    val dither = if (ditherEnabled) nextTpdfDither() else 0f
                    val scaled = (sample * volumeFactor + dither).toInt().coerceIn(-8388608, 8388607)
                    buffer[i] = (scaled and 0xFF).toByte()
                    buffer[i + 1] = ((scaled shr 8) and 0xFF).toByte()
                    buffer[i + 2] = ((scaled shr 16) and 0xFF).toByte()
                    i += 3
                }
            }
            32 -> {
                var i = 0
                while (i + 3 < length) {
                    val b0 = buffer[i].toInt() and 0xFF
                    val b1 = buffer[i + 1].toInt() and 0xFF
                    val b2 = buffer[i + 2].toInt() and 0xFF
                    val b3 = buffer[i + 3].toInt()
                    val sample = (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
                    val dither = if (ditherEnabled) (nextTpdfDither() * 256.0f).toLong() else 0L
                    val scaled = ((sample * volumeFactor).toLong() + dither).coerceIn(-2147483648L, 2147483647L).toInt()
                    buffer[i] = (scaled and 0xFF).toByte()
                    buffer[i + 1] = ((scaled shr 8) and 0xFF).toByte()
                    buffer[i + 2] = ((scaled shr 16) and 0xFF).toByte()
                    buffer[i + 3] = ((scaled shr 24) and 0xFF).toByte()
                    i += 4
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
            32 -> {
                var i = 0
                while (i + 3 < length) {
                    val b0 = buffer[i].toInt() and 0xFF
                    val b1 = buffer[i + 1].toInt() and 0xFF
                    val b2 = buffer[i + 2].toInt() and 0xFF
                    val b3 = buffer[i + 3].toInt()
                    val sample = Math.abs((b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0)
                    if (sample > maxSample) {
                        maxSample = sample
                        if (maxSample > (threshold shl 16)) return false
                    }
                    i += 4
                }
            }
        }
        return maxSample <= threshold
    }
}
