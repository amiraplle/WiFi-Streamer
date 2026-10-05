package com.example.data.network

import android.util.Log
import com.example.domain.audio.AudioInterruptionLogger
import com.example.domain.audio.AudioRingBuffer
import com.example.model.AudioStreamFormat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * Precision rate-regulated audio transmitter.
 * Pulls PCM audio from [AudioRingBuffer] and feeds the TCP socket or HTTP server at the exact
 * real-time playback clock, protecting the ESP32-C3 I2S DMA buffer from burst overflows and starvations.
 *
 * Designed for extreme battery efficiency:
 * - Uses zero heap allocations in the hot loop
 * - Employs LockSupport.parkNanos for nanosecond-level sleep with zero CPU spin
 * - Resets timing on network stalls to strictly prevent catch-up bursts
 */
class PacedAudioTransmitter(
    private val ringBuffer: AudioRingBuffer,
    private val sendChunkToTransport: (buffer: ByteArray, offset: Int, length: Int) -> Boolean
) {
    private val TAG = "PacedAudioTransmitter"

    private val isRunning = AtomicBoolean(false)
    private var transmitterThread: Thread? = null

    // Pre-allocated chunk buffer for transmission (e.g. 2048 bytes ~ 11.6ms @ 44.1kHz stereo)
    private val chunkBuffer = ByteArray(2048)
    private val silenceChunk = ByteArray(2048)

    @Volatile
    var ratePacingEnabled: Boolean = true

    @Volatile
    var currentFormat: AudioStreamFormat = AudioStreamFormat.FORMAT_44K_16BIT_STEREO

    // Target jitter buffer pre-roll in bytes (~150ms default = 26,460 bytes for 44.1kHz stereo)
    @Volatile
    var targetPreRollBytes: Int = 26460

    fun setTargetLatencyPreset(preset: com.example.model.BufferLatencyPreset, format: AudioStreamFormat) {
        val bytesPerSec = format.sampleRate.toLong() * format.frameSizeBytes
        val calculated = ((bytesPerSec * preset.durationMs) / 1000).toInt()
        targetPreRollBytes = calculated.coerceIn(chunkBuffer.size * 2, 64 * 1024)
        Log.i(TAG, "Configured target jitter buffer pre-roll: $targetPreRollBytes bytes (${preset.durationMs}ms)")
    }

    fun start(format: AudioStreamFormat) {
        if (isRunning.get()) return

        currentFormat = format
        isRunning.set(true)

        transmitterThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            transmissionLoop()
        }, "C3-PacedTransmitterThread").apply {
            isDaemon = true
            start()
        }

        Log.i(TAG, "PacedAudioTransmitter started for ${format.displayName} (Pacing: $ratePacingEnabled, PreRoll: ${targetPreRollBytes}B)")
    }

    fun stop() {
        isRunning.set(false)
        transmitterThread?.interrupt()
        try {
            transmitterThread?.join(300)
        } catch (_: InterruptedException) {}
        transmitterThread = null
        ringBuffer.clear()
        Log.i(TAG, "PacedAudioTransmitter stopped")
    }

    private fun transmissionLoop() {
        var bytesPerSec = currentFormat.sampleRate.toLong() * currentFormat.frameSizeBytes
        if (bytesPerSec <= 0L) bytesPerSec = 176400L

        var nextScheduledTimeNs = System.nanoTime()
        val maxAllowedDriftNs = 50_000_000L // 50ms maximum allowable lag before resetting clock
        var starvationStartTime = 0L
        var isCurrentlyStarved = false
        var isPreRolling = true

        while (isRunning.get()) {
            val available = ringBuffer.available
            val readCount: Int
            val isComfortSilence: Boolean

            if (isPreRolling) {
                // While waiting for the initial jitter buffer reservoir to build:
                if (available < targetPreRollBytes) {
                    // Send paced comfort silence so the ESP32 receiver connection stays alive and clock runs
                    readCount = chunkBuffer.size
                    isComfortSilence = true
                } else {
                    // Jitter buffer reservoir reached! Seamlessly engage real playback
                    isPreRolling = false
                    nextScheduledTimeNs = System.nanoTime()
                    readCount = ringBuffer.read(chunkBuffer, 0, chunkBuffer.size)
                    isComfortSilence = false
                    Log.i(TAG, "Jitter buffer filled ($available bytes). Real-time streaming engaged.")
                }
            } else {
                // Active playback mode: pull from the jitter buffer reservoir
                if (available < chunkBuffer.size) {
                    // Buffer ran completely dry (e.g. playback stopped or huge OS pause)
                    isPreRolling = true
                    readCount = chunkBuffer.size
                    isComfortSilence = true
                    if (!isCurrentlyStarved) {
                        isCurrentlyStarved = true
                        starvationStartTime = System.currentTimeMillis()
                    }
                } else {
                    // Normal continuous playback: pulled smoothly from the pre-rolled buffer
                    readCount = ringBuffer.read(chunkBuffer, 0, chunkBuffer.size)
                    isComfortSilence = false
                    if (isCurrentlyStarved) {
                        isCurrentlyStarved = false
                        val gapDuration = System.currentTimeMillis() - starvationStartTime
                        if (gapDuration >= 20L) {
                            AudioInterruptionLogger.log(
                                category = "RingBuffer Starvation",
                                description = "Audio capture paused for ${gapDuration}ms. Transmitter sent comfort silence frames to maintain receiver playback.",
                                durationMs = gapDuration
                            )
                        }
                    }
                }
            }

            if (readCount <= 0) {
                LockSupport.parkNanos(2_000_000L)
                continue
            }

            // Calculate chunk duration in nanoseconds
            val chunkDurationNs = (readCount.toLong() * 1_000_000_000L) / bytesPerSec

            // If the buffer has accumulated more than 2x the target reservoir (e.g. after a long UI gesture backlog),
            // transmit immediately to drain the excess backlog down to the target buffer level!
            val hasBacklog = !isComfortSilence && available > (targetPreRollBytes * 2)

            if (ratePacingEnabled && !hasBacklog) {
                val now = System.nanoTime()

                // If scheduled time is in the future, sleep precisely until transmission window
                if (nextScheduledTimeNs > now) {
                    val waitNs = nextScheduledTimeNs - now
                    LockSupport.parkNanos(waitNs)
                    nextScheduledTimeNs += chunkDurationNs
                } else {
                    if ((now - nextScheduledTimeNs) > maxAllowedDriftNs) {
                        nextScheduledTimeNs = now + chunkDurationNs
                    } else {
                        nextScheduledTimeNs += chunkDurationNs
                    }
                }
            } else if (hasBacklog) {
                // Keep next scheduled time aligned with current real time
                nextScheduledTimeNs = System.nanoTime()
            }

            // Transmit to TCP / HTTP transport
            val bufferToSend = if (isComfortSilence) silenceChunk else chunkBuffer
            val sendSuccess = sendChunkToTransport(bufferToSend, 0, readCount)
            if (!sendSuccess) {
                // If transmission failed (socket dropped), sleep briefly to conserve CPU
                LockSupport.parkNanos(10_000_000L) // 10ms
            }
        }
    }
}
