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

    // Frame-aligned packet sizes:
    // 24-bit stereo (6 bytes/frame) -> 1152 bytes (192 frames)
    // 16-bit stereo (4 bytes/frame) -> 1024 bytes (256 frames)
    // 32-bit stereo (8 bytes/frame) -> 1024 bytes (128 frames)
    // All sizes fit in a single unfragmented UDP datagram (MTU < 1400)
    private val MAX_PACKET_CAPACITY = 1400
    private val chunkBuffer = ByteArray(MAX_PACKET_CAPACITY)
    private val silenceChunk = ByteArray(MAX_PACKET_CAPACITY)

    @Volatile
    var ratePacingEnabled: Boolean = true

    @Volatile
    var currentFormat: AudioStreamFormat = AudioStreamFormat.FORMAT_48K_16BIT_STEREO

    // Target jitter buffer pre-roll in bytes (~150ms default)
    @Volatile
    var targetPreRollBytes: Int = 28800

    fun getPacketSizeForFormat(format: AudioStreamFormat): Int {
        val frameSize = format.frameSizeBytes.coerceAtLeast(1)
        return when (format.bitDepth) {
            24 -> 1152 // 192 frames * 6 bytes = 1152 bytes
            32 -> 1024 // 128 frames * 8 bytes = 1024 bytes
            else -> 1024 // 256 frames * 4 bytes = 1024 bytes
        }
    }

    fun setTargetLatencyMs(targetMs: Int, format: AudioStreamFormat) {
        val clampedMs = targetMs.coerceIn(15, 300)
        val bytesPerSec = (format.sampleRate.toLong() * format.frameSizeBytes).coerceAtLeast(1L)
        val calculated = ((bytesPerSec * clampedMs) / 1000).toInt()
        val packetSize = getPacketSizeForFormat(format)
        targetPreRollBytes = calculated.coerceIn(packetSize * 2, 64 * 1024)
        Log.i(TAG, "Configured target jitter buffer pre-roll: $targetPreRollBytes bytes (${clampedMs}ms)")
    }

    fun setTargetLatencyPreset(preset: com.example.model.BufferLatencyPreset, format: AudioStreamFormat) {
        setTargetLatencyMs(preset.durationMs, format)
    }

    fun updateFormat(newFormat: AudioStreamFormat) {
        currentFormat = newFormat
        val packetSize = getPacketSizeForFormat(newFormat)
        val bytesPerSec = (newFormat.sampleRate.toLong() * newFormat.frameSizeBytes).coerceAtLeast(1L)
        targetPreRollBytes = ((bytesPerSec * 150) / 1000).toInt().coerceIn(packetSize * 4, 64 * 1024)
        Log.i(TAG, "PacedAudioTransmitter format updated to ${newFormat.displayName} (PacketSize: ${packetSize}B)")
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

        val packetSize = getPacketSizeForFormat(format)
        Log.i(TAG, "PacedAudioTransmitter started for ${format.displayName} (Pacing: $ratePacingEnabled, PreRoll: ${targetPreRollBytes}B, Packet: ${packetSize}B)")
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
        var nextPacketTimeNs = System.nanoTime()
        var isBuffering = true
        var starvationStartTime = 0L
        var consecutiveUnderflows = 0

        while (isRunning.get()) {
            val format = currentFormat
            val packetSize = getPacketSizeForFormat(format)
            val bytesPerSec = (format.sampleRate.toLong() * format.frameSizeBytes).coerceAtLeast(1L)
            val packetDurationNs = (packetSize.toLong() * 1_000_000_000L) / bytesPerSec

            val available = ringBuffer.available
            val minCushionBytes = targetPreRollBytes.coerceIn(packetSize * 4, 64 * 1024)

            if (isBuffering) {
                // Initial pre-roll or recovery buffer: wait until healthy cushion accumulates
                if (available < minCushionBytes) {
                    // Send paced comfort silence to keep ESP32 connection alive and DAC clocked
                    sendChunkToTransport(silenceChunk, 0, packetSize)

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
            if (available < packetSize) {
                // Buffer momentarily low: send 1 comfort silence packet to keep DAC clocked
                sendChunkToTransport(silenceChunk, 0, packetSize)
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

            // Pull exactly one aligned frame packet from the ring buffer
            val readCount = ringBuffer.read(chunkBuffer, 0, packetSize)
            if (readCount < packetSize) {
                java.util.Arrays.fill(chunkBuffer, readCount, packetSize, 0.toByte())
            }

            // Transmit single unfragmented datagram to transport
            val sendSuccess = sendChunkToTransport(chunkBuffer, 0, packetSize)
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
