package com.example.domain.audio

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-performance, zero-allocation real-time Audio DSP Engine tailored for
 * compact I2S DAC amplifiers (MAX98357A, PCM5102, PT2811) connected to ESP32-C3 receivers.
 *
 * Features:
 * 1. 5-Band Graphic Equalizer with Biquad IIR filters (100Hz, 300Hz, 1kHz, 3.5kHz, 8kHz).
 * 2. Peak Limiter & Soft Clipper to prevent 0 dBFS amplifier rail saturation and crackling.
 * 3. Bass Boost & Treble Clarity enhancers for compact micro speakers.
 */
class AudioDspEngine {

    companion object {
        const val BAND_COUNT = 5
        val BAND_FREQUENCIES = floatArrayOf(100f, 300f, 1000f, 3500f, 8000f)

        val PRESET_FLAT = floatArrayOf(0f, 0f, 0f, 0f, 0f)
        val PRESET_BASS_BOOST = floatArrayOf(6f, 3f, 0f, 0f, 0f)
        val PRESET_VOCAL = floatArrayOf(-2f, 1f, 4f, 3f, 0f)
        val PRESET_ACOUSTIC = floatArrayOf(3f, 1f, -1f, 2f, 3f)
        val PRESET_ROCK = floatArrayOf(5f, 2f, -1f, 3f, 4f)
    }

    @Volatile
    var isEnabled: Boolean = false

    @Volatile
    var isSoftLimiterEnabled: Boolean = true

