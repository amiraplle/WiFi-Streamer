package com.example.domain.audio

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-performance, zero-allocation real-time Audio DSP Engine tailored for
 * compact I2S DAC amplifiers (MAX98357A, PCM5102, PT2811) connected to ESP32 receivers.
 *
 * Modules & Features:
 * 1. 5-Band Graphic Equalizer with Biquad IIR filters (100Hz, 300Hz, 1kHz, 3.5kHz, 8kHz).
 * 2. Dedicated Bass Boost & Treble Clarity enhancers.
 * 3. Air Band High-Shelf filter (14 kHz) for silky high frequencies without harsh sibilance.
 * 4. Tight Bass Punch (65 Hz) & Mud-Cut (280 Hz) to eliminate boomy boxiness while preserving snap.
 * 5. Sub-Bass Infrasonic Rumble Cut (28 Hz 2nd-order Butterworth HPF) to protect DAC headroom and small drivers.
 * 6. Frequency-Split Stereo Widener (Mono sub-150Hz bass + Mid/Side stereo expansion for highs).
 * 7. Soft-Knee Peak Limiter & Preamp Gain Trim (-6 to +6 dB).
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
    var limiterTrimDb: Float = 0f

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

    // --- New Audiophile Sound Tweaks ---

    @Volatile
    var airBandEnabled: Boolean = false

    @Volatile
    var airBandGainDb: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 6f)
            updateCoefficients()
        }

    @Volatile
    var bassPunchEnabled: Boolean = false

    @Volatile
    var bassPunchIntensity: Int = 50
        set(value) {
            field = value.coerceIn(0, 100)
            updateCoefficients()
        }

    @Volatile
    var subBassCutEnabled: Boolean = false

    @Volatile
    var stereoWidenerEnabled: Boolean = false

    @Volatile
    var stereoWidthPercent: Int = 125
        set(value) {
            field = value.coerceIn(100, 150)
        }

    private val bandGains = FloatArray(BAND_COUNT) { 0f }

    // 5-band EQ coefficients: [b0, b1, b2, a1, a2]
    private val filterCoeffs = Array(BAND_COUNT) { FloatArray(5) }
    private val leftHistory = Array(BAND_COUNT) { FloatArray(4) }
    private val rightHistory = Array(BAND_COUNT) { FloatArray(4) }

    // Air Band coefficients & state history
    private val airBandCoeffs = FloatArray(5)
    private val airBandHistL = FloatArray(4)
    private val airBandHistR = FloatArray(4)

    // Bass Punch (65 Hz) & Mud-Cut (280 Hz) coefficients & state history
    private val punchCoeffs = FloatArray(5)
    private val punchHistL = FloatArray(4)
    private val punchHistR = FloatArray(4)

    private val mudCutCoeffs = FloatArray(5)
    private val mudCutHistL = FloatArray(4)
    private val mudCutHistR = FloatArray(4)

    // Sub-Bass Rumble Cut (28 Hz HPF) coefficients & state history
    private val subBassCutCoeffs = FloatArray(5)
    private val subBassCutHistL = FloatArray(4)
    private val subBassCutHistR = FloatArray(4)

    // Stereo Widener 150 Hz crossover LPF state
    private var widenerLpfStateL = 0f
    private var widenerLpfStateR = 0f
    private var widenerLpfAlpha = 0.0194f // Calculated for 150Hz at 48kHz

    private var currentSampleRate: Int = 48000

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
        airBandHistL.fill(0f)
        airBandHistR.fill(0f)
        punchHistL.fill(0f)
        punchHistR.fill(0f)
        mudCutHistL.fill(0f)
        mudCutHistR.fill(0f)
        subBassCutHistL.fill(0f)
        subBassCutHistR.fill(0f)
        widenerLpfStateL = 0f
        widenerLpfStateR = 0f
    }

    /**
     * Compute Robert Bristow-Johnson Audio EQ Cookbook biquad coefficients.
     */
    @Synchronized
    fun updateCoefficients() {
        val Fs = currentSampleRate.toFloat()

        // 1. 5-Band Equalizer Coefficients
        val effectiveGain0 = (bandGains[0] + (bassBoostPercent / 100f) * 7f).coerceIn(-12f, 15f)
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
                    // Low Shelf
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
                    // High Shelf
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
                    // Peaking EQ
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

        // 2. Air Band (14 kHz High Shelf)
        computeHighShelfCoeffs(14000f, airBandGainDb, 0.707, airBandCoeffs)

        // 3. Bass Punch (65 Hz Bell Boost) & Mud-Cut (280 Hz Bell Cut)
        val punchGainDb = (bassPunchIntensity / 100f) * 4.5f
        val mudCutGainDb = (bassPunchIntensity / 100f) * -2.5f
        computePeakingCoeffs(65f, punchGainDb, 1.4, punchCoeffs)
        computePeakingCoeffs(280f, mudCutGainDb, 1.8, mudCutCoeffs)

        // 4. Sub-Bass Rumble Cut (28 Hz 2nd-order Butterworth HPF)
        computeHpfCoeffs(28f, 0.707, subBassCutCoeffs)

        // 5. Stereo Widener crossover alpha (150 Hz LPF)
        val dt = 1.0f / Fs
        val rc = 1.0f / (2.0f * Math.PI.toFloat() * 150.0f)
        widenerLpfAlpha = (dt / (rc + dt)).coerceIn(0.001f, 0.5f)
    }

    private fun computeHighShelfCoeffs(f0: Float, gainDb: Float, Q: Double, out: FloatArray) {
        val Fs = currentSampleRate.toDouble()
        val A = 10.0.pow(gainDb / 40.0)
        val clampedF0 = f0.toDouble().coerceAtMost(Fs * 0.45)
        val w0 = 2.0 * Math.PI * clampedF0 / Fs
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW / (2.0 * Q)
        val sqrtA = sqrt(A)

        val b0 = A * ((A + 1.0) + (A - 1.0) * cosW + 2.0 * sqrtA * alpha)
        val b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosW)
        val b2 = A * ((A + 1.0) + (A - 1.0) * cosW - 2.0 * sqrtA * alpha)
        val a0 = (A + 1.0) - (A - 1.0) * cosW + 2.0 * sqrtA * alpha
        val a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosW)
        val a2 = (A + 1.0) - (A - 1.0) * cosW - 2.0 * sqrtA * alpha

        val invA0 = (1.0 / a0).toFloat()
        out[0] = (b0 * invA0).toFloat()
        out[1] = (b1 * invA0).toFloat()
        out[2] = (b2 * invA0).toFloat()
        out[3] = (a1 * invA0).toFloat()
        out[4] = (a2 * invA0).toFloat()
    }

    private fun computePeakingCoeffs(f0: Float, gainDb: Float, Q: Double, out: FloatArray) {
        val Fs = currentSampleRate.toDouble()
        val A = 10.0.pow(gainDb / 40.0)
        val clampedF0 = f0.toDouble().coerceAtMost(Fs * 0.45)
        val w0 = 2.0 * Math.PI * clampedF0 / Fs
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW / (2.0 * Q)

        val b0 = 1.0 + alpha * A
        val b1 = -2.0 * cosW
        val b2 = 1.0 - alpha * A
        val a0 = 1.0 + alpha / A
        val a1 = -2.0 * cosW
        val a2 = 1.0 - alpha / A

        val invA0 = (1.0 / a0).toFloat()
        out[0] = (b0 * invA0).toFloat()
        out[1] = (b1 * invA0).toFloat()
        out[2] = (b2 * invA0).toFloat()
        out[3] = (a1 * invA0).toFloat()
        out[4] = (a2 * invA0).toFloat()
    }

    private fun computeHpfCoeffs(f0: Float, Q: Double, out: FloatArray) {
        val Fs = currentSampleRate.toDouble()
        val clampedF0 = f0.toDouble().coerceAtMost(Fs * 0.45)
        val w0 = 2.0 * Math.PI * clampedF0 / Fs
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW / (2.0 * Q)

        val b0 = (1.0 + cosW) / 2.0
        val b1 = -(1.0 + cosW)
        val b2 = (1.0 + cosW) / 2.0
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cosW
        val a2 = 1.0 - alpha

        val invA0 = (1.0 / a0).toFloat()
        out[0] = (b0 * invA0).toFloat()
        out[1] = (b1 * invA0).toFloat()
        out[2] = (b2 * invA0).toFloat()
        out[3] = (a1 * invA0).toFloat()
        out[4] = (a2 * invA0).toFloat()
    }

    private inline fun applyBiquad(sample: Float, coeffs: FloatArray, hist: FloatArray): Float {
        val b0 = coeffs[0]
        val b1 = coeffs[1]
        val b2 = coeffs[2]
        val a1 = coeffs[3]
        val a2 = coeffs[4]

        val x1 = hist[0]
        val x2 = hist[1]
        val y1 = hist[2]
        val y2 = hist[3]

        val y0 = b0 * sample + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2

        hist[1] = x1
        hist[0] = sample
        hist[3] = y1
        hist[2] = y0

        return y0
    }

    /**
     * In-place DSP processing on 16-bit, 24-bit, or 32-bit interleaved PCM buffer.
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
        val currentTrim = limiterTrimDb
        val applyAir = airBandEnabled && airBandGainDb > 0f
        val applyPunch = bassPunchEnabled && bassPunchIntensity > 0
        val applySubCut = subBassCutEnabled
        val applyWidener = stereoWidenerEnabled && channels == 2 && stereoWidthPercent > 100

        val trimGain = if (currentTrim != 0f) 10.0.pow(currentTrim.toDouble() / 20.0).toFloat() else 1.0f

        // Fast-path bypass check: if everything is disabled/zero, skip immediately
        if (!applyEq && !applyLimiter && currentTrim == 0f && !applyAir && !applyPunch && !applySubCut && !applyWidener) {
            return
        }

        if (bitDepth == 16) {
            process16Bit(buffer, length, channels, applyEq, applyLimiter, trimGain, applyAir, applyPunch, applySubCut, applyWidener)
        } else if (bitDepth == 24) {
            process24Bit(buffer, length, channels, applyEq, applyLimiter, trimGain, applyAir, applyPunch, applySubCut, applyWidener)
        } else if (bitDepth == 32) {
            process32Bit(buffer, length, channels, applyEq, applyLimiter, trimGain, applyAir, applyPunch, applySubCut, applyWidener)
        }
    }

    private fun process16Bit(
        buffer: ByteArray,
        length: Int,
        channels: Int,
        applyEq: Boolean,
        applyLimiter: Boolean,
        trimGain: Float,
        applyAir: Boolean,
        applyPunch: Boolean,
        applySubCut: Boolean,
        applyWidener: Boolean
    ) {
        val widthMult = stereoWidthPercent / 100f
        val alpha = widenerLpfAlpha

        if (channels == 2) {
            var i = 0
            while (i + 3 < length) {
                val lLow = buffer[i].toInt() and 0xFF
                val lHigh = buffer[i + 1].toInt()
                var sL = ((lHigh shl 8) or lLow).toShort().toFloat()

                val rLow = buffer[i + 2].toInt() and 0xFF
                val rHigh = buffer[i + 3].toInt()
                var sR = ((rHigh shl 8) or rLow).toShort().toFloat()

                // Preamp Trim Gain
                if (trimGain != 1.0f) {
                    sL *= trimGain
                    sR *= trimGain
                }

                // 1. Sub-Bass Infrasonic Rumble Cut (28 Hz HPF)
                if (applySubCut) {
                    sL = applyBiquad(sL, subBassCutCoeffs, subBassCutHistL)
                    sR = applyBiquad(sR, subBassCutCoeffs, subBassCutHistR)
                }

                // 2. Tight Bass Punch & Mud-Cut
                if (applyPunch) {
                    sL = applyBiquad(sL, punchCoeffs, punchHistL)
                    sL = applyBiquad(sL, mudCutCoeffs, mudCutHistL)

                    sR = applyBiquad(sR, punchCoeffs, punchHistR)
                    sR = applyBiquad(sR, mudCutCoeffs, mudCutHistR)
                }

                // 3. 5-Band Equalizer
                if (applyEq) {
                    for (b in 0 until BAND_COUNT) {
                        sL = applyBiquad(sL, filterCoeffs[b], leftHistory[b])
                        sR = applyBiquad(sR, filterCoeffs[b], rightHistory[b])
                    }
                }

                // 4. Air Band High-Shelf (14 kHz)
                if (applyAir) {
                    sL = applyBiquad(sL, airBandCoeffs, airBandHistL)
                    sR = applyBiquad(sR, airBandCoeffs, airBandHistR)
                }

                // 5. Frequency-Split Stereo Widener (Mono sub-150Hz bass + Mid/Side on highs)
                if (applyWidener) {
                    widenerLpfStateL += alpha * (sL - widenerLpfStateL)
                    widenerLpfStateR += alpha * (sR - widenerLpfStateR)

                    val lowMono = (widenerLpfStateL + widenerLpfStateR) * 0.5f
                    val highL = sL - widenerLpfStateL
                    val highR = sR - widenerLpfStateR

                    val mid = (highL + highR) * 0.5f
                    val side = (highL - highR) * 0.5f * widthMult

                    sL = lowMono + mid + side
                    sR = lowMono + mid - side
                }

                // 6. Soft-Knee Peak Limiter
                if (applyLimiter) {
                    sL = softLimit16(sL)
                    sR = softLimit16(sR)
                } else {
                    sL = sL.coerceIn(-32768f, 32767f)
                    sR = sR.coerceIn(-32768f, 32767f)
                }

                val outL = sL.toInt().toShort()
                buffer[i] = (outL.toInt() and 0xFF).toByte()
                buffer[i + 1] = ((outL.toInt() shr 8) and 0xFF).toByte()

                val outR = sR.toInt().toShort()
                buffer[i + 2] = (outR.toInt() and 0xFF).toByte()
                buffer[i + 3] = ((outR.toInt() shr 8) and 0xFF).toByte()

                i += 4
            }
        } else {
            // Mono fallback
            var i = 0
            while (i + 1 < length) {
                val low = buffer[i].toInt() and 0xFF
                val high = buffer[i + 1].toInt()
                var s = ((high shl 8) or low).toShort().toFloat()

                if (trimGain != 1.0f) s *= trimGain
                if (applySubCut) s = applyBiquad(s, subBassCutCoeffs, subBassCutHistL)
                if (applyPunch) {
                    s = applyBiquad(s, punchCoeffs, punchHistL)
                    s = applyBiquad(s, mudCutCoeffs, mudCutHistL)
                }
                if (applyEq) {
                    for (b in 0 until BAND_COUNT) s = applyBiquad(s, filterCoeffs[b], leftHistory[b])
                }
                if (applyAir) s = applyBiquad(s, airBandCoeffs, airBandHistL)

                if (applyLimiter) s = softLimit16(s) else s = s.coerceIn(-32768f, 32767f)

                val out = s.toInt().toShort()
                buffer[i] = (out.toInt() and 0xFF).toByte()
                buffer[i + 1] = ((out.toInt() shr 8) and 0xFF).toByte()
                i += 2
            }
        }
    }

    private fun process24Bit(
        buffer: ByteArray,
        length: Int,
        channels: Int,
        applyEq: Boolean,
        applyLimiter: Boolean,
        trimGain: Float,
        applyAir: Boolean,
        applyPunch: Boolean,
        applySubCut: Boolean,
        applyWidener: Boolean
    ) {
        val widthMult = stereoWidthPercent / 100f
        val alpha = widenerLpfAlpha

        if (channels == 2) {
            var i = 0
            while (i + 5 < length) {
                val l0 = buffer[i].toInt() and 0xFF
                val l1 = buffer[i + 1].toInt() and 0xFF
                val l2 = buffer[i + 2].toInt()
                var rawL = (l2 shl 16) or (l1 shl 8) or l0
                if ((rawL and 0x800000) != 0) rawL = rawL or 0xFF000000.toInt()
                var sL = rawL.toFloat()

                val r0 = buffer[i + 3].toInt() and 0xFF
                val r1 = buffer[i + 4].toInt() and 0xFF
                val r2 = buffer[i + 5].toInt()
                var rawR = (r2 shl 16) or (r1 shl 8) or r0
                if ((rawR and 0x800000) != 0) rawR = rawR or 0xFF000000.toInt()
                var sR = rawR.toFloat()

                if (trimGain != 1.0f) {
                    sL *= trimGain
                    sR *= trimGain
                }

                if (applySubCut) {
                    sL = applyBiquad(sL, subBassCutCoeffs, subBassCutHistL)
                    sR = applyBiquad(sR, subBassCutCoeffs, subBassCutHistR)
                }

                if (applyPunch) {
                    sL = applyBiquad(sL, punchCoeffs, punchHistL)
                    sL = applyBiquad(sL, mudCutCoeffs, mudCutHistL)
                    sR = applyBiquad(sR, punchCoeffs, punchHistR)
                    sR = applyBiquad(sR, mudCutCoeffs, mudCutHistR)
                }

                if (applyEq) {
                    for (b in 0 until BAND_COUNT) {
                        sL = applyBiquad(sL, filterCoeffs[b], leftHistory[b])
                        sR = applyBiquad(sR, filterCoeffs[b], rightHistory[b])
                    }
                }

                if (applyAir) {
                    sL = applyBiquad(sL, airBandCoeffs, airBandHistL)
                    sR = applyBiquad(sR, airBandCoeffs, airBandHistR)
                }

                if (applyWidener) {
                    widenerLpfStateL += alpha * (sL - widenerLpfStateL)
                    widenerLpfStateR += alpha * (sR - widenerLpfStateR)

                    val lowMono = (widenerLpfStateL + widenerLpfStateR) * 0.5f
                    val highL = sL - widenerLpfStateL
                    val highR = sR - widenerLpfStateR

                    val mid = (highL + highR) * 0.5f
                    val side = (highL - highR) * 0.5f * widthMult

                    sL = lowMono + mid + side
                    sR = lowMono + mid - side
                }

                if (applyLimiter) {
                    sL = softLimit24(sL)
                    sR = softLimit24(sR)
                } else {
                    sL = sL.coerceIn(-8388608f, 8388607f)
                    sR = sR.coerceIn(-8388608f, 8388607f)
                }

                val outL = sL.toInt()
                buffer[i] = (outL and 0xFF).toByte()
                buffer[i + 1] = ((outL shr 8) and 0xFF).toByte()
                buffer[i + 2] = ((outL shr 16) and 0xFF).toByte()

                val outR = sR.toInt()
                buffer[i + 3] = (outR and 0xFF).toByte()
                buffer[i + 4] = ((outR shr 8) and 0xFF).toByte()
                buffer[i + 5] = ((outR shr 16) and 0xFF).toByte()

                i += 6
            }
        } else {
            var i = 0
            while (i + 2 < length) {
                val b0 = buffer[i].toInt() and 0xFF
                val b1 = buffer[i + 1].toInt() and 0xFF
                val b2 = buffer[i + 2].toInt()
                var raw = (b2 shl 16) or (b1 shl 8) or b0
                if ((raw and 0x800000) != 0) raw = raw or 0xFF000000.toInt()
                var s = raw.toFloat()

                if (trimGain != 1.0f) s *= trimGain
                if (applySubCut) s = applyBiquad(s, subBassCutCoeffs, subBassCutHistL)
                if (applyPunch) {
                    s = applyBiquad(s, punchCoeffs, punchHistL)
                    s = applyBiquad(s, mudCutCoeffs, mudCutHistL)
                }
                if (applyEq) {
                    for (b in 0 until BAND_COUNT) s = applyBiquad(s, filterCoeffs[b], leftHistory[b])
                }
                if (applyAir) s = applyBiquad(s, airBandCoeffs, airBandHistL)

                if (applyLimiter) s = softLimit24(s) else s = s.coerceIn(-8388608f, 8388607f)

                val out = s.toInt()
                buffer[i] = (out and 0xFF).toByte()
                buffer[i + 1] = ((out shr 8) and 0xFF).toByte()
                buffer[i + 2] = ((out shr 16) and 0xFF).toByte()
                i += 3
            }
        }
    }

    private fun process32Bit(
        buffer: ByteArray,
        length: Int,
        channels: Int,
        applyEq: Boolean,
        applyLimiter: Boolean,
        trimGain: Float,
        applyAir: Boolean,
        applyPunch: Boolean,
        applySubCut: Boolean,
        applyWidener: Boolean
    ) {
        val widthMult = stereoWidthPercent / 100f
        val alpha = widenerLpfAlpha

        if (channels == 2) {
            var i = 0
            while (i + 7 < length) {
                val l0 = buffer[i].toInt() and 0xFF
                val l1 = buffer[i + 1].toInt() and 0xFF
                val l2 = buffer[i + 2].toInt() and 0xFF
                val l3 = buffer[i + 3].toInt()
                var sL = ((l3 shl 24) or (l2 shl 16) or (l1 shl 8) or l0).toFloat()

                val r0 = buffer[i + 4].toInt() and 0xFF
                val r1 = buffer[i + 5].toInt() and 0xFF
                val r2 = buffer[i + 6].toInt() and 0xFF
                val r3 = buffer[i + 7].toInt()
                var sR = ((r3 shl 24) or (r2 shl 16) or (r1 shl 8) or r0).toFloat()

                if (trimGain != 1.0f) {
                    sL *= trimGain
                    sR *= trimGain
                }

                if (applySubCut) {
                    sL = applyBiquad(sL, subBassCutCoeffs, subBassCutHistL)
                    sR = applyBiquad(sR, subBassCutCoeffs, subBassCutHistR)
                }

                if (applyPunch) {
                    sL = applyBiquad(sL, punchCoeffs, punchHistL)
                    sL = applyBiquad(sL, mudCutCoeffs, mudCutHistL)
                    sR = applyBiquad(sR, punchCoeffs, punchHistR)
                    sR = applyBiquad(sR, mudCutCoeffs, mudCutHistR)
                }

                if (applyEq) {
                    for (b in 0 until BAND_COUNT) {
                        sL = applyBiquad(sL, filterCoeffs[b], leftHistory[b])
                        sR = applyBiquad(sR, filterCoeffs[b], rightHistory[b])
                    }
                }

                if (applyAir) {
                    sL = applyBiquad(sL, airBandCoeffs, airBandHistL)
                    sR = applyBiquad(sR, airBandCoeffs, airBandHistR)
                }

                if (applyWidener) {
                    widenerLpfStateL += alpha * (sL - widenerLpfStateL)
                    widenerLpfStateR += alpha * (sR - widenerLpfStateR)

                    val lowMono = (widenerLpfStateL + widenerLpfStateR) * 0.5f
                    val highL = sL - widenerLpfStateL
                    val highR = sR - widenerLpfStateR

                    val mid = (highL + highR) * 0.5f
                    val side = (highL - highR) * 0.5f * widthMult

                    sL = lowMono + mid + side
                    sR = lowMono + mid - side
                }

                val outL = sL.coerceIn(-2147483648f, 2147483647f).toLong().toInt()
                buffer[i] = (outL and 0xFF).toByte()
                buffer[i + 1] = ((outL shr 8) and 0xFF).toByte()
                buffer[i + 2] = ((outL shr 16) and 0xFF).toByte()
                buffer[i + 3] = ((outL shr 24) and 0xFF).toByte()

                val outR = sR.coerceIn(-2147483648f, 2147483647f).toLong().toInt()
                buffer[i + 4] = (outR and 0xFF).toByte()
                buffer[i + 5] = ((outR shr 8) and 0xFF).toByte()
                buffer[i + 6] = ((outR shr 16) and 0xFF).toByte()
                buffer[i + 7] = ((outR shr 24) and 0xFF).toByte()

                i += 8
            }
        } else {
            var i = 0
            while (i + 3 < length) {
                val b0 = buffer[i].toInt() and 0xFF
                val b1 = buffer[i + 1].toInt() and 0xFF
                val b2 = buffer[i + 2].toInt() and 0xFF
                val b3 = buffer[i + 3].toInt()
                var s = ((b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0).toFloat()

                if (trimGain != 1.0f) s *= trimGain
                if (applySubCut) s = applyBiquad(s, subBassCutCoeffs, subBassCutHistL)
                if (applyPunch) {
                    s = applyBiquad(s, punchCoeffs, punchHistL)
                    s = applyBiquad(s, mudCutCoeffs, mudCutHistL)
                }
                if (applyEq) {
                    for (b in 0 until BAND_COUNT) s = applyBiquad(s, filterCoeffs[b], leftHistory[b])
                }
                if (applyAir) s = applyBiquad(s, airBandCoeffs, airBandHistL)

                val out = s.coerceIn(-2147483648f, 2147483647f).toLong().toInt()
                buffer[i] = (out and 0xFF).toByte()
                buffer[i + 1] = ((out shr 8) and 0xFF).toByte()
                buffer[i + 2] = ((out shr 16) and 0xFF).toByte()
                buffer[i + 3] = ((out shr 24) and 0xFF).toByte()
                i += 4
            }
        }
    }

    /**
     * Soft saturation limiter for 16-bit PCM.
     * Threshold = 28000 (~ -1.36 dBFS).
     */
    private inline fun softLimit16(x: Float): Float {
        val absX = if (x < 0f) -x else x
        val threshold = 28000f
        val maxCap = 32700f

        if (absX <= threshold) return x

        val excess = absX - threshold
        val span = maxCap - threshold
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

        if (absX <= threshold) return x

        val excess = absX - threshold
        val span = maxCap - threshold
        val normalized = excess / span
        val soft = threshold + span * (normalized / (1f + normalized))

        return if (x < 0f) -soft else soft
    }
}
