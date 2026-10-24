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

    private var playbackJob: Job? = null
    private val isPaused = AtomicBoolean(false)
    private val isStopped = AtomicBoolean(true)
    private val pendingSeekMs = AtomicLong(-1L)

    init {
        val initialPrefs = preferencesRepository.userPreferences.value
        _uiState.value = _uiState.value.copy(
            playlist = emptyList(),
            currentIndex = -1,
            currentTrack = null,
            outputFormatInfo = "${initialPrefs.bitDepth}-bit • ${initialPrefs.sampleRate / 1000.0} kHz"
        )

        // Observe global preferences to update the active output target format badge dynamically
        playerScope.launch {
            preferencesRepository.userPreferences.collect { prefs ->
                _uiState.value = _uiState.value.copy(
                    outputFormatInfo = "${prefs.bitDepth}-bit • ${prefs.sampleRate / 1000.0} kHz"
                )
            }
        }

        // Permanently restore saved playlist and last played track from disk
        restoreSavedPlaylist()
    }

    fun updateTargetFormat(format: com.example.model.AudioStreamFormat) {
        preferencesRepository.updateAudioFormat(format)
    }

    fun playTrackAtIndex(index: Int) {
        val list = _uiState.value.playlist
        if (index !in list.indices) return

        val track = list[index]
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

    fun stopPlayback() {
        isStopped.set(true)
        isPaused.set(false)
        playbackJob?.cancel()
        playbackJob = null
        StreamingService.instance?.clearRingBuffer()
        _uiState.value = _uiState.value.copy(playerState = PlayerState.IDLE, positionMs = 0L)
        if (StreamingService.instance != null && StreamingService.isStreaming) {
            StreamingService.instance?.stopStreaming()
        }
    }

    private fun startPlaybackWorker(track: AudioTrack) {
        // Stop any non-direct stream (e.g. system screen capture / microphone)
        if (StreamingService.instance != null && StreamingService.isStreaming && StreamingService.instance?.activeStreamSource != "DIRECT_AUDIO") {
            StreamingService.instance?.stopStreaming()
        }

        isStopped.set(true)
        playbackJob?.cancel()
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
            } else 44100

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

            _uiState.value = _uiState.value.copy(
                durationMs = totalDurationMs,
                sourceFormatInfo = "${mime.substringAfterLast("/")} • ${sourceSampleRate / 1000.0} kHz • ${if (sourceChannels == 2) "Stereo" else "Mono"}",
                playerState = PlayerState.PLAYING
            )

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(mediaFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var isEos = false
            var isStartingRamp = true
            val targetCushionBytes = 96_000 // Deep ~550ms reservoir prevents starvation underruns completely

            while (currentCoroutineContext().isActive && !isStopped.get()) {
                // Check if user paused
                if (isPaused.get()) {
                    delay(30)
                    continue
                }

                // Check pending seek
                val seekMs = pendingSeekMs.getAndSet(-1L)
                if (seekMs >= 0L) {
                    val seekUs = seekMs * 1000L
                    extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    codec.flush()
                    StreamingService.instance?.clearRingBuffer()
                    isStartingRamp = true
                }

                // Regulate buffer cushion: if ring buffer has > 96KB (~550ms), yield CPU to transmitter
                val buffered = StreamingService.instance?.getRingBufferAvailable() ?: 0
                if (buffered >= targetCushionBytes) {
                    delay(20)
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

                        // Format & resample to user's Settings choice
                        val formattedPcm = formatPcmStream(
                            inputPcm = pcmBytes,
                            sourceSampleRate = sourceSampleRate,
                            sourceChannels = sourceChannels,
                            targetSampleRate = targetSampleRate,
                            targetBitDepth = targetBitDepth
                        )

                        if (formattedPcm.isNotEmpty()) {
                            // Apply 15ms anti-pop soft-ramp on track start or seek resume
                            if (isStartingRamp) {
                                applySoftRamp(formattedPcm, targetSampleRate, targetBitDepth)
                                isStartingRamp = false
                            }

                            // Write directly to RingBuffer for PacedAudioTransmitter: ZERO phone speaker leak
                            StreamingService.instance?.writeDirectAudio(formattedPcm, 0, formattedPcm.size)
                        }

                        val currentPositionMs = bufferInfo.presentationTimeUs / 1000L
                        if (currentPositionMs >= 0) {
                            _uiState.value = _uiState.value.copy(positionMs = currentPositionMs)
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

        // Fast passthrough if source already matches target (standard 44.1kHz 16-bit stereo)
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

            if (targetBitDepth == 24) {
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
     * Recursively traverses subfolders up to 3 levels deep and safely extracts audio files.
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
                } catch (_: Throwable) {}

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
                    scanDirectory(folderUri, rootDocId, discovered, audioExtensions, depth = 0, maxDepth = 3)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error scanning folder: ${e.message}", e)
            }

            if (discovered.isNotEmpty()) {
                addTracks(discovered, playImmediately = false)
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
            // Safely open ParcelFileDescriptor to ensure readable permission without native crashes
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                retriever = MediaMetadataRetriever()
                retriever?.setDataSource(pfd.fileDescriptor)
                retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let {
                    if (it.isNotBlank()) title = it
                }
                retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let {
                    if (it.isNotBlank()) artist = it
                }
                retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let {
                    if (it.isNotBlank()) album = it
                }
                retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let {
                    durationMs = it
                }
                retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let {
                    mimeType = it
                }
                // Extract real embedded album artwork
                val rawArt = retriever?.embeddedPicture
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
            val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions) ?: return null
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            bitmap.recycle()
            stream.toByteArray()
        } catch (_: Throwable) {
            rawBytes
        }
    }

    private fun applySoftRamp(pcm: ByteArray, sampleRate: Int, bitDepth: Int) {
        val bytesPerSample = bitDepth / 8
        val frameSize = bytesPerSample * 2 // stereo
        val rampFrames = (sampleRate * 15) / 1000 // 15ms
        val framesToProcess = minOf(rampFrames, pcm.size / frameSize)
        if (framesToProcess <= 0) return

        var idx = 0
        for (f in 0 until framesToProcess) {
            val factor = (0.5 * (1.0 - cos(Math.PI * f / framesToProcess))).toFloat()
            if (bitDepth == 16) {
                val lLow = pcm[idx].toInt() and 0xFF
                val lHigh = pcm[idx + 1].toInt()
                val lSample = ((lHigh shl 8) or lLow).toShort()
                val rampedL = (lSample * factor).toInt().coerceIn(-32768, 32767).toShort()
                pcm[idx] = (rampedL.toInt() and 0xFF).toByte()
                pcm[idx + 1] = ((rampedL.toInt() shr 8) and 0xFF).toByte()

                val rLow = pcm[idx + 2].toInt() and 0xFF
                val rHigh = pcm[idx + 3].toInt()
                val rSample = ((rHigh shl 8) or rLow).toShort()
                val rampedR = (rSample * factor).toInt().coerceIn(-32768, 32767).toShort()
                pcm[idx + 2] = (rampedR.toInt() and 0xFF).toByte()
                pcm[idx + 3] = ((rampedR.toInt() shr 8) and 0xFF).toByte()
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
                            sampleRate = obj.optInt("sampleRate", 44100),
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

                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(
                            playlist = restored,
                            currentIndex = lastIdx,
                            currentTrack = targetTrack,
                            durationMs = targetTrack.durationMs
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
