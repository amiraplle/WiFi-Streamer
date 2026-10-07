package com.example.domain.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.util.Log
import com.example.model.AudioSourceType
import com.example.model.AudioStreamFormat
import com.example.model.BufferLatencyPreset
import com.example.model.CaptureStatus
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

class AudioCaptureManager(
    private val onCaptureStatusChanged: (CaptureStatus, String?) -> Unit,
    private val onAudioChunkReady: (buffer: ByteArray, length: Int, isSilent: Boolean) -> Unit
) {
    private val TAG = "AudioCaptureManager"

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    private var savedMediaProjection: MediaProjection? = null
    private var savedLatencyPreset: BufferLatencyPreset = BufferLatencyPreset.BALANCED

    // Reusable buffer pool to prevent GC allocation in the hot loop
    private val bufferQueue = ArrayBlockingQueue<ByteArray>(8)
    private var bufferSize = 0
    private var actualHardwareEncoding: Int = AudioFormat.ENCODING_PCM_16BIT

    @Volatile
    var currentFormat: AudioStreamFormat = AudioStreamFormat.FORMAT_44K_16BIT_STEREO
        private set

    @Volatile
    var currentSource: AudioSourceType = AudioSourceType.INTERNAL_AUDIO
        private set

    @Volatile
    var volumePercent: Int = 100

    @Volatile
    var isMuted: Boolean = false

    val dspEngine = AudioDspEngine()

    @SuppressLint("MissingPermission")
    fun startCapture(
        format: AudioStreamFormat,
        sourceType: AudioSourceType,
        latencyPreset: BufferLatencyPreset,
        mediaProjection: MediaProjection?
    ): Boolean {
        if (isRunning.get()) {
            stopCapture()
        }

        currentFormat = format
        currentSource = sourceType
        savedMediaProjection = mediaProjection
        savedLatencyPreset = latencyPreset
        dspEngine.setSampleRate(format.sampleRate)

        onCaptureStatusChanged(CaptureStatus.INITIALIZING, null)

        val channelConfig = format.androidChannelConfig
        val audioEncoding = if ((format.bitDepth == 32 || format.bitDepth == 24) && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            AudioFormat.ENCODING_PCM_16BIT
        } else {
            format.androidEncoding
        }
        actualHardwareEncoding = audioEncoding
        val sampleRate = format.sampleRate

        val minHardwareBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
        if (minHardwareBufferSize <= 0) {
            val err = "Invalid hardware buffer size ($minHardwareBufferSize) for format: ${format.displayName}"
            Log.e(TAG, err)
            onCaptureStatusChanged(CaptureStatus.ERROR, err)
            return false
        }

        // Calculate chunk size according to latency preset (e.g. 10ms, 25ms, 50ms)
        // Keep chunk size tight (10ms-25ms, max 4096 bytes) so AudioRecord.read() never blocks for 80ms!
        val bytesPerMs = (sampleRate * format.frameSizeBytes) / 1000
        val targetChunkSize = bytesPerMs * latencyPreset.durationMs
        bufferSize = (targetChunkSize / format.frameSizeBytes * format.frameSizeBytes).coerceIn(format.frameSizeBytes * 10, 4096)

        // Initialize reusable byte array pool
        bufferQueue.clear()
        for (i in 0 until 8) {
            bufferQueue.offer(ByteArray(bufferSize))
        }

        try {
            audioRecord = if (sourceType == AudioSourceType.INTERNAL_AUDIO) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (mediaProjection == null) {
                        val err = "Internal audio requires screen/audio cast permission. Please tap Start again."
                        Log.e(TAG, err)
                        onCaptureStatusChanged(CaptureStatus.ERROR, err)
                        return false
                    }

                    val playbackConfigBuilder = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    try {
                        playbackConfigBuilder.addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    } catch (_: Exception) {}
                    val playbackConfig = playbackConfigBuilder.build()

                    val audioFormat = AudioFormat.Builder()
                        .setEncoding(audioEncoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()

                    // Give AudioRecord OS driver a deep 500ms hardware buffer so Android never overflows during UI animations
                    val targetDriverBufferBytes = (sampleRate * format.frameSizeBytes * 500) / 1000
                    val bufferBytes = targetDriverBufferBytes.coerceAtLeast(minHardwareBufferSize * 4)

                    try {
                        AudioRecord.Builder()
                            .setAudioPlaybackCaptureConfig(playbackConfig)
                            .setAudioFormat(audioFormat)
                            .setBufferSizeInBytes(bufferBytes)
                            .build()
                    } catch (e: Exception) {
                        Log.w(TAG, "Primary AudioRecord build failed (${e.message}), trying 16-bit fallback...")
                        try {
                            val fallbackFormat = AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(channelConfig)
                                .build()
                            val fallbackMin = AudioRecord.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
                            AudioRecord.Builder()
                                .setAudioPlaybackCaptureConfig(playbackConfig)
                                .setAudioFormat(fallbackFormat)
                                .setBufferSizeInBytes((targetDriverBufferBytes).coerceAtLeast(fallbackMin * 4))
                                .build()
                        } catch (e2: Exception) {
                            Log.w(TAG, "Secondary 16-bit build failed (${e2.message}), trying 48000Hz 16-bit hardware rate...")
                            val fallback48k = AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(48000)
                                .setChannelMask(channelConfig)
                                .build()
                            val min48k = AudioRecord.getMinBufferSize(48000, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
                            AudioRecord.Builder()
                                .setAudioPlaybackCaptureConfig(playbackConfig)
                                .setAudioFormat(fallback48k)
                                .setBufferSizeInBytes((48000 * format.frameSizeBytes / 2).coerceAtLeast(min48k * 4))
                                .build()
                        }
                    }
                } else {
                    val err = "Internal audio capture requires Android 10 (API 29) or higher"
                    Log.e(TAG, err)
                    onCaptureStatusChanged(CaptureStatus.ERROR, err)
                    return false
                }
            } else {
                val targetDriverBufferBytes = (sampleRate * format.frameSizeBytes * 500) / 1000
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioEncoding,
                    targetDriverBufferBytes.coerceAtLeast(minHardwareBufferSize * 4)
                )
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                val err = "AudioRecord failed to initialize (State: ${audioRecord?.state})."
                Log.e(TAG, err)
                audioRecord?.release()
                audioRecord = null
                onCaptureStatusChanged(CaptureStatus.ERROR, err)
                return false
            }

            audioRecord?.startRecording()
            isRunning.set(true)
            onCaptureStatusChanged(CaptureStatus.CAPTURING, null)

            // Start dedicated audio reading thread with urgent audio priority
            captureThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                captureLoop()
            }, "C3-AudioCaptureThread").apply {
                isDaemon = true
                start()
            }

            Log.i(TAG, "Audio capture started: ${format.displayName}, Source=$sourceType, ChunkSize=$bufferSize")
            return true
        } catch (e: SecurityException) {
            val err = "Permission denied for audio capture: ${e.message}"
            Log.e(TAG, err, e)
            onCaptureStatusChanged(CaptureStatus.ERROR, err)
            releaseAudioRecord()
            return false
        } catch (e: Exception) {
            val err = "Exception initializing audio capture: ${e.message}"
            Log.e(TAG, err, e)
            onCaptureStatusChanged(CaptureStatus.ERROR, err)
            releaseAudioRecord()
            return false
        }
    }

    private fun captureLoop() {
        var silentChunksCount = 0
        var isCurrentlySilent = false
        var lastAudioTimestamp = System.currentTimeMillis()
        var stallStartTime = 0L

        while (isRunning.get()) {
            val record = audioRecord ?: break

            // Get a reusable buffer from queue or allocate fallback if starved
            val buffer = bufferQueue.poll() ?: ByteArray(bufferSize)

            val bytesRead = record.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)

            if (bytesRead > 0) {
                val finalBuffer: ByteArray
                val finalLength: Int
                if (currentFormat.bitDepth == 32 && actualHardwareEncoding == AudioFormat.ENCODING_PCM_16BIT) {
                    val sampleCount = bytesRead / 2
                    val converted = ByteArray(sampleCount * 4)
                    var srcIdx = 0
                    var dstIdx = 0
                    while (srcIdx + 1 < bytesRead) {
                        val b0 = buffer[srcIdx]
                        val b1 = buffer[srcIdx + 1]
                        // 32-bit I2S slot: pad lower 16 bits with 0, upper 16 bits with sample
                        converted[dstIdx] = 0
                        converted[dstIdx + 1] = 0
                        converted[dstIdx + 2] = b0
                        converted[dstIdx + 3] = b1
                        srcIdx += 2
                        dstIdx += 4
                    }
                    finalBuffer = converted
                    finalLength = dstIdx
                } else if (currentFormat.bitDepth == 24 && actualHardwareEncoding == AudioFormat.ENCODING_PCM_16BIT) {
                    val sampleCount = bytesRead / 2
                    val converted = ByteArray(sampleCount * 3)
                    var srcIdx = 0
                    var dstIdx = 0
                    while (srcIdx + 1 < bytesRead) {
                        val b0 = buffer[srcIdx]
                        val b1 = buffer[srcIdx + 1]
                        // 24-bit packed: lower byte 0, upper 2 bytes from 16-bit
                        converted[dstIdx] = 0
                        converted[dstIdx + 1] = b0
                        converted[dstIdx + 2] = b1
                        srcIdx += 2
                        dstIdx += 3
                    }
                    finalBuffer = converted
                    finalLength = dstIdx
                } else {
                    finalBuffer = buffer
                    finalLength = bytesRead
                }

                val now = System.currentTimeMillis()
                if (stallStartTime > 0L) {
                    val stallDuration = now - stallStartTime
                    if (stallDuration >= 80L) {
                        AudioInterruptionLogger.log(
                            category = "OS Capture Stall",
                            description = "Android OS paused audio capture delivery for ${stallDuration}ms (System UI animation / App switch).",
                            durationMs = stallDuration
                        )
                    }
                    stallStartTime = 0L
                }
                lastAudioTimestamp = now

                // Apply DSP (EQ, bass boost, treble clarity, soft limiter) and volume scaling/mute in-place
                PcmAudioProcessor.processInPlace(
                    buffer = finalBuffer,
                    length = finalLength,
                    bitDepth = currentFormat.bitDepth,
                    volumePercent = volumePercent,
                    isMuted = isMuted,
                    dspEngine = dspEngine,
                    channels = currentFormat.channelCount
                )

                // Detect silence
                val chunkIsSilent = PcmAudioProcessor.isBufferSilent(
                    buffer = finalBuffer,
                    length = finalLength,
                    bitDepth = currentFormat.bitDepth
                )

                if (chunkIsSilent) {
                    silentChunksCount++
                    // If silence persists for ~200ms, update state to SILENCE
                    if (silentChunksCount > 10 && !isCurrentlySilent) {
                        isCurrentlySilent = true
                        onCaptureStatusChanged(CaptureStatus.SILENCE, null)
                    }
                } else {
                    silentChunksCount = 0
                    if (isCurrentlySilent) {
                        isCurrentlySilent = false
                        onCaptureStatusChanged(CaptureStatus.CAPTURING, null)
                    }
                }

                // Pass chunk to consumer (network streamer)
                onAudioChunkReady(finalBuffer, finalLength, chunkIsSilent)

                // Return buffer to queue
                bufferQueue.offer(buffer)

                // Loop immediately to burst-drain any additional pending samples from the OS driver buffer
                continue
            } else if (bytesRead == 0) {
                // No audio ready at this exact millisecond
                bufferQueue.offer(buffer)
                val now = System.currentTimeMillis()
                if (stallStartTime == 0L && (now - lastAudioTimestamp) > 100L) {
                    stallStartTime = lastAudioTimestamp
                }
                // Sleep 2ms with parkNanos to yield CPU without busy-spin
                LockSupport.parkNanos(2_000_000L)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && bytesRead == AudioRecord.ERROR_DEAD_OBJECT) {
                bufferQueue.offer(buffer)
                AudioInterruptionLogger.log(
                    category = "AudioRecord Error",
                    description = "ERROR_DEAD_OBJECT: Android audio server dropped capture track; recreating AudioRecord in background."
                )
                Log.w(TAG, "AudioRecord ERROR_DEAD_OBJECT (Android audio server re-routed). Recreating AudioRecord...")
                val recreated = recreateAudioRecord()
                if (!recreated) {
                    try {
                        Thread.sleep(50)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } else if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                bufferQueue.offer(buffer)
                AudioInterruptionLogger.log(
                    category = "AudioRecord Error",
                    description = "ERROR_INVALID_OPERATION: App switch or temporary device routing change detected by Android AudioPolicy."
                )
                Log.w(TAG, "AudioRecord ERROR_INVALID_OPERATION (App switch or temporary device routing change)")
                // Sleep briefly and retry without killing the session
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    break
                }
            } else if (bytesRead == AudioRecord.ERROR_BAD_VALUE) {
                bufferQueue.offer(buffer)
                AudioInterruptionLogger.log(
                    category = "AudioRecord Error",
                    description = "ERROR_BAD_VALUE: AudioRecord bad parameters."
                )
                Log.e(TAG, "AudioRecord ERROR_BAD_VALUE")
                onCaptureStatusChanged(CaptureStatus.ERROR, "AudioRecord bad parameters")
                break
            } else {
                bufferQueue.offer(buffer)
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun recreateAudioRecord(): Boolean {
        try {
            releaseAudioRecord()
            val format = currentFormat
            val sourceType = currentSource
            val channelConfig = format.androidChannelConfig
            val audioEncoding = format.androidEncoding
            val sampleRate = format.sampleRate
            val minHardwareBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
            if (minHardwareBufferSize <= 0) return false

            val targetDriverBufferBytes = (sampleRate * format.frameSizeBytes * 500) / 1000
            val bufferBytes = targetDriverBufferBytes.coerceAtLeast(minHardwareBufferSize * 4)

            audioRecord = if (sourceType == AudioSourceType.INTERNAL_AUDIO && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val mp = savedMediaProjection ?: return false
                val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mp)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .apply {
                        try { addMatchingUsage(AudioAttributes.USAGE_UNKNOWN) } catch (_: Exception) {}
                    }.build()

                val audioFormat = AudioFormat.Builder()
                    .setEncoding(audioEncoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()

                AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(playbackConfig)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
            } else {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioEncoding,
                    bufferBytes
                )
            }

            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord?.startRecording()
                Log.i(TAG, "AudioRecord successfully recreated and restarted in background")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "recreateAudioRecord exception: ${e.message}")
        }
        return false
    }

    fun stopCapture() {
        isRunning.set(false)
        captureThread?.interrupt()
        try {
            captureThread?.join(500)
        } catch (_: InterruptedException) {}
        captureThread = null

        releaseAudioRecord()
        bufferQueue.clear()
        onCaptureStatusChanged(CaptureStatus.IDLE, null)
        Log.i(TAG, "Audio capture stopped")
    }

    private fun releaseAudioRecord() {
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }
}
