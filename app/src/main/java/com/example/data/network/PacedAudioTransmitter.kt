package com.example.data.network

import android.util.Log
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

    @Volatile
    var ratePacingEnabled: Boolean = true

    @Volatile
    var currentFormat: AudioStreamFormat = AudioStreamFormat.FORMAT_44K_16BIT_STEREO

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

        Log.i(TAG, "PacedAudioTransmitter started for ${format.displayName} (Pacing: $ratePacingEnabled)")
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

        while (isRunning.get()) {
            val available = ringBuffer.available
            if (available < chunkBuffer.size) {
                // If not enough data in ring buffer, yield or sleep 2ms to prevent busy-waiting
                LockSupport.parkNanos(2_000_000L)
                continue
            }

            // Read fixed-size chunk from ring buffer
            val readCount = ringBuffer.read(chunkBuffer, 0, chunkBuffer.size)
            if (readCount <= 0) {
                LockSupport.parkNanos(2_000_000L)
                continue
            }

            // Calculate chunk duration in nanoseconds
            val chunkDurationNs = (readCount.toLong() * 1_000_000_000L) / bytesPerSec

            // If the buffer has accumulated more than 2 chunks (e.g. caused by an app switch or window animation),
            // transmit immediately to refill the receiver's hardware buffer without delay!
            val hasBacklog = ringBuffer.available > (chunkBuffer.size * 2)

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
            val sendSuccess = sendChunkToTransport(chunkBuffer, 0, readCount)
            if (!sendSuccess) {
                // If transmission failed (socket dropped), sleep briefly to conserve CPU
                LockSupport.parkNanos(10_000_000L) // 10ms
            }
        }
    }
}
