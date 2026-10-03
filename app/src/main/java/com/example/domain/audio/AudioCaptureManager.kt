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

class AudioCaptureManager(
    private val onCaptureStatusChanged: (CaptureStatus, String?) -> Unit,
    private val onAudioChunkReady: (buffer: ByteArray, length: Int, isSilent: Boolean) -> Unit
) {
    private val TAG = "AudioCaptureManager"

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    // Reusable buffer pool to prevent GC allocation in the hot loop
    private val bufferQueue = ArrayBlockingQueue<ByteArray>(8)
    private var bufferSize = 0

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
        dspEngine.setSampleRate(format.sampleRate)

        onCaptureStatusChanged(CaptureStatus.INITIALIZING, null)

        val channelConfig = format.androidChannelConfig
        val audioEncoding = format.androidEncoding
        val sampleRate = format.sampleRate

        val minHardwareBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
        if (minHardwareBufferSize <= 0) {
            val err = "Invalid hardware buffer size ($minHardwareBufferSize) for format: ${format.displayName}"
            Log.e(TAG, err)
            onCaptureStatusChanged(CaptureStatus.ERROR, err)
            return false
        }

        // Calculate chunk size according to latency preset (e.g. 10ms, 25ms, 50ms)
        val bytesPerMs = (sampleRate * format.frameSizeBytes) / 1000
        val targetChunkSize = bytesPerMs * latencyPreset.durationMs
        // Ensure chunk size aligns with frame size and meets hardware minimums
        bufferSize = (targetChunkSize / format.frameSizeBytes * format.frameSizeBytes).coerceAtLeast(minHardwareBufferSize)

        // Initialize reusable byte array pool
        bufferQueue.clear()
        for (i in 0 until 8) {
            bufferQueue.offer(ByteArray(bufferSize))
        }

        try {
            audioRecord = if (sourceType == AudioSourceType.INTERNAL_AUDIO) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (mediaProjection == null) {
                        val err = "MediaProjection is null for Internal Audio capture"
                        Log.e(TAG, err)
                        onCaptureStatusChanged(CaptureStatus.ERROR, err)
                        return false
                    }

                    val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()

                    val audioFormat = AudioFormat.Builder()
                        .setEncoding(audioEncoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()

                    AudioRecord.Builder()
                        .setAudioPlaybackCaptureConfig(playbackConfig)
                        .setAudioFormat(audioFormat)
                        .setBufferSizeInBytes(bufferSize * 2)
                        .build()
                } else {
                    val err = "Internal audio capture requires Android 10 (API 29) or higher"
                    Log.e(TAG, err)
                    onCaptureStatusChanged(CaptureStatus.ERROR, err)
                    return false
                }
            } else {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioEncoding,
                    bufferSize * 2
                )
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                val err = "AudioRecord failed to initialize (State: ${audioRecord?.state})"
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

        while (isRunning.get()) {
            val record = audioRecord ?: break

            // Get a reusable buffer from queue or allocate fallback if starved
            val buffer = bufferQueue.poll() ?: ByteArray(bufferSize)

            val bytesRead = record.read(buffer, 0, buffer.size)

            if (bytesRead > 0) {
                // Apply DSP (EQ, bass boost, treble clarity, soft limiter) and volume scaling/mute in-place
                PcmAudioProcessor.processInPlace(
                    buffer = buffer,
                    length = bytesRead,
                    bitDepth = currentFormat.bitDepth,
                    volumePercent = volumePercent,
                    isMuted = isMuted,
                    dspEngine = dspEngine,
                    channels = currentFormat.channelCount
                )

                // Detect silence
                val chunkIsSilent = PcmAudioProcessor.isBufferSilent(
                    buffer = buffer,
                    length = bytesRead,
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
                onAudioChunkReady(buffer, bytesRead, chunkIsSilent)

                // Return buffer to queue
                bufferQueue.offer(buffer)
            } else if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                Log.w(TAG, "AudioRecord ERROR_INVALID_OPERATION (App switch or temporary device routing change)")
                // Sleep briefly and retry without killing the session
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    break
                }
            } else if (bytesRead == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "AudioRecord ERROR_BAD_VALUE")
                onCaptureStatusChanged(CaptureStatus.ERROR, "AudioRecord bad parameters")
                break
            } else {
                // Sleep tiny duration to prevent CPU spin if read returned 0
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
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
