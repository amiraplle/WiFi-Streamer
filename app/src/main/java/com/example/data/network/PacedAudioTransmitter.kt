package com.example.data.network

import android.util.Log
import com.example.domain.audio.AudioInterruptionLogger
import com.example.domain.audio.AudioRingBuffer
import com.example.model.AudioStreamFormat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * Precision rate-regulated audio transmitter.
 * Pulls PCM audio from [AudioRingBuffer] and feeds the UDP transport at the exact
 * hardware playback clock, protecting the ESP32-C3 / ESP32-S3 I2S DMA buffer from
 * burst overflows, socket drops, and starvation clicks.
 *
 * Designed for rock-solid real-time stability:
 * - Fixed 1024-byte packet frames (256 stereo 16-bit samples) matching ESP32 DMA descriptors
 * - Zero UDP fragmentation (every packet fits in a single unfragmented 1024-byte datagram)
 * - Jitter buffer reservoir (~150ms) absorbs OS scheduling jitter & background animation stalls
 * - Nanosecond monotonic clock regulation prevents packet burst floods on the microcontroller
 */
class PacedAudioTransmitter(
    private val ringBuffer: AudioRingBuffer,
    private val sendChunkToTransport: (buffer: ByteArray, offset: Int, length: Int) -> Boolean
) {
    private val TAG = "PacedAudioTransmitter"

    private val isRunning = AtomicBoolean(false)
    private var transmitterThread: Thread? = null

    // Standard 1024-byte packet size (256 stereo 16-bit frames = ~5.3ms @ 48kHz)
    // Fits comfortably in a single UDP packet (MTU < 1400) without fragmentation
    private val PACKET_SIZE = 1024
    private val chunkBuffer = ByteArray(PACKET_SIZE)
    private val silenceChunk = ByteArray(PACKET_SIZE)

    @Volatile
    var ratePacingEnabled: Boolean = true

    @Volatile
    var currentFormat: AudioStreamFormat = AudioStreamFormat.FORMAT_48K_16BIT_STEREO

    // Target jitter buffer pre-roll in bytes (~150ms default = 28,800 bytes for 48kHz stereo)
    @Volatile
    var targetPreRollBytes: Int = 28800

    fun setTargetLatencyPreset(preset: com.example.model.BufferLatencyPreset, format: AudioStreamFormat) {
        val bytesPerSec = format.sampleRate.toLong() * format.frameSizeBytes
        val calculated = ((bytesPerSec * preset.durationMs) / 1000).toInt()
        targetPreRollBytes = calculated.coerceIn(PACKET_SIZE * 8, 64 * 1024)
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

        Log.i(TAG, "PacedAudioTransmitter started for ${format.displayName} (Pacing: $ratePacingEnabled, PreRoll: ${targetPreRollBytes}B, Packet: ${PACKET_SIZE}B)")
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
        val bytesPerSec = (currentFormat.sampleRate.toLong() * currentFormat.frameSizeBytes).coerceAtLeast(1L)
        // Duration of one 1024-byte packet in nanoseconds (~5,333,333 ns at 48kHz stereo)
        val packetDurationNs = (PACKET_SIZE.toLong() * 1_000_000_000L) / bytesPerSec

        var nextPacketTimeNs = System.nanoTime()
        var isBuffering = true
        var starvationStartTime = 0L

        var consecutiveUnderflows = 0

        while (isRunning.get()) {
            val available = ringBuffer.available
            val minCushionBytes = targetPreRollBytes.coerceIn(PACKET_SIZE * 8, 64 * 1024)

            if (isBuffering) {
                // Initial pre-roll or recovery buffer: wait until healthy cushion accumulates
                if (available < minCushionBytes) {
                    // Send paced comfort silence to keep ESP32 connection alive and DAC clocked
                    sendChunkToTransport(silenceChunk, 0, PACKET_SIZE)

                    if (ratePacingEnabled) {
                        nextPacketTimeNs += packetDurationNs
                        val now = System.nanoTime()
                        val waitNs = nextPacketTimeNs - now
                        if (waitNs > 0) {
                            LockSupport.parkNanos(waitNs)
                        } else {
                            nextPacketTimeNs = now
                        }
                    }
                    continue
                } else {
                    // Reservoir filled! Seamlessly engage real audio playback
                    isBuffering = false
                    consecutiveUnderflows = 0
                    nextPacketTimeNs = System.nanoTime()
                    if (starvationStartTime > 0L) {
                        val gapDuration = System.currentTimeMillis() - starvationStartTime
                        AudioInterruptionLogger.log(
                            category = "Buffer Refill",
                            description = "Audio reservoir recovered after ${gapDuration}ms. Smooth playback resumed.",
                            durationMs = gapDuration
                        )
                        starvationStartTime = 0L
                    }
                    Log.i(TAG, "Jitter buffer filled ($available bytes). Real-time streaming engaged.")
                }
            }

            // Active streaming mode:
            if (available < PACKET_SIZE) {
                // Buffer momentarily low: send 1 comfort silence packet to keep DAC clocked
                sendChunkToTransport(silenceChunk, 0, PACKET_SIZE)
                consecutiveUnderflows++
                if (starvationStartTime == 0L) {
                    starvationStartTime = System.currentTimeMillis()
                }

                // Only engage full re-buffering if starved for more than 12 consecutive packets (~70ms)
                if (consecutiveUnderflows > 12) {
                    isBuffering = true
                }

                if (ratePacingEnabled) {
                    nextPacketTimeNs += packetDurationNs
                    val now = System.nanoTime()
                    val waitNs = nextPacketTimeNs - now
                    if (waitNs > 0) {
                        LockSupport.parkNanos(waitNs)
                    } else {
                        nextPacketTimeNs = now
                    }
                }
                continue
            }

            // Audio available: reset underflow counter immediately
            consecutiveUnderflows = 0

            // Pull exactly one 1024-byte frame from the ring buffer
            val readCount = ringBuffer.read(chunkBuffer, 0, PACKET_SIZE)
            if (readCount < PACKET_SIZE) {
                java.util.Arrays.fill(chunkBuffer, readCount, PACKET_SIZE, 0.toByte())
            }

            // Transmit single unfragmented 1024-byte datagram to transport
            val sendSuccess = sendChunkToTransport(chunkBuffer, 0, PACKET_SIZE)
            if (!sendSuccess) {
                LockSupport.parkNanos(2_000_000L) // 2ms backoff on network drop
            }

            // Nanosecond rate pacing: strictly prevents packet bursts
            if (ratePacingEnabled) {
                nextPacketTimeNs += packetDurationNs
                val now = System.nanoTime()
                val waitNs = nextPacketTimeNs - now

                if (waitNs > 0) {
                    LockSupport.parkNanos(waitNs)
                } else {
                    // Slipped behind clock due to network I/O or OS scheduling.
                    // Strictly reset clock to now so we NEVER burst-flood the ESP32!
                    nextPacketTimeNs = now
                }
            }
        }
    }
}
