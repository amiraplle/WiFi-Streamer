package com.example.domain.player

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.withContext
import com.example.data.preferences.UserPreferencesRepository
import com.example.domain.audio.AudioDspEngine
import com.example.model.AudioTrack
import com.example.model.PlayerState
import com.example.model.PlayerUiState
import com.example.model.PlayerRepeatMode
import com.example.service.StreamingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

class LocalAudioPlayerManager(
    private val context: Context,
    private val preferencesRepository: UserPreferencesRepository
) {
    private val TAG = "LocalAudioPlayerManager"
    private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    val savedFolderName = MutableStateFlow<String?>(null)
    val isRescanning = MutableStateFlow(false)

    private var playbackJob: Job? = null
    private val isPaused = AtomicBoolean(false)
    private val isStopped = AtomicBoolean(true)
    private val pendingSeekMs = AtomicLong(-1L)

    // Tracks last stereo sample values to perform pure, click-free Hann window fade-out on pause/stop/skip
    @Volatile
    private var lastSampleL: Int = 0
    @Volatile
    private var lastSampleR: Int = 0

    private val dspEngine = AudioDspEngine()

    init {
        val initialPrefs = preferencesRepository.userPreferences.value
        val diskPrefs = context.getSharedPreferences("c3_player_state", Context.MODE_PRIVATE)
        savedFolderName.value = diskPrefs.getString("last_folder_name", null)

        _uiState.value = _uiState.value.copy(
            playlist = emptyList(),
            currentIndex = -1,
            currentTrack = null,
            outputFormatInfo = "${initialPrefs.bitDepth}-bit • ${initialPrefs.sampleRate / 1000.0} kHz"
        )

        // Observe global preferences to update the active output target format badge and DSP engine dynamically
        playerScope.launch {
            preferencesRepository.userPreferences.collect { prefs ->
                _uiState.value = _uiState.value.copy(
                    outputFormatInfo = "${prefs.bitDepth}-bit • ${prefs.sampleRate / 1000.0} kHz"
                )
                dspEngine.apply {
                    setSampleRate(prefs.sampleRate)
                    isEnabled = prefs.dspEnabled
                    isSoftLimiterEnabled = prefs.softLimiterEnabled
                    limiterTrimDb = prefs.limiterTrimDb
                    airBandEnabled = prefs.airBandEnabled
                    airBandGainDb = prefs.airBandGainDb
                    bassPunchEnabled = prefs.bassPunchEnabled
                    bassPunchIntensity = prefs.bassPunchIntensity
                    subBassCutEnabled = prefs.subBassCutEnabled
                    stereoWidenerEnabled = prefs.stereoWidenerEnabled
                    stereoWidthPercent = prefs.stereoWidthPercent
                }
            }
        }

        // Permanently restore saved playlist and last played track from disk
        restoreSavedPlaylist()
    }

    fun updateTargetFormat(format: com.example.model.AudioStreamFormat) {
        preferencesRepository.updateAudioFormat(format)
    }

    fun updateDspSettings(prefs: com.example.data.preferences.UserPreferences) {
        dspEngine.apply {
            setSampleRate(prefs.sampleRate)
            isEnabled = prefs.dspEnabled
            isSoftLimiterEnabled = prefs.softLimiterEnabled
            limiterTrimDb = prefs.limiterTrimDb
            airBandEnabled = prefs.airBandEnabled
            airBandGainDb = prefs.airBandGainDb
            bassPunchEnabled = prefs.bassPunchEnabled
            bassPunchIntensity = prefs.bassPunchIntensity
            subBassCutEnabled = prefs.subBassCutEnabled
            stereoWidenerEnabled = prefs.stereoWidenerEnabled
            stereoWidthPercent = prefs.stereoWidthPercent
        }
    }

    /**
     * Symmetrical 15ms Hann window soft fade-out (supports 16-bit and 24-bit PCM).
     * Ramps the instantaneous DAC output voltage down to 0, preventing clicks or pops.
     */
    private fun writeSoftFadeOut(sampleRate: Int, bitDepth: Int) {
        val bytesPerSample = bitDepth / 8
        val frameSize = bytesPerSample * 2
        val rampFrames = (sampleRate * 15) / 1000 // 15ms
        if (rampFrames <= 0) return

        val startL = lastSampleL
        val startR = lastSampleR
        if (startL == 0 && startR == 0) return

        val fadeOutBytes = ByteArray(rampFrames * frameSize)
        var outIdx = 0

        for (f in 0 until rampFrames) {
            val factor = (0.5 * (1.0 + cos(Math.PI * f / rampFrames))).toFloat()
            if (bitDepth == 32) {
                val curL = (startL.toLong() * factor).toLong().coerceIn(-2147483648L, 2147483647L).toInt()
                val curR = (startR.toLong() * factor).toLong().coerceIn(-2147483648L, 2147483647L).toInt()
                fadeOutBytes[outIdx++] = (curL and 0xFF).toByte()
                fadeOutBytes[outIdx++] = ((curL shr 8) and 0xFF).toByte()
                fadeOutBytes[outIdx++] = ((curL shr 16) and 0xFF).toByte()
                fadeOutBytes[outIdx++] = ((curL shr 24) and 0xFF).toByte()
                fadeOutBytes[outIdx++] = (curR and 0xFF).toByte()
                fadeOutBytes[outIdx++] = ((curR shr 8) and 0xFF).toByte()
                fadeOutBytes[outIdx++] = ((curR shr 16) and 0xFF).toByte()
                fadeOutBytes[outIdx++] = ((curR shr 24) and 0xFF).toByte()
            } else if (bitDepth == 24) {
                val curL = (startL * factor).toInt().coerceIn(-8388608, 8388607)
                val curR = (startR * factor).toInt().coerceIn(-8388608, 8388607)
                fadeOutBytes[outIdx++] = (curL and 0xFF).toByte()
                fadeOutBytes[outIdx++] = ((curL shr 8) and 0xFF).toByte()
                fadeOutBytes[outIdx++] = ((curL shr 16) and 0xFF).toByte()
                fadeOutBytes[outIdx++] = (curR and 0xFF).toByte()
                fadeOutBytes[outIdx++] = ((curR shr 8) and 0xFF).toByte()
                fadeOutBytes[outIdx++] = ((curR shr 16) and 0xFF).toByte()
            } else {
                val curL = (startL * factor).toInt().coerceIn(-32768, 32767)
                val curR = (startR * factor).toInt().coerceIn(-32768, 32767)
                fadeOutBytes[outIdx++] = (curL and 0xFF).toByte()
                fadeOutBytes[outIdx++] = ((curL shr 8) and 0xFF).toByte()
                fadeOutBytes[outIdx++] = (curR and 0xFF).toByte()
                fadeOutBytes[outIdx++] = ((curR shr 8) and 0xFF).toByte()
            }
        }
        lastSampleL = 0
        lastSampleR = 0
        StreamingService.instance?.writeDirectAudio(fadeOutBytes, 0, fadeOutBytes.size)
    }

    fun playTrackAtIndex(index: Int) {
        val list = _uiState.value.playlist
        if (index !in list.indices) return

        // Soft fade out outgoing track if playing
        if (_uiState.value.playerState == PlayerState.PLAYING) {
            val prefs = preferencesRepository.userPreferences.value
            writeSoftFadeOut(prefs.sampleRate, prefs.bitDepth)
        }

        var track = list[index]
        // Instant artwork guarantee: extract metadata immediately if track was restored from disk without art
        if (track.artworkBytes == null) {
            val fullTrack = extractTrackMetadata(track.uri, track.title)
            if (fullTrack.artworkBytes != null) {
                track = track.copy(artworkBytes = fullTrack.artworkBytes)
                val updatedList = list.toMutableList()
                if (index in updatedList.indices) {
                    updatedList[index] = track
                }
                _uiState.value = _uiState.value.copy(playlist = updatedList)
            }
        }

        _uiState.value = _uiState.value.copy(
            currentIndex = index,
            currentTrack = track,
            positionMs = 0L,
            durationMs = track.durationMs,
            playerState = PlayerState.BUFFERING,
            sourceFormatInfo = "Loading…",
            errorMessage = null
        )

        saveCurrentTrackIndex(index)
        startPlaybackWorker(track)
    }

    fun togglePlayPause() {
        val state = _uiState.value.playerState
        when (state) {
            PlayerState.PLAYING -> {
                val prefs = preferencesRepository.userPreferences.value
                writeSoftFadeOut(prefs.sampleRate, prefs.bitDepth)
                isPaused.set(true)
                _uiState.value = _uiState.value.copy(playerState = PlayerState.PAUSED)
            }
            PlayerState.PAUSED -> {
                isPaused.set(false)
                _uiState.value = _uiState.value.copy(playerState = PlayerState.PLAYING)
            }
            PlayerState.IDLE, PlayerState.ERROR -> {
                val index = if (_uiState.value.currentIndex >= 0) _uiState.value.currentIndex else 0
                playTrackAtIndex(index)
            }
            PlayerState.BUFFERING -> {
                // Wait for buffer
            }
        }
    }

    fun playNext() {
        val list = _uiState.value.playlist
        if (list.isEmpty()) return

        val nextIndex = if (_uiState.value.isShuffle) {
            list.indices.filter { it != _uiState.value.currentIndex }.randomOrNull() ?: 0
        } else {
            val next = _uiState.value.currentIndex + 1
            if (next >= list.size) {
                if (_uiState.value.repeatMode == PlayerRepeatMode.ALL) 0 else return
            } else {
                next
            }
        }
        playTrackAtIndex(nextIndex)
    }

    fun playPrevious() {
        val list = _uiState.value.playlist
        if (list.isEmpty()) return

        // If played more than 3 seconds, restart current track
        if (_uiState.value.positionMs > 3000L) {
            seekTo(0L)
            return
        }

        val prev = _uiState.value.currentIndex - 1
        val prevIndex = if (prev < 0) {
            if (_uiState.value.repeatMode == PlayerRepeatMode.ALL) list.size - 1 else 0
        } else {
            prev
        }
        playTrackAtIndex(prevIndex)
    }

    fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceIn(0L, _uiState.value.durationMs.coerceAtLeast(1L))
        _uiState.value = _uiState.value.copy(positionMs = clamped)
        pendingSeekMs.set(clamped)
    }

    fun toggleShuffle() {
        _uiState.value = _uiState.value.copy(isShuffle = !_uiState.value.isShuffle)
    }

    fun toggleRepeat() {
        val next = when (_uiState.value.repeatMode) {
            PlayerRepeatMode.OFF -> PlayerRepeatMode.ALL
            PlayerRepeatMode.ALL -> PlayerRepeatMode.ONE
            PlayerRepeatMode.ONE -> PlayerRepeatMode.OFF
        }
        _uiState.value = _uiState.value.copy(repeatMode = next)
    }

    fun addTracks(newTracks: List<AudioTrack>, playImmediately: Boolean = false) {
        if (newTracks.isEmpty()) return
        val currentList = _uiState.value.playlist.toMutableList()
        currentList.addAll(newTracks)
        val startIndex = if (_uiState.value.playlist.isEmpty()) 0 else currentList.size - newTracks.size
        val targetTrack = _uiState.value.currentTrack ?: newTracks.firstOrNull()
        val targetIdx = if (_uiState.value.currentIndex < 0) 0 else _uiState.value.currentIndex
        _uiState.value = _uiState.value.copy(
            playlist = currentList,
            currentTrack = targetTrack,
            currentIndex = targetIdx
        )
        // Permanently persist playlist to disk so songs never disappear on app close
        savePlaylistToDisk(currentList, targetIdx)

        if (playImmediately) {
            playTrackAtIndex(startIndex)
        }
    }

    fun clearQueue() {
        stopPlayback()
        _uiState.value = _uiState.value.copy(
            playlist = emptyList(),
            currentTrack = null,
            currentIndex = -1,
            playerState = PlayerState.IDLE,
            positionMs = 0L,
            durationMs = 0L
        )
        deleteSavedPlaylist()
    }

    fun stopPlayback(stopService: Boolean = true) {
        if (_uiState.value.playerState == PlayerState.PLAYING) {
            val prefs = preferencesRepository.userPreferences.value
            writeSoftFadeOut(prefs.sampleRate, prefs.bitDepth)
        }
        isStopped.set(true)
        isPaused.set(false)
        playbackJob?.cancel()
        playbackJob = null
        StreamingService.instance?.clearRingBuffer()
        _uiState.value = _uiState.value.copy(playerState = PlayerState.IDLE, positionMs = 0L)
        if (stopService && StreamingService.instance != null && StreamingService.isStreaming && StreamingService.instance?.activeStreamSource == "DIRECT_AUDIO") {
            StreamingService.instance?.stopStreaming()
        }
    }

    private fun startPlaybackWorker(track: AudioTrack) {
        // Seamlessly switch to direct audio if streaming service is already active
        val service = StreamingService.instance
        if (service != null && StreamingService.isStreaming) {
            if (service.activeStreamSource != "DIRECT_AUDIO") {
                service.switchToDirectAudio()
            }
        } else {
            StreamingService.startDirectStreaming(context)
        }

        isStopped.set(true)
        playbackJob?.cancel()
        StreamingService.instance?.clearRingBuffer()
        isStopped.set(false)
        isPaused.set(false)

        playbackJob = playerScope.launch {
            try {
                // Ensure streaming service is active for UDP transport
                ensureStreamingServiceRunning()
                runFileDecodingPlayback(track)
            } catch (e: Exception) {
                if (isActive && !isStopped.get()) {
                    Log.e(TAG, "Playback error on track ${track.title}: ${e.message}", e)
                    _uiState.value = _uiState.value.copy(
                        playerState = PlayerState.ERROR,
                        errorMessage = "Playback failed: ${e.message ?: "Unsupported format"}"
                    )
                }
            }
        }
    }

    private fun ensureStreamingServiceRunning() {
        if (StreamingService.instance == null || !StreamingService.isStreaming) {
            StreamingService.startDirectStreaming(context)
        }
    }

    /**
     * High-performance MediaCodec + MediaExtractor audio decoding loop.
     * Extracts raw PCM from MP3, FLAC, WAV, AAC, OGG, Opus and sends to both device speaker and UDP pipeline.
     */
    private suspend fun runFileDecodingPlayback(track: AudioTrack) {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Exception) {}

        var pfd: ParcelFileDescriptor? = null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            pfd = context.contentResolver.openFileDescriptor(track.uri, "r")
                ?: throw IllegalArgumentException("Cannot open audio file URI")

            extractor.setDataSource(pfd.fileDescriptor)

            var audioTrackIndex = -1
            var mediaFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    mediaFormat = format
                    break
                }
            }

            if (audioTrackIndex < 0 || mediaFormat == null) {
                throw IllegalStateException("No valid audio track found in file")
            }

            extractor.selectTrack(audioTrackIndex)

            val mime = mediaFormat.getString(MediaFormat.KEY_MIME) ?: "audio/raw"
            val sourceSampleRate = if (mediaFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 48000

            val sourceChannels = if (mediaFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2

            val durationUs = if (mediaFormat.containsKey(MediaFormat.KEY_DURATION)) {
                mediaFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            val totalDurationMs = durationUs / 1000L

            // Target preferences from Settings
            val prefs = preferencesRepository.userPreferences.value
            val targetSampleRate = prefs.sampleRate
            val targetBitDepth = prefs.bitDepth

            val uriLower = track.uri.toString().lowercase()
            val titleLower = track.title.lowercase()
            val codecDisplay = when {
                mime.contains("opus", ignoreCase = true) || uriLower.contains(".opus") || titleLower.contains(".opus") || track.mimeType.contains("opus", ignoreCase = true) -> "OPUS"
                mime.contains("flac", ignoreCase = true) || uriLower.contains(".flac") -> "FLAC"
                mime.contains("wav", ignoreCase = true) || uriLower.contains(".wav") -> "WAV"
                mime.contains("mp4a", ignoreCase = true) || mime.contains("aac", ignoreCase = true) || uriLower.contains(".m4a") -> "AAC"
                mime.contains("mp3", ignoreCase = true) || mime.contains("mpeg", ignoreCase = true) || uriLower.contains(".mp3") -> "MP3"
                mime.contains("ogg", ignoreCase = true) || mime.contains("vorbis", ignoreCase = true) -> "OGG"
                else -> track.formatDisplay
            }

            _uiState.value = _uiState.value.copy(
                durationMs = totalDurationMs,
                sourceFormatInfo = "$codecDisplay • ${sourceSampleRate / 1000.0} kHz • ${if (sourceChannels == 2) "Stereo" else "Mono"}",
                playerState = PlayerState.PLAYING
            )

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(mediaFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var isEos = false
            var isStartingRamp = true
            val targetCushionBytes = 256_000 // Deep ~1.5s reservoir prevents starvation underruns completely when minimized

            while (currentCoroutineContext().isActive && !isStopped.get()) {
                // Check if user paused
                if (isPaused.get()) {
                    isStartingRamp = true // On resume, smoothly ramp in with 15ms Hann curve
                    delay(30)
                    continue
                }

                // Check pending seek
                val seekMs = pendingSeekMs.getAndSet(-1L)
                if (seekMs >= 0L) {
                    writeSoftFadeOut(targetSampleRate, targetBitDepth)
                    val seekUs = seekMs * 1000L
                    extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    codec.flush()
                    StreamingService.instance?.clearRingBuffer()
                    isStartingRamp = true
                }

                // Regulate buffer cushion: if ring buffer has > 256KB (~1.5s), yield CPU to transmitter
                val buffered = StreamingService.instance?.getRingBufferAvailable() ?: 0
                if (buffered >= targetCushionBytes) {
                    delay(25)
                    continue
                }

                // Feed input to decoder
                if (!isEos) {
                    val inIndex = codec.dequeueInputBuffer(10_000L)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isEos = true
                            } else {
                                val presentationTimeUs = extractor.sampleTime
                                codec.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                // Retrieve decoded PCM samples
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                if (outIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val pcmBytes = ByteArray(bufferInfo.size)
                        outputBuffer.get(pcmBytes)

                        // Format & resample to user's Settings choice (dynamic check allows on-the-fly 16->24 bit switching)
                        val activePrefs = preferencesRepository.userPreferences.value
                        val activeTargetSampleRate = activePrefs.sampleRate
                        val activeTargetBitDepth = activePrefs.bitDepth

                        val formattedPcm = formatPcmStream(
                            inputPcm = pcmBytes,
                            sourceSampleRate = sourceSampleRate,
                            sourceChannels = sourceChannels,
                            targetSampleRate = activeTargetSampleRate,
                            targetBitDepth = activeTargetBitDepth
                        )

                        if (formattedPcm.isNotEmpty()) {
                            // Apply real-time audiophile DSP processing (unless bit-perfect direct mode is active)
                            if (!activePrefs.bitPerfectMode) {
                                dspEngine.process(
                                    buffer = formattedPcm,
                                    length = formattedPcm.size,
                                    bitDepth = activeTargetBitDepth,
                                    channels = 2
                                )
                            }

                            // Apply 25ms anti-pop soft-ramp on track start or seek resume
                            if (isStartingRamp) {
                                applySoftRamp(formattedPcm, activeTargetSampleRate, activeTargetBitDepth, isFadeOut = false)
                                isStartingRamp = false
                            }

                            // Capture last stereo sample in chunk for seamless soft fade-out
                            if (activeTargetBitDepth == 32 && formattedPcm.size >= 8) {
                                val offset = formattedPcm.size - 8
                                val l0 = formattedPcm[offset].toInt() and 0xFF
                                val l1 = formattedPcm[offset + 1].toInt() and 0xFF
                                val l2 = formattedPcm[offset + 2].toInt() and 0xFF
                                val l3 = formattedPcm[offset + 3].toInt() and 0xFF
                                lastSampleL = (l3 shl 24) or (l2 shl 16) or (l1 shl 8) or l0

                                val r0 = formattedPcm[offset + 4].toInt() and 0xFF
                                val r1 = formattedPcm[offset + 5].toInt() and 0xFF
                                val r2 = formattedPcm[offset + 6].toInt() and 0xFF
                                val r3 = formattedPcm[offset + 7].toInt() and 0xFF
                                lastSampleR = (r3 shl 24) or (r2 shl 16) or (r1 shl 8) or r0
                            } else if (activeTargetBitDepth == 24 && formattedPcm.size >= 6) {
                                val offset = formattedPcm.size - 6
                                val l0 = formattedPcm[offset].toInt() and 0xFF
                                val l1 = formattedPcm[offset + 1].toInt() and 0xFF
                                val l2 = formattedPcm[offset + 2].toInt() and 0xFF
                                var sL = (l2 shl 16) or (l1 shl 8) or l0
                                if ((sL and 0x800000) != 0) sL = sL or 0xFF000000.toInt()
                                lastSampleL = sL

                                val r0 = formattedPcm[offset + 3].toInt() and 0xFF
                                val r1 = formattedPcm[offset + 4].toInt() and 0xFF
                                val r2 = formattedPcm[offset + 5].toInt() and 0xFF
                                var sR = (r2 shl 16) or (r1 shl 8) or r0
                                if ((sR and 0x800000) != 0) sR = sR or 0xFF000000.toInt()
                                lastSampleR = sR
                            } else if (activeTargetBitDepth == 16 && formattedPcm.size >= 4) {
                                val offset = formattedPcm.size - 4
                                val lLow = formattedPcm[offset].toInt() and 0xFF
                                val lHigh = formattedPcm[offset + 1].toInt() and 0xFF
                                var sL = (lHigh shl 8) or lLow
                                if ((sL and 0x8000) != 0) sL = sL or 0xFFFF0000.toInt()
                                lastSampleL = sL

                                val rLow = formattedPcm[offset + 2].toInt() and 0xFF
                                val rHigh = formattedPcm[offset + 3].toInt() and 0xFF
                                var sR = (rHigh shl 8) or rLow
                                if ((sR and 0x8000) != 0) sR = sR or 0xFFFF0000.toInt()
                                lastSampleR = sR
                            }

                            // Backpressure check: wait until ring buffer has enough free space to avoid dropping frames
                            while (currentCoroutineContext().isActive && !isStopped.get()) {
                                val freeSpace = StreamingService.instance?.getRingBufferFreeSpace() ?: 512_000
                                if (freeSpace >= formattedPcm.size) break
                                delay(10)
                            }

                            // Write directly to RingBuffer for PacedAudioTransmitter: ZERO phone speaker leak
                            StreamingService.instance?.writeDirectAudio(formattedPcm, 0, formattedPcm.size)
                        }

                        val currentPositionMs = bufferInfo.presentationTimeUs / 1000L
                        if (currentPositionMs >= 0) {
                            val lastPos = _uiState.value.positionMs
                            if (currentPositionMs - lastPos >= 250L || currentPositionMs < lastPos) {
                                _uiState.value = _uiState.value.copy(positionMs = currentPositionMs)
                            }
                        }
                    }

                    codec.releaseOutputBuffer(outIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        // Wait for transmitter to drain remaining buffered audio before transitioning
                        while (currentCoroutineContext().isActive && !isStopped.get()) {
                            val remaining = StreamingService.instance?.getRingBufferAvailable() ?: 0
                            if (remaining <= 1024) break
                            delay(40)
                        }
                        break // Track ended
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    Log.i(TAG, "Decoder output format changed: $newFormat")
                }
            }

            // Track completed naturally
            if (!isStopped.get()) {
                if (_uiState.value.repeatMode == PlayerRepeatMode.ONE) {
                    playTrackAtIndex(_uiState.value.currentIndex)
                } else {
                    playNext()
                }
            }
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {}
            try {
                extractor.release()
            } catch (_: Exception) {}
            try {
                pfd?.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Resamples and bit-converts decoded PCM samples to the user's active Settings choice.
     */
    private fun formatPcmStream(
        inputPcm: ByteArray,
        sourceSampleRate: Int,
        sourceChannels: Int,
        targetSampleRate: Int,
        targetBitDepth: Int
    ): ByteArray {
        if (inputPcm.size < 2 || sourceChannels <= 0 || sourceSampleRate <= 0) {
            return ByteArray(0)
        }

        // Fast passthrough if source already matches target (standard 48kHz 16-bit stereo)
        if (sourceSampleRate == targetSampleRate && sourceChannels == 2 && targetBitDepth == 16) {
            return inputPcm
        }

        val shortBuffer = ByteBuffer.wrap(inputPcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val totalSourceSamples = shortBuffer.remaining()
        val sourceFrames = totalSourceSamples / sourceChannels
        if (sourceFrames <= 0) return ByteArray(0)

        // 1. Resample if necessary (linear interpolation)
        val targetFrames = if (sourceSampleRate == targetSampleRate) {
            sourceFrames
        } else {
            ((sourceFrames.toLong() * targetSampleRate) / sourceSampleRate).toInt()
        }
        if (targetFrames <= 0) return ByteArray(0)

        val bytesPerSample = targetBitDepth / 8
        val outputBytes = ByteArray(targetFrames * 2 * bytesPerSample) // always stereo output
        var outIdx = 0

        val ratio = sourceSampleRate.toDouble() / targetSampleRate.toDouble()

        for (i in 0 until targetFrames) {
            val srcPos = i * ratio
            val srcIndex = srcPos.toInt().coerceAtMost(sourceFrames - 1)
            val nextIndex = (srcIndex + 1).coerceAtMost(sourceFrames - 1)
            val frac = (srcPos - srcIndex).toFloat()

            // Read source samples
            val left1: Short
            val right1: Short
            val left2: Short
            val right2: Short

            if (sourceChannels == 1) {
                left1 = shortBuffer.get(srcIndex)
                right1 = left1
                left2 = shortBuffer.get(nextIndex)
                right2 = left2
            } else {
                left1 = shortBuffer.get(srcIndex * 2)
                right1 = shortBuffer.get(srcIndex * 2 + 1)
                left2 = shortBuffer.get(nextIndex * 2)
                right2 = shortBuffer.get(nextIndex * 2 + 1)
            }

            val finalLeft = (left1 + frac * (left2 - left1)).toInt().coerceIn(-32768, 32767)
            val finalRight = (right1 + frac * (right2 - right1)).toInt().coerceIn(-32768, 32767)

            if (targetBitDepth == 32) {
                val s32L = finalLeft shl 16
                outputBytes[outIdx++] = (s32L and 0xFF).toByte()
                outputBytes[outIdx++] = ((s32L shr 8) and 0xFF).toByte()
                outputBytes[outIdx++] = ((s32L shr 16) and 0xFF).toByte()
                outputBytes[outIdx++] = ((s32L shr 24) and 0xFF).toByte()

                val s32R = finalRight shl 16
                outputBytes[outIdx++] = (s32R and 0xFF).toByte()
                outputBytes[outIdx++] = ((s32R shr 8) and 0xFF).toByte()
                outputBytes[outIdx++] = ((s32R shr 16) and 0xFF).toByte()
                outputBytes[outIdx++] = ((s32R shr 24) and 0xFF).toByte()
            } else if (targetBitDepth == 24) {
                val s24L = finalLeft shl 8
                outputBytes[outIdx++] = (s24L and 0xFF).toByte()
                outputBytes[outIdx++] = ((s24L shr 8) and 0xFF).toByte()
                outputBytes[outIdx++] = ((s24L shr 16) and 0xFF).toByte()

                val s24R = finalRight shl 8
                outputBytes[outIdx++] = (s24R and 0xFF).toByte()
                outputBytes[outIdx++] = ((s24R shr 8) and 0xFF).toByte()
                outputBytes[outIdx++] = ((s24R shr 16) and 0xFF).toByte()
            } else {
                outputBytes[outIdx++] = (finalLeft and 0xFF).toByte()
                outputBytes[outIdx++] = ((finalLeft shr 8) and 0xFF).toByte()
                outputBytes[outIdx++] = (finalRight and 0xFF).toByte()
                outputBytes[outIdx++] = ((finalRight shr 8) and 0xFF).toByte()
            }
        }

        return outputBytes
    }

    /**
     * Scans an entire folder picked by user via Storage Access Framework using DocumentsContract.
     * Recursively traverses subfolders up to 4 levels deep and safely extracts audio files.
     * Persists the folder URI and takePersistableUriPermission so it's never forgotten.
     */
    fun loadFromFolder(folderUri: Uri, onComplete: (Int) -> Unit) {
        playerScope.launch(Dispatchers.IO) {
            val audioExtensions = setOf("mp3", "flac", "wav", "m4a", "ogg", "aac", "opus")
            val discovered = mutableListOf<AudioTrack>()

            try {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        folderUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    Log.i(TAG, "Acquired persistable URI permission for folder: $folderUri")
                } catch (pe: Throwable) {
                    Log.w(TAG, "Notice taking persistable URI permission: ${pe.message}")
                }

                // Extract folder display name
                val folderName = try {
                    var n: String? = null
                    context.contentResolver.query(folderUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            n = cursor.getString(0)
                        }
                    }
                    n ?: if (DocumentsContract.isDocumentUri(context, folderUri)) {
                        DocumentsContract.getDocumentId(folderUri).substringAfterLast(":").substringAfterLast("/")
                    } else {
                        DocumentsContract.getTreeDocumentId(folderUri).substringAfterLast(":").substringAfterLast("/")
                    }
                } catch (_: Throwable) {
                    "Music Folder"
                }

                // Persist folder URI and name immediately in SharedPreferences
                val prefs = context.getSharedPreferences("c3_player_state", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("last_folder_uri", folderUri.toString())
                    .putString("last_folder_name", folderName)
                    .apply()

                withContext(Dispatchers.Main) {
                    savedFolderName.value = folderName
                }

                val rootDocId = try {
                    if (DocumentsContract.isDocumentUri(context, folderUri)) {
                        DocumentsContract.getDocumentId(folderUri)
                    } else {
                        DocumentsContract.getTreeDocumentId(folderUri)
                    }
                } catch (_: Throwable) {
                    try {
                        DocumentsContract.getTreeDocumentId(folderUri)
                    } catch (_: Throwable) {
                        null
                    }
                }

                if (rootDocId != null) {
                    scanDirectory(folderUri, rootDocId, discovered, audioExtensions, depth = 0, maxDepth = 4)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error scanning folder: ${e.message}", e)
            }

            if (discovered.isNotEmpty()) {
                val currentTrack = _uiState.value.currentTrack ?: discovered.firstOrNull()
                val currentIdx = if (_uiState.value.currentIndex < 0) 0 else _uiState.value.currentIndex
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        playlist = discovered,
                        currentTrack = currentTrack,
                        currentIndex = currentIdx
                    )
                }
                savePlaylistToDisk(discovered, currentIdx, folderUri)
            }

            // Always dispatch UI callback / toast on Main thread to prevent background thread crash
            withContext(Dispatchers.Main) {
                try {
                    onComplete(discovered.size)
                } catch (e: Throwable) {
                    Log.e(TAG, "onComplete callback error: ${e.message}")
                }
            }
        }
    }

    /**
     * Rock-solid folder rescan:
     * Discovers newly added audio files and removes deleted ones without stopping or interrupting active playback.
     */
    fun rescanCurrentFolder(onResult: (newCount: Int, removedCount: Int) -> Unit = { _, _ -> }) {
        if (isRescanning.value) return
        val diskPrefs = context.getSharedPreferences("c3_player_state", Context.MODE_PRIVATE)
        val uriStr = diskPrefs.getString("last_folder_uri", null)
        if (uriStr.isNullOrBlank()) {
            playerScope.launch(Dispatchers.Main) {
                onResult(0, 0)
            }
            return
        }
        val folderUri = Uri.parse(uriStr)

        isRescanning.value = true
        playerScope.launch(Dispatchers.IO) {
            var newCount = 0
            var removedCount = 0

            try {
                // Ensure permission
                try {
                    context.contentResolver.takePersistableUriPermission(
                        folderUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Throwable) {}

                val audioExtensions = setOf("mp3", "flac", "wav", "m4a", "ogg", "aac", "opus")
                val discovered = mutableListOf<AudioTrack>()

                val rootDocId = try {
                    if (DocumentsContract.isDocumentUri(context, folderUri)) {
                        DocumentsContract.getDocumentId(folderUri)
                    } else {
                        DocumentsContract.getTreeDocumentId(folderUri)
                    }
                } catch (_: Throwable) {
                    try {
                        DocumentsContract.getTreeDocumentId(folderUri)
                    } catch (_: Throwable) {
                        null
                    }
                }

                if (rootDocId != null) {
                    scanDirectory(folderUri, rootDocId, discovered, audioExtensions, depth = 0, maxDepth = 4)
                }

                val currentPlaylist = _uiState.value.playlist
                val currentUris = currentPlaylist.map { it.uri.toString() }.toSet()
                val discoveredUris = discovered.map { it.uri.toString() }.toSet()

                val newlyFound = discovered.filter { it.uri.toString() !in currentUris }
                val removedUrisSet = currentUris.filter { it !in discoveredUris }.toSet()

                newCount = newlyFound.size
                removedCount = removedUrisSet.size

                if (newlyFound.isNotEmpty() || removedUrisSet.isNotEmpty()) {
                    val updatedList = currentPlaylist.filter { it.uri.toString() !in removedUrisSet }.toMutableList()
                    updatedList.addAll(newlyFound)

                    val currentTrack = _uiState.value.currentTrack
                    var targetIdx = _uiState.value.currentIndex
                    if (currentTrack != null) {
                        val foundIdx = updatedList.indexOfFirst { it.uri.toString() == currentTrack.uri.toString() }
                        targetIdx = if (foundIdx >= 0) foundIdx else targetIdx.coerceIn(0, (updatedList.size - 1).coerceAtLeast(0))
                    }

                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(
                            playlist = updatedList,
                            currentIndex = targetIdx
                        )
                    }
                    savePlaylistToDisk(updatedList, targetIdx, folderUri)
                    Log.i(TAG, "Rescan completed: +$newCount added, -$removedCount removed. Total: ${updatedList.size}")
                } else {
                    Log.i(TAG, "Rescan completed: No file changes detected in folder")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error during rescan: ${e.message}", e)
            } finally {
                withContext(Dispatchers.Main) {
                    isRescanning.value = false
                    try {
                        onResult(newCount, removedCount)
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun scanDirectory(
        treeUri: Uri,
        parentDocId: String,
        discovered: MutableList<AudioTrack>,
        audioExtensions: Set<String>,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth > maxDepth || discovered.size >= 1000) return

        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )

            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

                while (cursor.moveToNext()) {
                    val docId = if (idCol >= 0) cursor.getString(idCol) else null ?: continue
                    val name = (if (nameCol >= 0) cursor.getString(nameCol) else null) ?: "Audio File"
                    val mime = (if (mimeCol >= 0) cursor.getString(mimeCol) else null) ?: ""

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR || mime == "vnd.android.document/directory") {
                        // Recursively scan subfolder
                        scanDirectory(treeUri, docId, discovered, audioExtensions, depth + 1, maxDepth)
                    } else {
                        val ext = name.substringAfterLast(".", "").lowercase()
                        if (ext in audioExtensions || mime.startsWith("audio/")) {
                            val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                            val track = extractTrackMetadata(fileUri, name)
                            discovered.add(track)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed scanning directory $parentDocId: ${e.message}")
        }
    }

    fun extractTrackMetadata(uri: Uri, fallbackName: String): AudioTrack {
        var title = fallbackName.substringBeforeLast(".")
        var artist = "Local Library"
        var album = ""
        var durationMs = 0L
        var mimeType = "audio/*"
        var artworkBytes: ByteArray? = null

        var retriever: MediaMetadataRetriever? = null
        try {
            retriever = MediaMetadataRetriever()
            var dataSourceSet = false
            try {
                retriever.setDataSource(context, uri)
                dataSourceSet = true
            } catch (_: Throwable) {}

            if (!dataSourceSet) {
                try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        retriever.setDataSource(pfd.fileDescriptor, 0, pfd.statSize)
                        dataSourceSet = true
                    }
                } catch (_: Throwable) {}
            }

            if (dataSourceSet) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let {
                    if (it.isNotBlank()) title = it
                }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let {
                    if (it.isNotBlank()) artist = it
                }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let {
                    if (it.isNotBlank()) album = it
                }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let {
                    durationMs = it
                }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let {
                    mimeType = it
                }

                val uriStrLower = uri.toString().lowercase()
                val nameLower = fallbackName.lowercase()
                if (nameLower.endsWith(".opus") || uriStrLower.endsWith(".opus") || uriStrLower.contains(".opus") || mimeType.contains("opus")) {
                    mimeType = "audio/opus"
                }

                // Extract real embedded album artwork
                val rawArt = retriever.embeddedPicture
                artworkBytes = processArtwork(rawArt)
            }
        } catch (_: Throwable) {
            // Safely ignore metadata extraction errors, fallback to filename and 0 duration
        } finally {
            try {
                retriever?.release()
            } catch (_: Throwable) {}
        }

        return AudioTrack(
            id = uri.toString(),
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            uri = uri,
            mimeType = mimeType,
            artworkBytes = artworkBytes
        )
    }

    private fun processArtwork(rawBytes: ByteArray?): ByteArray? {
        if (rawBytes == null || rawBytes.isEmpty()) return null
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)
            val maxDim = 320
            var sampleSize = 1
            if (options.outHeight > maxDim || options.outWidth > maxDim) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while ((halfHeight / sampleSize) >= maxDim && (halfWidth / sampleSize) >= maxDim) {
                    sampleSize *= 2
                }
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions)
                ?: BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                ?: return rawBytes

            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            bitmap.recycle()
            stream.toByteArray()
        } catch (_: Throwable) {
            rawBytes
        }
    }

    private fun applySoftRamp(pcm: ByteArray, sampleRate: Int, bitDepth: Int, isFadeOut: Boolean = false) {
        val bytesPerSample = bitDepth / 8
        val frameSize = bytesPerSample * 2 // stereo
        val rampFrames = (sampleRate * 25) / 1000 // 25ms smooth ramp
        val framesToProcess = minOf(rampFrames, pcm.size / frameSize)
        if (framesToProcess <= 0) return

        var idx = 0
        for (f in 0 until framesToProcess) {
            val factor = if (!isFadeOut) {
                (0.5 * (1.0 - cos(Math.PI * f / framesToProcess))).toFloat()
            } else {
                (0.5 * (1.0 + cos(Math.PI * f / framesToProcess))).toFloat()
            }

            if (bitDepth == 32) {
                val l0 = pcm[idx].toInt() and 0xFF
                val l1 = pcm[idx + 1].toInt() and 0xFF
                val l2 = pcm[idx + 2].toInt() and 0xFF
                val l3 = pcm[idx + 3].toInt() and 0xFF
                val sampleL = ((l3 shl 24) or (l2 shl 16) or (l1 shl 8) or l0).toDouble()
                val rampedL = (sampleL * factor).toLong().coerceIn(-2147483648L, 2147483647L).toInt()
                pcm[idx] = (rampedL and 0xFF).toByte()
                pcm[idx + 1] = ((rampedL shr 8) and 0xFF).toByte()
                pcm[idx + 2] = ((rampedL shr 16) and 0xFF).toByte()
                pcm[idx + 3] = ((rampedL shr 24) and 0xFF).toByte()

                val r0 = pcm[idx + 4].toInt() and 0xFF
                val r1 = pcm[idx + 5].toInt() and 0xFF
                val r2 = pcm[idx + 6].toInt() and 0xFF
                val r3 = pcm[idx + 7].toInt() and 0xFF
                val sampleR = ((r3 shl 24) or (r2 shl 16) or (r1 shl 8) or r0).toDouble()
                val rampedR = (sampleR * factor).toLong().coerceIn(-2147483648L, 2147483647L).toInt()
                pcm[idx + 4] = (rampedR and 0xFF).toByte()
                pcm[idx + 5] = ((rampedR shr 8) and 0xFF).toByte()
                pcm[idx + 6] = ((rampedR shr 16) and 0xFF).toByte()
                pcm[idx + 7] = ((rampedR shr 24) and 0xFF).toByte()
            } else if (bitDepth == 24) {
                val l0 = pcm[idx].toInt() and 0xFF
                val l1 = pcm[idx + 1].toInt() and 0xFF
                val l2 = pcm[idx + 2].toInt() and 0xFF
                var sampleL = (l2 shl 16) or (l1 shl 8) or l0
                if ((sampleL and 0x800000) != 0) sampleL = sampleL or 0xFF000000.toInt()
                val rampedL = (sampleL.toFloat() * factor).toInt().coerceIn(-8388608, 8388607)
                pcm[idx] = (rampedL and 0xFF).toByte()
                pcm[idx + 1] = ((rampedL shr 8) and 0xFF).toByte()
                pcm[idx + 2] = ((rampedL shr 16) and 0xFF).toByte()

                val r0 = pcm[idx + 3].toInt() and 0xFF
                val r1 = pcm[idx + 4].toInt() and 0xFF
                val r2 = pcm[idx + 5].toInt() and 0xFF
                var sampleR = (r2 shl 16) or (r1 shl 8) or r0
                if ((sampleR and 0x800000) != 0) sampleR = sampleR or 0xFF000000.toInt()
                val rampedR = (sampleR.toFloat() * factor).toInt().coerceIn(-8388608, 8388607)
                pcm[idx + 3] = (rampedR and 0xFF).toByte()
                pcm[idx + 4] = ((rampedR shr 8) and 0xFF).toByte()
                pcm[idx + 5] = ((rampedR shr 16) and 0xFF).toByte()
            } else {
                val l0 = pcm[idx].toInt() and 0xFF
                val l1 = pcm[idx + 1].toInt() and 0xFF
                var sampleL = (l1 shl 8) or l0
                if ((sampleL and 0x8000) != 0) sampleL = sampleL or 0xFFFF0000.toInt()
                val rampedL = (sampleL.toFloat() * factor).toInt().coerceIn(-32768, 32767)
                pcm[idx] = (rampedL and 0xFF).toByte()
                pcm[idx + 1] = ((rampedL shr 8) and 0xFF).toByte()

                val r0 = pcm[idx + 2].toInt() and 0xFF
                val r1 = pcm[idx + 3].toInt() and 0xFF
                var sampleR = (r1 shl 8) or r0
                if ((sampleR and 0x8000) != 0) sampleR = sampleR or 0xFFFF0000.toInt()
                val rampedR = (sampleR.toFloat() * factor).toInt().coerceIn(-32768, 32767)
                pcm[idx + 2] = (rampedR and 0xFF).toByte()
                pcm[idx + 3] = ((rampedR shr 8) and 0xFF).toByte()
            }
            idx += frameSize
        }
    }

    private fun saveCurrentTrackIndex(index: Int) {
        playerScope.launch(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences("c3_player_state", Context.MODE_PRIVATE)
                prefs.edit().putInt("last_track_index", index).apply()
            } catch (_: Exception) {}
        }
    }

    private fun savePlaylistToDisk(tracks: List<AudioTrack>, currentIndex: Int, folderUri: Uri? = null) {
        playerScope.launch(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences("c3_player_state", Context.MODE_PRIVATE)
                val editor = prefs.edit().putInt("last_track_index", currentIndex)
                if (folderUri != null) {
                    editor.putString("last_folder_uri", folderUri.toString())
                }
                editor.apply()

                val jsonArray = JSONArray()
                for (t in tracks) {
                    val obj = JSONObject().apply {
                        put("id", t.id)
                        put("title", t.title)
                        put("artist", t.artist)
                        put("album", t.album)
                        put("durationMs", t.durationMs)
                        put("uri", t.uri.toString())
                        put("mimeType", t.mimeType)
                        put("sampleRate", t.sampleRate)
                        put("bitDepth", t.bitDepth)
                        put("channels", t.channels)
                    }
                    jsonArray.put(obj)
                }
                val file = File(context.filesDir, "saved_playlist.json")
                file.writeText(jsonArray.toString())
                Log.i(TAG, "Saved ${tracks.size} tracks to persistent playlist storage")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save playlist to disk: ${e.message}")
            }
        }
    }

    private fun restoreSavedPlaylist() {
        playerScope.launch(Dispatchers.IO) {
            try {
                val file = File(context.filesDir, "saved_playlist.json")
                if (!file.exists()) return@launch

                val content = file.readText()
                if (content.isBlank()) return@launch

                val jsonArray = JSONArray(content)
                val restored = mutableListOf<AudioTrack>()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val uriStr = obj.optString("uri")
                    if (uriStr.isBlank()) continue
                    val uri = Uri.parse(uriStr)

                    var isAccessible = true
                    try {
                        context.contentResolver.openFileDescriptor(uri, "r")?.close()
                    } catch (_: Throwable) {
                        isAccessible = false
                    }

                    if (isAccessible) {
                        val track = AudioTrack(
                            id = obj.optString("id", uriStr),
                            title = obj.optString("title", "Audio Track"),
                            artist = obj.optString("artist", "Local Library"),
                            album = obj.optString("album", ""),
                            durationMs = obj.optLong("durationMs", 0L),
                            uri = uri,
                            mimeType = obj.optString("mimeType", "audio/*"),
                            sampleRate = obj.optInt("sampleRate", 48000),
                            bitDepth = obj.optInt("bitDepth", 16),
                            channels = obj.optInt("channels", 2),
                            artworkBytes = null
                        )
                        restored.add(track)
                    }
                }

                if (restored.isNotEmpty()) {
                    val prefs = context.getSharedPreferences("c3_player_state", Context.MODE_PRIVATE)
                    val lastIdx = prefs.getInt("last_track_index", 0).coerceIn(0, restored.size - 1)
                    val targetTrack = restored.getOrNull(lastIdx) ?: restored.first()

                    val initialFmt = "${targetTrack.formatDisplay} • ${targetTrack.sampleRate / 1000.0} kHz • ${if (targetTrack.channels == 2) "Stereo" else "Mono"}"
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(
                            playlist = restored,
                            currentIndex = lastIdx,
                            currentTrack = targetTrack,
                            durationMs = targetTrack.durationMs,
                            sourceFormatInfo = initialFmt
                        )
                    }
                    Log.i(TAG, "Restored ${restored.size} tracks from persistent storage")

                    // Lazily load embedded artwork in background so UI remains instant
                    for (i in restored.indices) {
                        val t = restored[i]
                        val fullTrack = extractTrackMetadata(t.uri, t.title)
                        if (fullTrack.artworkBytes != null) {
                            val currentList = _uiState.value.playlist.toMutableList()
                            if (i in currentList.indices && currentList[i].id == t.id) {
                                currentList[i] = currentList[i].copy(artworkBytes = fullTrack.artworkBytes)
                                val updatedCurrent = if (_uiState.value.currentIndex == i) currentList[i] else _uiState.value.currentTrack
                                withContext(Dispatchers.Main) {
                                    _uiState.value = _uiState.value.copy(
                                        playlist = currentList,
                                        currentTrack = updatedCurrent
                                    )
                                }
                            }
                        }
                    }
                }

                // After restoring, check if a folder was saved and silently sync in background
                val diskPrefs = context.getSharedPreferences("c3_player_state", Context.MODE_PRIVATE)
                val folderName = diskPrefs.getString("last_folder_name", null)
                val folderUriStr = diskPrefs.getString("last_folder_uri", null)
                withContext(Dispatchers.Main) {
                    if (folderName != null) savedFolderName.value = folderName
                }
                if (!folderUriStr.isNullOrBlank()) {
                    rescanCurrentFolder { newCount, removedCount ->
                        Log.i(TAG, "Startup auto-sync finished: +$newCount new, -$removedCount removed")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error restoring playlist: ${e.message}")
            }
        }
    }

    private fun deleteSavedPlaylist() {
        playerScope.launch(Dispatchers.IO) {
            try {
                val file = File(context.filesDir, "saved_playlist.json")
                if (file.exists()) file.delete()
                val prefs = context.getSharedPreferences("c3_player_state", Context.MODE_PRIVATE)
                prefs.edit().clear().apply()
            } catch (_: Exception) {}
        }
    }
}
