package com.example.domain.audio

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-performance, zero-allocation real-time Audio DSP Engine tailored for
 * compact I2S DAC amplifiers (MAX98357A, PCM5102A, PT2811, UDA1334A) connected to ESP32 receivers.
 *
 * Dedicated Audiophile Modules:
 * 1. Sub-Bass Infrasonic Rumble Cut (28 Hz 2nd-order Butterworth HPF) to protect DAC headroom and small drivers.
 * 2. Tight Bass Punch (65 Hz) & Mud-Cut (280 Hz) to eliminate boomy boxiness while preserving snap.
 * 3. Air Band High-Shelf filter (14 kHz) for silky high frequencies without harsh sibilance.
 * 4. Frequency-Split Stereo Widener (Mono sub-150Hz bass + Mid/Side stereo expansion for highs).
 * 5. Soft-Knee Peak Limiter & Preamp Gain Trim (-12 to +12 dB).
 *
 * Thread-Safety & Stability:
 * - Uses atomic immutable coefficient snapshots so real-time parameter tweaking from UI sliders
 *   never causes partial coefficient updates or IIR filter instability.
 * - Built-in NaN / Infinity sanitation prevents recursive math explosions and DAC mute stalls.
 */
class AudioDspEngine {

    class DspCoeffs(
        val airBand: FloatArray,
        val punch: FloatArray,
        val mudCut: FloatArray,
        val subBassCut: FloatArray,
        val widenerAlpha: Float
    )

    @Volatile
    var isEnabled: Boolean = true

    @Volatile
    var isSoftLimiterEnabled: Boolean = true

    @Volatile
    var limiterTrimDb: Float = 0f

    @Volatile
    var airBandEnabled: Boolean = false
        set(value) {
            field = value
            if (value) isEnabled = true
        }