    @Volatile
    var bassBoostPercent: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            updateCoefficients()
        }

    @Volatile
    var trebleClarityPercent: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            updateCoefficients()
        }

    private val bandGains = FloatArray(BAND_COUNT) { 0f }

    // Biquad filter coefficients for 5 bands: [b0, b1, b2, a1, a2]
    private val filterCoeffs = Array(BAND_COUNT) { FloatArray(5) }

    // Direct Form II Transposed or Direct Form I state history:
    // Left channel [band][x1, x2, y1, y2], Right channel [band][x1, x2, y1, y2]
    private val leftHistory = Array(BAND_COUNT) { FloatArray(4) }
    private val rightHistory = Array(BAND_COUNT) { FloatArray(4) }

    private var currentSampleRate: Int = 44100

    init {
        updateCoefficients()
    }

    fun setSampleRate(sampleRate: Int) {
        if (currentSampleRate != sampleRate && sampleRate > 0) {
            currentSampleRate = sampleRate
            resetHistory()
            updateCoefficients()
        }
    }

    fun setBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex in 0 until BAND_COUNT) {
            bandGains[bandIndex] = gainDb.coerceIn(-12f, 12f)
            updateCoefficients()
        }
    }

    fun setAllBandGains(gains: FloatArray) {
        for (i in 0 until BAND_COUNT.coerceAtMost(gains.size)) {
            bandGains[i] = gains[i].coerceIn(-12f, 12f)
        }
        updateCoefficients()
    }

    fun getBandGains(): FloatArray = bandGains.clone()

    fun resetHistory() {
        for (b in 0 until BAND_COUNT) {
            leftHistory[b].fill(0f)
            rightHistory[b].fill(0f)
        }
    }

    /**
     * Compute Robert Bristow-Johnson Audio EQ Cookbook biquad coefficients.
     */
    @Synchronized
    private fun updateCoefficients() {
        val Fs = currentSampleRate.toFloat()

        // Combine EQ band gains with dedicated Bass Boost and Treble Clarity
        // Bass Boost adds up to +7dB at 100Hz band
        val effectiveGain0 = (bandGains[0] + (bassBoostPercent / 100f) * 7f).coerceIn(-12f, 15f)
        // Treble Clarity adds up to +7dB at 8000Hz band
        val effectiveGain4 = (bandGains[4] + (trebleClarityPercent / 100f) * 7f).coerceIn(-12f, 15f)

        for (band in 0 until BAND_COUNT) {
            val f0 = BAND_FREQUENCIES[band]
            val gainDb = when (band) {
                0 -> effectiveGain0
                4 -> effectiveGain4
                else -> bandGains[band]
            }

            val A = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * Math.PI * f0 / Fs
            val cosW = cos(w0)
            val sinW = sin(w0)

            when (band) {
                0 -> {
                    // Low Shelf Filter for Bass
                    val Q = 0.707
                    val alpha = sinW / (2.0 * Q)
                    val sqrtA = sqrt(A)
                    val b0 = A * ((A + 1.0) - (A - 1.0) * cosW + 2.0 * sqrtA * alpha)
                    val b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosW)
                    val b2 = A * ((A + 1.0) - (A - 1.0) * cosW - 2.0 * sqrtA * alpha)
                    val a0 = (A + 1.0) + (A - 1.0) * cosW + 2.0 * sqrtA * alpha
                    val a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosW)
                    val a2 = (A + 1.0) + (A - 1.0) * cosW - 2.0 * sqrtA * alpha

                    val invA0 = (1.0 / a0).toFloat()
                    filterCoeffs[band][0] = (b0 * invA0).toFloat()
                    filterCoeffs[band][1] = (b1 * invA0).toFloat()
                    filterCoeffs[band][2] = (b2 * invA0).toFloat()
                    filterCoeffs[band][3] = (a1 * invA0).toFloat()
                    filterCoeffs[band][4] = (a2 * invA0).toFloat()
                }
                4 -> {
                    // High Shelf Filter for Treble
                    val Q = 0.707
                    val alpha = sinW / (2.0 * Q)
                    val sqrtA = sqrt(A)
                    val b0 = A * ((A + 1.0) + (A - 1.0) * cosW + 2.0 * sqrtA * alpha)
                    val b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosW)
                    val b2 = A * ((A + 1.0) + (A - 1.0) * cosW - 2.0 * sqrtA * alpha)
                    val a0 = (A + 1.0) - (A - 1.0) * cosW + 2.0 * sqrtA * alpha
                    val a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosW)
                    val a2 = (A + 1.0) - (A - 1.0) * cosW - 2.0 * sqrtA * alpha

                    val invA0 = (1.0 / a0).toFloat()
                    filterCoeffs[band][0] = (b0 * invA0).toFloat()
                    filterCoeffs[band][1] = (b1 * invA0).toFloat()
                    filterCoeffs[band][2] = (b2 * invA0).toFloat()
                    filterCoeffs[band][3] = (a1 * invA0).toFloat()
                    filterCoeffs[band][4] = (a2 * invA0).toFloat()
                }
                else -> {
                    // Peaking EQ for Mid bands
                    val Q = 1.0
                    val alpha = sinW / (2.0 * Q)
                    val b0 = 1.0 + alpha * A
                    val b1 = -2.0 * cosW
                    val b2 = 1.0 - alpha * A
                    val a0 = 1.0 + alpha / A
                    val a1 = -2.0 * cosW
                    val a2 = 1.0 - alpha / A

                    val invA0 = (1.0 / a0).toFloat()
                    filterCoeffs[band][0] = (b0 * invA0).toFloat()
                    filterCoeffs[band][1] = (b1 * invA0).toFloat()
                    filterCoeffs[band][2] = (b2 * invA0).toFloat()
                    filterCoeffs[band][3] = (a1 * invA0).toFloat()
                    filterCoeffs[band][4] = (a2 * invA0).toFloat()
                }
            }
        }
    }

    /**
     * In-place DSP processing on 16-bit or 24-bit interleaved PCM buffer.
     * ZERO allocations occur during this method.
     */
    fun process(
        buffer: ByteArray,
        length: Int,
        bitDepth: Int,
        channels: Int
    ) {
        val applyEq = isEnabled
        val applyLimiter = isSoftLimiterEnabled

        if (!applyEq && !applyLimiter) return

        if (bitDepth == 16) {
            process16Bit(buffer, length, channels, applyEq, applyLimiter)
        } else if (bitDepth == 24) {
            process24Bit(buffer, length, channels, applyEq, applyLimiter)
        }
    }

    private fun process16Bit(
        buffer: ByteArray,
        length: Int,
        channels: Int,
        applyEq: Boolean,
        applyLimiter: Boolean
    ) {
        var i = 0
        var channelIdx = 0

        while (i + 1 < length) {
            val low = buffer[i].toInt() and 0xFF
            val high = buffer[i + 1].toInt()
            var sample = ((high shl 8) or low).toFloat()

            // Apply 5-Band Biquad EQ
            if (applyEq) {
                val hist = if (channelIdx == 0 || channels == 1) leftHistory else rightHistory
                for (b in 0 until BAND_COUNT) {
                    val coeffs = filterCoeffs[b]
                    val b0 = coeffs[0]
                    val b1 = coeffs[1]
                    val b2 = coeffs[2]
                    val a1 = coeffs[3]
                    val a2 = coeffs[4]

                    val x1 = hist[b][0]
                    val x2 = hist[b][1]
                    val y1 = hist[b][2]
                    val y2 = hist[b][3]

                    val y0 = b0 * sample + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2

                    hist[b][1] = x1
                    hist[b][0] = sample
                    hist[b][3] = y1
                    hist[b][2] = y0

                    sample = y0
                }
            }

            // Apply Hardware Protection: Soft Limiter & Clipper
            // Prevents 0 dBFS rail saturation on MAX98357A / PCM5102 Class-D I2S DACs
            if (applyLimiter) {
                sample = softLimit16(sample)
            } else {
                sample = sample.coerceIn(-32768f, 32767f)
            }

            val outSample = sample.toInt().toShort()
            buffer[i] = (outSample.toInt() and 0xFF).toByte()
            buffer[i + 1] = ((outSample.toInt() shr 8) and 0xFF).toByte()

            i += 2
            channelIdx = (channelIdx + 1) % channels
        }
    }

    private fun process24Bit(
        buffer: ByteArray,
        length: Int,
        channels: Int,
        applyEq: Boolean,
        applyLimiter: Boolean
    ) {
        var i = 0
        var channelIdx = 0

        while (i + 2 < length) {
            val b0 = buffer[i].toInt() and 0xFF
            val b1 = buffer[i + 1].toInt() and 0xFF
            val b2 = buffer[i + 2].toInt()
            var rawSample = (b2 shl 16) or (b1 shl 8) or b0
            if ((rawSample and 0x800000) != 0) {
                rawSample = rawSample or 0xFF000000.toInt()
            }
            var sample = rawSample.toFloat()

            if (applyEq) {
                val hist = if (channelIdx == 0 || channels == 1) leftHistory else rightHistory
                for (b in 0 until BAND_COUNT) {
                    val coeffs = filterCoeffs[b]
                    val b0 = coeffs[0]
                    val b1 = coeffs[1]
                    val b2 = coeffs[2]
                    val a1 = coeffs[3]
                    val a2 = coeffs[4]

                    val x1 = hist[b][0]
                    val x2 = hist[b][1]
                    val y1 = hist[b][2]
                    val y2 = hist[b][3]

                    val y0 = b0 * sample + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2

                    hist[b][1] = x1
                    hist[b][0] = sample
                    hist[b][3] = y1
                    hist[b][2] = y0

                    sample = y0
                }
            }

            if (applyLimiter) {
                sample = softLimit24(sample)
            } else {
                sample = sample.coerceIn(-8388608f, 8388607f)
            }

            val outSample = sample.toInt()
            buffer[i] = (outSample and 0xFF).toByte()
            buffer[i + 1] = ((outSample shr 8) and 0xFF).toByte()
            buffer[i + 2] = ((outSample shr 16) and 0xFF).toByte()

            i += 3
            channelIdx = (channelIdx + 1) % channels
        }
    }

    /**
     * Soft saturation limiter for 16-bit PCM.
     * Threshold = 28000 (~ -1.36 dBFS).
     * Linear below threshold. Smooth asymptotic knee above threshold up to 32700.
     */
    private inline fun softLimit16(x: Float): Float {
        val absX = if (x < 0f) -x else x
        val threshold = 28000f
        val maxCap = 32700f

        if (absX <= threshold) {
            return x
        }

        val excess = absX - threshold
        val span = maxCap - threshold // 4700f
        val normalized = excess / span
        val soft = threshold + span * (normalized / (1f + normalized))

        return if (x < 0f) -soft else soft
    }

    /**
     * Soft saturation limiter for 24-bit PCM.
     * Threshold = 7168000 (~ -1.36 dBFS).
     */
    private inline fun softLimit24(x: Float): Float {
        val absX = if (x < 0f) -x else x
        val threshold = 7168000f
        val maxCap = 8370000f

        if (absX <= threshold) {
            return x
        }

        val excess = absX - threshold
        val span = maxCap - threshold
        val normalized = excess / span
        val soft = threshold + span * (normalized / (1f + normalized))

        return if (x < 0f) -soft else soft
    }
}