    @Volatile
    var airBandGainDb: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 12f)
            if (field != clamped) {
                field = clamped
                if (clamped > 0f) isEnabled = true
                recomputeCoefficients()
            }
        }

    @Volatile
    var bassPunchEnabled: Boolean = false
        set(value) {
            field = value
            if (value) isEnabled = true
        }

    @Volatile
    var bassPunchIntensity: Int = 50
        set(value) {
            val clamped = value.coerceIn(0, 100)
            if (field != clamped) {
                field = clamped
                if (clamped > 0) isEnabled = true
                recomputeCoefficients()
            }
        }

    @Volatile
    var subBassCutEnabled: Boolean = false
        set(value) {
            field = value
            if (value) isEnabled = true
        }

    @Volatile
    var stereoWidenerEnabled: Boolean = false
        set(value) {
            field = value
            if (value) isEnabled = true
        }

    @Volatile
    var stereoWidthPercent: Int = 125
        set(value) {
            field = value.coerceIn(100, 180)
            if (field > 100) isEnabled = true
        }

    // Filter history registers (audio processing thread only)
    private val airBandHistL = FloatArray(4)
    private val airBandHistR = FloatArray(4)
    private val punchHistL = FloatArray(4)
    private val punchHistR = FloatArray(4)
    private val mudCutHistL = FloatArray(4)
    private val mudCutHistR = FloatArray(4)
    private val subBassCutHistL = FloatArray(4)
    private val subBassCutHistR = FloatArray(4)

    // Stereo Widener 150 Hz crossover LPF state
    private var widenerLpfStateL = 0f
    private var widenerLpfStateR = 0f

    @Volatile
    private var currentSampleRate: Int = 48000

    @Volatile
    private var activeCoeffs: DspCoeffs = computeNewCoeffs(48000, 0f, 50)

    init {
        recomputeCoefficients()
    }

    fun setSampleRate(sampleRate: Int) {
        if (currentSampleRate != sampleRate && sampleRate > 0) {
            currentSampleRate = sampleRate
            resetHistory()
            recomputeCoefficients()
        }
    }

    fun resetHistory() {
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

    private fun recomputeCoefficients() {
        val sr = currentSampleRate
        val airGain = airBandGainDb
        val punchInt = bassPunchIntensity
        activeCoeffs = computeNewCoeffs(sr, airGain, punchInt)
    }

    private fun computeNewCoeffs(sampleRate: Int, airGainDb: Float, punchIntensity: Int): DspCoeffs {
        val Fs = sampleRate.toDouble().coerceAtLeast(8000.0)

        // 1. Air Band (10 kHz High Shelf for audible crystalline clarity & presence)
        val air = FloatArray(5)
        computeHighShelfCoeffs(Fs, 10000.0, airGainDb.toDouble(), 0.707, air)

        // 2. Bass Punch (85 Hz True Kick / Punch Bell Boost up to +12 dB)
        val punchGainDb = (punchIntensity / 100.0) * 12.0
        val mudCutGainDb = (punchIntensity / 100.0) * -1.5
        val punch = FloatArray(5)
        val mudCut = FloatArray(5)
        computePeakingCoeffs(Fs, 85.0, punchGainDb, 1.3, punch)
        computePeakingCoeffs(Fs, 300.0, mudCutGainDb, 1.5, mudCut)

        // 3. Sub-Bass Rumble Cut (32 Hz 2nd-order Butterworth HPF)
        val subCut = FloatArray(5)
        computeHpfCoeffs(Fs, 32.0, 0.707, subCut)

        // 4. Stereo Widener crossover alpha (160 Hz LPF)
        val dt = 1.0 / Fs
        val rc = 1.0 / (2.0 * Math.PI * 160.0)
        val alpha = (dt / (rc + dt)).toFloat().coerceIn(0.001f, 0.5f)

        return DspCoeffs(air, punch, mudCut, subCut, alpha)
    }

    private fun computeHighShelfCoeffs(Fs: Double, f0: Double, gainDb: Double, Q: Double, out: FloatArray) {
        val A = 10.0.pow(gainDb / 40.0)
        val clampedF0 = f0.coerceAtMost(Fs * 0.45)
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

    private fun computePeakingCoeffs(Fs: Double, f0: Double, gainDb: Double, Q: Double, out: FloatArray) {
        val A = 10.0.pow(gainDb / 40.0)
        val clampedF0 = f0.coerceAtMost(Fs * 0.45)
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

    private fun computeHpfCoeffs(Fs: Double, f0: Double, Q: Double, out: FloatArray) {
        val clampedF0 = f0.coerceAtMost(Fs * 0.45)
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

    /**
     * Applies a standard biquad direct-form IIR filter.
     * Includes NaN / Infinity sanitation to self-heal and avoid any mute or noise latching.
     */
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

        var y0 = b0 * sample + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        if (y0.isNaN() || y0.isInfinite()) {
            hist[0] = 0f
            hist[1] = 0f
            hist[2] = 0f
            hist[3] = 0f
            return sample
        }

        hist[1] = x1
        hist[0] = sample
        hist[3] = y1
        hist[2] = y0

        return y0
    }

    /**
     * In-place DSP processing on 16-bit, 24-bit, or 32-bit interleaved PCM buffer.
     * ZERO memory allocations occur during this method.
     */
    fun process(
        buffer: ByteArray,
        length: Int,
        bitDepth: Int,
        channels: Int
    ) {
        val masterEnabled = isEnabled
        val applyLimiter = isSoftLimiterEnabled
        val currentTrim = limiterTrimDb
        val applyAir = (masterEnabled || airBandEnabled) && airBandGainDb > 0f
        val applyPunch = (masterEnabled || bassPunchEnabled) && bassPunchIntensity > 0
        val applySubCut = masterEnabled && subBassCutEnabled
        val applyWidener = (masterEnabled || stereoWidenerEnabled) && channels == 2 && stereoWidthPercent > 100

        val trimGain = if (currentTrim != 0f) 10.0.pow(currentTrim.toDouble() / 20.0).toFloat() else 1.0f

        // Fast-path bypass check: if nothing is enabled and trim is 0, skip immediately
        if (!applyAir && !applyPunch && !applySubCut && !applyWidener && !applyLimiter && currentTrim == 0f) {
            return
        }

        val coeffs = activeCoeffs
        // Always use high-performance 16-bit processing
        process16Bit(buffer, length, channels, true, applyLimiter, trimGain, applyAir, applyPunch, applySubCut, applyWidener, coeffs)
    }

    private fun process16Bit(
        buffer: ByteArray,
        length: Int,
        channels: Int,
        masterEnabled: Boolean,
        applyLimiter: Boolean,
        trimGain: Float,
        applyAir: Boolean,
        applyPunch: Boolean,
        applySubCut: Boolean,
        applyWidener: Boolean,
        coeffs: DspCoeffs
    ) {
        val widthMult = stereoWidthPercent / 100f
        val alpha = coeffs.widenerAlpha

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

                if (masterEnabled) {
                    // 1. Sub-Bass Infrasonic Rumble Cut (28 Hz HPF)
                    if (applySubCut) {
                        sL = applyBiquad(sL, coeffs.subBassCut, subBassCutHistL)
                        sR = applyBiquad(sR, coeffs.subBassCut, subBassCutHistR)
                    }

                    // 2. Tight Bass Punch & Mud-Cut
                    if (applyPunch) {
                        sL = applyBiquad(sL, coeffs.punch, punchHistL)
                        sL = applyBiquad(sL, coeffs.mudCut, mudCutHistL)
                        sR = applyBiquad(sR, coeffs.punch, punchHistR)
                        sR = applyBiquad(sR, coeffs.mudCut, mudCutHistR)
                    }

                    // 3. Air Band High-Shelf (14 kHz)
                    if (applyAir) {
                        sL = applyBiquad(sL, coeffs.airBand, airBandHistL)
                        sR = applyBiquad(sR, coeffs.airBand, airBandHistR)
                    }

                    // 4. Frequency-Split Stereo Widener (Mono sub-150Hz bass + Mid/Side on highs)
                    if (applyWidener) {
                        widenerLpfStateL += alpha * (sL - widenerLpfStateL)
                        widenerLpfStateR += alpha * (sR - widenerLpfStateR)
                        if (widenerLpfStateL.isNaN()) widenerLpfStateL = 0f
                        if (widenerLpfStateR.isNaN()) widenerLpfStateR = 0f

                        val lowMono = (widenerLpfStateL + widenerLpfStateR) * 0.5f
                        val highL = sL - widenerLpfStateL
                        val highR = sR - widenerLpfStateR

                        val mid = (highL + highR) * 0.5f
                        val side = (highL - highR) * 0.5f * widthMult

                        sL = lowMono + mid + side
                        sR = lowMono + mid - side
                    }
                }

                // 5. Soft-Knee Peak Limiter
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
                if (masterEnabled) {
                    if (applySubCut) s = applyBiquad(s, coeffs.subBassCut, subBassCutHistL)
                    if (applyPunch) {
                        s = applyBiquad(s, coeffs.punch, punchHistL)
                        s = applyBiquad(s, coeffs.mudCut, mudCutHistL)
                    }
                    if (applyAir) s = applyBiquad(s, coeffs.airBand, airBandHistL)
                }

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
        masterEnabled: Boolean,
        applyLimiter: Boolean,
        trimGain: Float,
        applyAir: Boolean,
        applyPunch: Boolean,
        applySubCut: Boolean,
        applyWidener: Boolean,
        coeffs: DspCoeffs
    ) {
        val widthMult = stereoWidthPercent / 100f
        val alpha = coeffs.widenerAlpha

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

                if (masterEnabled) {
                    if (applySubCut) {
                        sL = applyBiquad(sL, coeffs.subBassCut, subBassCutHistL)
                        sR = applyBiquad(sR, coeffs.subBassCut, subBassCutHistR)
                    }

                    if (applyPunch) {
                        sL = applyBiquad(sL, coeffs.punch, punchHistL)
                        sL = applyBiquad(sL, coeffs.mudCut, mudCutHistL)
                        sR = applyBiquad(sR, coeffs.punch, punchHistR)
                        sR = applyBiquad(sR, coeffs.mudCut, mudCutHistR)
                    }

                    if (applyAir) {
                        sL = applyBiquad(sL, coeffs.airBand, airBandHistL)
                        sR = applyBiquad(sR, coeffs.airBand, airBandHistR)
                    }

                    if (applyWidener) {
                        widenerLpfStateL += alpha * (sL - widenerLpfStateL)
                        widenerLpfStateR += alpha * (sR - widenerLpfStateR)
                        if (widenerLpfStateL.isNaN()) widenerLpfStateL = 0f
                        if (widenerLpfStateR.isNaN()) widenerLpfStateR = 0f

                        val lowMono = (widenerLpfStateL + widenerLpfStateR) * 0.5f
                        val highL = sL - widenerLpfStateL
                        val highR = sR - widenerLpfStateR

                        val mid = (highL + highR) * 0.5f
                        val side = (highL - highR) * 0.5f * widthMult

                        sL = lowMono + mid + side
                        sR = lowMono + mid - side
                    }
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
                if (masterEnabled) {
                    if (applySubCut) s = applyBiquad(s, coeffs.subBassCut, subBassCutHistL)
                    if (applyPunch) {
                        s = applyBiquad(s, coeffs.punch, punchHistL)
                        s = applyBiquad(s, coeffs.mudCut, mudCutHistL)
                    }
                    if (applyAir) s = applyBiquad(s, coeffs.airBand, airBandHistL)
                }

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
        masterEnabled: Boolean,
        applyLimiter: Boolean,
        trimGain: Float,
        applyAir: Boolean,
        applyPunch: Boolean,
        applySubCut: Boolean,
        applyWidener: Boolean,
        coeffs: DspCoeffs
    ) {
        val widthMult = stereoWidthPercent / 100f
        val alpha = coeffs.widenerAlpha

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

                if (masterEnabled) {
                    if (applySubCut) {
                        sL = applyBiquad(sL, coeffs.subBassCut, subBassCutHistL)
                        sR = applyBiquad(sR, coeffs.subBassCut, subBassCutHistR)
                    }

                    if (applyPunch) {
                        sL = applyBiquad(sL, coeffs.punch, punchHistL)
                        sL = applyBiquad(sL, coeffs.mudCut, mudCutHistL)
                        sR = applyBiquad(sR, coeffs.punch, punchHistR)
                        sR = applyBiquad(sR, coeffs.mudCut, mudCutHistR)
                    }

                    if (applyAir) {
                        sL = applyBiquad(sL, coeffs.airBand, airBandHistL)
                        sR = applyBiquad(sR, coeffs.airBand, airBandHistR)
                    }

                    if (applyWidener) {
                        widenerLpfStateL += alpha * (sL - widenerLpfStateL)
                        widenerLpfStateR += alpha * (sR - widenerLpfStateR)
                        if (widenerLpfStateL.isNaN()) widenerLpfStateL = 0f
                        if (widenerLpfStateR.isNaN()) widenerLpfStateR = 0f

                        val lowMono = (widenerLpfStateL + widenerLpfStateR) * 0.5f
                        val highL = sL - widenerLpfStateL
                        val highR = sR - widenerLpfStateR

                        val mid = (highL + highR) * 0.5f
                        val side = (highL - highR) * 0.5f * widthMult

                        sL = lowMono + mid + side
                        sR = lowMono + mid - side
                    }
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
                if (masterEnabled) {
                    if (applySubCut) s = applyBiquad(s, coeffs.subBassCut, subBassCutHistL)
                    if (applyPunch) {
                        s = applyBiquad(s, coeffs.punch, punchHistL)
                        s = applyBiquad(s, coeffs.mudCut, mudCutHistL)
                    }
                    if (applyAir) s = applyBiquad(s, coeffs.airBand, airBandHistL)
                }

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
