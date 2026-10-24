package com.example.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.content.ContextCompat
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.projection.MediaProjection
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.projection.MediaProjectionManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import com.example.model.PlayerState
import com.example.model.PlayerUiState
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.AppContainer
import com.example.C3StreamerApplication
import com.example.MainActivity
import com.example.R
import com.example.data.network.PacedAudioTransmitter
import com.example.data.network.UdpStreamClient
import com.example.data.preferences.UserPreferences
import com.example.domain.audio.AudioCaptureManager
import com.example.domain.audio.AudioRingBuffer
import com.example.domain.safety.StreamSafetyCoordinator
import com.example.model.AudioSourceType
import com.example.model.CaptureStatus
import com.example.model.ProtocolMode
import com.example.model.StreamTelemetry
import com.example.model.StreamingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class StreamingService : Service() {

    private val TAG = "StreamingService"
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var mediaProjection: MediaProjection? = null
    private var mediaSession: MediaSession? = null

    // Lock-free ring buffer (128 KB = ~370ms of 44.1kHz 16-bit stereo PCM)
    private val ringBuffer = AudioRingBuffer(128 * 1024)
    private var pacedTransmitter: PacedAudioTransmitter? = null

    private var captureManager: AudioCaptureManager? = null
    private var udpClient: UdpStreamClient? = null

    private var statsJob: Job? = null
    private var reconnectJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var sessionStartTime = 0L
    private var lastBytesCount = 0L
    private var reconnectAttempts = 0
    private val isStopping = AtomicBoolean(false)
    private var savedPhoneVolume: Int = -1

    private val _telemetry = MutableStateFlow(StreamTelemetry())
    val telemetry: StateFlow<StreamTelemetry> = _telemetry.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "C3AudioStreamer:StreamingWakeLock"
        ).apply {
            setReferenceCounted(false)
        }

        // Low-latency Wi-Fi lock prevents the Wi-Fi chip from throttling packet delivery during screen lock
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            },
            "C3AudioStreamer:LowLatencyWifiLock"
        ).apply {
            setReferenceCounted(false)
        }

        // Setup system MediaSession to guarantee foreground audio playback state during screen lock
        try {
            mediaSession = MediaSession(this, "C3AudioStreamingSession").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() {
                        val player = AppContainer.getPlayerManager(this@StreamingService)
                        if (player.uiState.value.playerState == PlayerState.PAUSED) {
                            player.togglePlayPause()
                        } else if (player.uiState.value.playlist.isNotEmpty()) {
                            val idx = if (player.uiState.value.currentIndex >= 0) player.uiState.value.currentIndex else 0
                            player.playTrackAtIndex(idx)
                        } else {
                            val prefsRepo = AppContainer.getPreferences(this@StreamingService)
                            prefsRepo.setMuted(false)
                            setMuted(false)
                        }
                        updateNotification()
                    }
                    override fun onPause() {
                        val player = AppContainer.getPlayerManager(this@StreamingService)
                        if (player.uiState.value.playerState == PlayerState.PLAYING) {
                            player.togglePlayPause()
                        } else {
                            val prefsRepo = AppContainer.getPreferences(this@StreamingService)
                            prefsRepo.setMuted(true)
                            setMuted(true)
                        }
                        updateNotification()
                    }
                    override fun onSkipToNext() {
                        val player = AppContainer.getPlayerManager(this@StreamingService)
                        player.playNext()
                        updateNotification()
                    }
                    override fun onSkipToPrevious() {
                        val player = AppContainer.getPlayerManager(this@StreamingService)
                        player.playPrevious()
                        updateNotification()
                    }
                    override fun onStop() {
                        val player = AppContainer.getPlayerManager(this@StreamingService)
                        player.stopPlayback()
                        stopStreaming()
                    }
                    override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                        if (action == "ACTION_STOP" || action == ACTION_STOP) {
                            val player = AppContainer.getPlayerManager(this@StreamingService)
                            player.stopPlayback()
                            stopStreaming()
                        }
                    }
                    override fun onSeekTo(pos: Long) {
                        val player = AppContainer.getPlayerManager(this@StreamingService)
                        player.seekTo(pos)
                    }
                })
                val stopAction = PlaybackState.CustomAction.Builder(
                    "ACTION_STOP",
                    "Stop",
                    R.drawable.ic_notif_stop
                ).build()

                val state = PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO
                    )
                    .addCustomAction(stopAction)
                    .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                    .build()
                setPlaybackState(state)
                isActive = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaSession init warning: ${e.message}")
        }

        // Keep MediaSession and notification in sync with player state & current track
        serviceScope.launch {
            AppContainer.getPlayerManager(this@StreamingService).uiState.collect { playerUi ->
                updatePlayerSessionAndNotification(playerUi)
            }
        }

        udpClient = UdpStreamClient(
            onStateChanged = { state, error -> handleTransportState(state, error) },
            onBytesTransmitted = { /* updated lock-free via atomic counter */ }
        )

        // Paced transmitter pulls from ringBuffer and sends to UDP with zero-jitter rate regulation
        pacedTransmitter = PacedAudioTransmitter(
            ringBuffer = ringBuffer,
            sendChunkToTransport = { buffer, offset, length ->
                udpClient?.sendAudioChunk(buffer, offset, length) ?: false
            }
        )

        // AudioCaptureManager writes directly to ringBuffer in microseconds, completely decoupling capture from network I/O
        captureManager = AudioCaptureManager(
            onCaptureStatusChanged = { status, error -> handleCaptureStatus(status, error) },
            onAudioChunkReady = { buffer, length, _ ->
                ringBuffer.write(buffer, 0, length)
            }
        )

        // Dynamically reflect changes made in Settings (Rate pacing, Wi-Fi QoS, Bit-Perfect, Dither, Keep-Alive)
        serviceScope.launch {
            AppContainer.getPreferences(this@StreamingService).userPreferences.collect { prefs ->
                captureManager?.volumePercent = prefs.transmissionVolume
                captureManager?.isMuted = prefs.isMuted
                captureManager?.bitPerfectMode = prefs.bitPerfectMode
                captureManager?.ditherEnabled = prefs.ditherEnabled
                captureManager?.keepAliveSilence = prefs.keepAliveSilence
                pacedTransmitter?.ratePacingEnabled = prefs.ratePacing
                udpClient?.setQosEnabled(prefs.wifiQosEnabled)
            }
        }

        registerNetworkMonitor()
    }

    private fun registerNetworkMonitor() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Wi-Fi network connected. Checking if auto-reconnect needed...")
                val currentState = _telemetry.value.streamingState
                if (currentState == StreamingState.RECONNECTING || currentState == StreamingState.DISCONNECTED) {
                    val prefs = AppContainer.getPreferences(this@StreamingService).userPreferences.value
                    if (prefs.autoReconnect) {
                        Log.i(TAG, "Wi-Fi restored! Instantly triggering receiver reconnect.")
                        reconnectJob?.cancel()
                        triggerAutoReconnect(prefs, instant = true)
                    }
                }
            }
        }

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register Wi-Fi network callback: ${e.message}")
        }
    }

    var activeStreamSource: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_START -> {
                val code = intent?.getIntExtra("result_code", 0) ?: 0
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra("intent_data", Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra("intent_data")
                }
                activeStreamSource = intent?.getStringExtra("stream_source")
                if (code != 0) projectionResultCode = code
                if (data != null) projectionIntentData = data

                val prefs = AppContainer.getPreferences(this).userPreferences.value
                val effectiveSource = prefs.audioSource

                startForegroundWithNotification(effectiveSource)
                startStreamingPipeline(effectiveSource)
            }
            ACTION_START_DIRECT -> {
                activeStreamSource = "DIRECT_AUDIO"
                startForegroundWithNotification(AudioSourceType.DIRECT_AUDIO)
                startStreamingPipeline(AudioSourceType.DIRECT_AUDIO)
            }
            ACTION_STOP -> {
                val player = AppContainer.getPlayerManager(this)
                player.stopPlayback()
                stopStreaming()
            }
            ACTION_PLAY_PAUSE -> {
                val player = AppContainer.getPlayerManager(this)
                if (player.uiState.value.playerState == PlayerState.PAUSED) {
                    player.togglePlayPause()
                } else if (player.uiState.value.playerState == PlayerState.PLAYING) {
                    player.togglePlayPause()
                } else if (player.uiState.value.playlist.isNotEmpty()) {
                    val idx = if (player.uiState.value.currentIndex >= 0) player.uiState.value.currentIndex else 0
                    player.playTrackAtIndex(idx)
                }
                updateNotification()
            }
            ACTION_PREV -> {
                val player = AppContainer.getPlayerManager(this)
                player.playPrevious()
                updateNotification()
            }
            ACTION_NEXT -> {
                val player = AppContainer.getPlayerManager(this)
                player.playNext()
                updateNotification()
            }
            ACTION_RECONNECT -> {
                reconnect()
            }
            ACTION_TOGGLE_MUTE -> {
                val prefsRepo = AppContainer.getPreferences(this)
                val currentMute = prefsRepo.userPreferences.value.isMuted
                prefsRepo.setMuted(!currentMute)
                updateNotification()
            }
            ACTION_VOLUME_UP -> {
                val prefsRepo = AppContainer.getPreferences(this)
                val currentVol = prefsRepo.userPreferences.value.transmissionVolume
                prefsRepo.updateVolume((currentVol + 5).coerceAtMost(100))
                updateNotification()
            }
            ACTION_VOLUME_DOWN -> {
                val prefsRepo = AppContainer.getPreferences(this)
                val currentVol = prefsRepo.userPreferences.value.transmissionVolume
                prefsRepo.updateVolume((currentVol - 5).coerceAtLeast(0))
                updateNotification()
            }
            ACTION_CLOSE_APP -> {
                closeAppAndService()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification(effectiveSource: AudioSourceType) {
        val notification = buildNotification(
            title = getString(R.string.status_connecting),
            content = "Streaming audio to ESP32-C3…"
        )

        val hasProjection = projectionIntentData != null && projectionResultCode != 0
        val hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
            // ONLY declare MICROPHONE foreground type if user explicitly selected MICROPHONE
            if (effectiveSource == AudioSourceType.MICROPHONE && hasMicPermission) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            // ONLY declare MEDIA_PROJECTION foreground type if user explicitly selected INTERNAL_AUDIO and token is valid
            if (effectiveSource == AudioSourceType.INTERNAL_AUDIO && hasProjection && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            type
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                serviceType
            )
            Log.i(TAG, "startForeground succeeded with type=$serviceType")
        } catch (e: Exception) {
            Log.w(TAG, "Failed startForeground with type $serviceType, falling back safely: ${e.message}")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
                } else {
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback startForeground also failed: ${e2.message}")
            }
        }
    }

    private fun startStreamingPipeline(effectiveSource: AudioSourceType) {
        isStopping.set(false)
        reconnectAttempts = 0
        sessionStartTime = System.currentTimeMillis()
        lastBytesCount = 0L

        wakeLock?.acquire(8 * 3600 * 1000L) // 8-hour safe max wake lock
        try {
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        } catch (_: Exception) {}

        val prefs = AppContainer.getPreferences(this).userPreferences.value
        captureManager?.volumePercent = prefs.transmissionVolume
        captureManager?.isMuted = prefs.isMuted
        captureManager?.bitPerfectMode = prefs.bitPerfectMode
        captureManager?.ditherEnabled = prefs.ditherEnabled
        captureManager?.keepAliveSilence = prefs.keepAliveSilence
        pacedTransmitter?.ratePacingEnabled = prefs.ratePacing

        _telemetry.value = StreamTelemetry(
            streamingState = StreamingState.CONNECTING,
            captureStatus = CaptureStatus.INITIALIZING,
            format = prefs.audioFormat,
            audioSource = effectiveSource,
            targetHost = prefs.targetHost,
            targetPort = prefs.targetPort,
            protocolMode = prefs.protocolMode,
            volumePercent = prefs.transmissionVolume,
            isMuted = prefs.isMuted
        )

        // Setup MediaProjection ONLY if user specifically requested System Audio AND provided tokens
        if (effectiveSource == AudioSourceType.INTERNAL_AUDIO && mediaProjection == null) {
            val projData = projectionIntentData
            val projCode = projectionResultCode
            if (projData != null && projCode != 0) {
                // In Android 14+ (UPSIDE_DOWN_CAKE), the Foreground Service MUST ALREADY be active with
                // FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION before MediaProjectionManager.getMediaProjection can be called!
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        val fullType = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0) or
                            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
                        val n = buildNotification(getString(R.string.status_streaming), "Internal audio capture active")
                        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, fullType)
                    } catch (e: Exception) {
                        Log.w(TAG, "Foreground service upgrade exception: ${e.message}")
                    }
                }

                try {
                    val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = mpManager.getMediaProjection(projCode, projData)
                    mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.w(TAG, "MediaProjection stopped by system")
                            mediaProjection = null
                            if (!isStopping.get()) {
                                _telemetry.value = _telemetry.value.copy(
                                    captureStatus = CaptureStatus.ERROR,
                                    lastError = "MediaProjection revoked by system"
                                )
                            }
                        }
                    }, null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get MediaProjection: ${e.message}", e)
                }
            }
        }

        // Clear ring buffer for fresh stream
        ringBuffer.clear()

        // Configure jitter buffer pre-roll and start paced transmitter
        pacedTransmitter?.setTargetLatencyPreset(prefs.bufferPreset, prefs.audioFormat)
        pacedTransmitter?.start(prefs.audioFormat)

        // Start Audio Capture with effective source (Zero Screen Capture for Direct & Browser)
        val captureOk = captureManager?.startCapture(
            format = prefs.audioFormat,
            sourceType = effectiveSource,
            latencyPreset = prefs.bufferPreset,
            mediaProjection = mediaProjection
        ) ?: false

        if (!captureOk) {
            Log.e(TAG, "Audio capture initialization failed")
            _telemetry.value = _telemetry.value.copy(
                streamingState = StreamingState.ERROR,
                captureStatus = CaptureStatus.ERROR,
                lastError = "Audio capture initialization failed"
            )
            stopStreaming()
            return
        }

        // Apply DSP configuration
        isStreaming = true
        updateDspSettings(prefs)
        if (prefs.mutePhoneWhileStreaming) {
            silencePhoneSpeaker()
        }

        // Start Transport
        startTransport(prefs)

        // Start Telemetry reporting loop (1 Hz, super low power)
        startStatsLoop()
    }

    private fun startTransport(prefs: UserPreferences) {
        serviceScope.launch(Dispatchers.IO) {
            udpClient?.connectAndStart(
                host = prefs.targetHost,
                port = prefs.targetPort,
                format = prefs.audioFormat,
                headerMode = prefs.headerMode,
                qosEnabled = prefs.wifiQosEnabled
            )
        }
    }

    private fun handleTransportState(state: StreamingState, infoOrError: String?) {
        val current = _telemetry.value
        val isStreamingNow = state == StreamingState.STREAMING
        val newError = when {
            isStreamingNow -> null // Clear previous errors when successfully streaming
            state == StreamingState.ERROR || state == StreamingState.DISCONNECTED -> infoOrError
            else -> null
        }
        val clientAddress = when {
            isStreamingNow && infoOrError != null && !infoOrError.startsWith("Waiting") -> infoOrError
            state == StreamingState.IDLE || state == StreamingState.DISCONNECTED -> null
            else -> current.connectedClientAddress
        }

        _telemetry.value = current.copy(
            streamingState = state,
            lastError = newError,
            connectedClientAddress = clientAddress
        )

        updateNotification()

        if (state == StreamingState.STREAMING) {
            // Reset retry count on successful connection
            reconnectAttempts = 0
        } else if (state == StreamingState.DISCONNECTED && !isStopping.get()) {
            val prefs = AppContainer.getPreferences(this).userPreferences.value
            if (prefs.autoReconnect) {
                triggerAutoReconnect(prefs, instant = false)
            }
        }
    }

    private fun handleCaptureStatus(status: CaptureStatus, error: String?) {
        val current = _telemetry.value
        _telemetry.value = current.copy(
            captureStatus = status,
            lastError = error ?: current.lastError
        )
    }

    private fun triggerAutoReconnect(prefs: UserPreferences, instant: Boolean = false) {
        if (reconnectAttempts >= prefs.maxReconnectRetries) {
            Log.w(TAG, "Max reconnect retries reached ($reconnectAttempts). Retrying gently at 10s intervals.")
        }

        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            reconnectAttempts++
            _telemetry.value = _telemetry.value.copy(
                streamingState = StreamingState.RECONNECTING,
                reconnectCount = reconnectAttempts
            )
            updateNotification()

            if (!instant) {
                // Instant retry on first drop (0ms), then gentle exponential backoff up to 5s max
                val backoffMs = if (reconnectAttempts <= 1) {
                    0L
                } else {
                    (1000L * (reconnectAttempts - 1)).coerceAtMost(5000L)
                }
                if (backoffMs > 0) {
                    Log.i(TAG, "Auto-reconnecting attempt #$reconnectAttempts in ${backoffMs}ms...")
                    delay(backoffMs)
                }
            }

            if (!isStopping.get()) {
                Log.i(TAG, "Silent reconnect executing UDP to ${prefs.targetHost}:${prefs.targetPort}")
                udpClient?.connectAndStart(
                    host = prefs.targetHost,
                    port = prefs.targetPort,
                    format = prefs.audioFormat,
                    headerMode = prefs.headerMode,
                    qosEnabled = prefs.wifiQosEnabled
                )
            }
        }
    }

    fun reconnect() {
        val prefs = AppContainer.getPreferences(this).userPreferences.value
        reconnectAttempts = 0
        serviceScope.launch(Dispatchers.IO) {
            udpClient?.disconnect(isManual = false)
            delay(100)
            udpClient?.connectAndStart(
                host = prefs.targetHost,
                port = prefs.targetPort,
                format = prefs.audioFormat,
                headerMode = prefs.headerMode,
                qosEnabled = prefs.wifiQosEnabled
            )
        }
    }

    fun setVolume(volume: Int) {
        val clamped = volume.coerceIn(0, 100)
        captureManager?.volumePercent = clamped
        _telemetry.value = _telemetry.value.copy(volumePercent = clamped)
    }

    fun adjustRemoteVolume(delta: Int) {
        val prefsRepo = AppContainer.getPreferences(this)
        val current = _telemetry.value.volumePercent
        val newVol = (current + delta).coerceIn(0, 100)
        setVolume(newVol)
        prefsRepo.updateVolume(newVol)
    }

    fun setMuted(muted: Boolean) {
        captureManager?.isMuted = muted
        _telemetry.value = _telemetry.value.copy(isMuted = muted)
    }

    fun updateDspSettings(prefs: UserPreferences) {
        captureManager?.dspEngine?.apply {
            isEnabled = prefs.dspEnabled
            isSoftLimiterEnabled = prefs.softLimiterEnabled
            bassBoostPercent = prefs.bassBoostPercent
            trebleClarityPercent = prefs.trebleClarityPercent
            setAllBandGains(prefs.eqBandGains)
        }
    }

    private fun silencePhoneSpeaker() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            savedPhoneVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            Log.i(TAG, "Silence phone speaker enabled. Saved volume: $savedPhoneVolume")
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Could not silence phone speaker: ${e.message}")
        }
    }

    private fun restorePhoneSpeaker() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (savedPhoneVolume >= 0) {
                Log.i(TAG, "Restoring phone volume to: $savedPhoneVolume")
                am.setStreamVolume(AudioManager.STREAM_MUSIC, savedPhoneVolume, 0)
                savedPhoneVolume = -1
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore phone volume: ${e.message}")
        }
    }

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            while (isActive && !isStopping.get()) {
                delay(1000)
                val now = System.currentTimeMillis()
                val duration = if (sessionStartTime > 0) (now - sessionStartTime) / 1000 else 0

                val currentBytes = udpClient?.totalBytesWritten?.get() ?: 0L

                val deltaBytes = currentBytes - lastBytesCount
                lastBytesCount = currentBytes
                val bitrateKbps = ((deltaBytes * 8) / 1000).toInt()

                _telemetry.value = _telemetry.value.copy(
                    bytesTransmitted = currentBytes,
                    durationSeconds = duration,
                    currentBitrateKbps = bitrateKbps
                )

                updateNotification()
            }
        }
    }

    private fun updateNotification() {
        if (isStopping.get()) return
        val t = _telemetry.value
        val prefs = AppContainer.getPreferences(this).userPreferences.value

        val title = when (t.streamingState) {
            StreamingState.STREAMING -> "Streaming to ${t.targetHost}:${t.targetPort}"
            StreamingState.CONNECTING -> "Connecting to ${t.targetHost}…"
            StreamingState.RECONNECTING -> "Reconnecting to ${t.targetHost} (#${t.reconnectCount})…"
            StreamingState.DISCONNECTED -> "Disconnected from receiver"
            StreamingState.ERROR -> "Streaming Error: ${t.lastError ?: "Unknown"}"
            StreamingState.IDLE -> "Audio Studio Ready"
        }

        val volStr = "Vol: ${prefs.transmissionVolume}%${if (prefs.isMuted) " [MUTED]" else ""}"
        val content = if (t.streamingState == StreamingState.STREAMING) {
            "$volStr • ${t.format.displayName} • ${t.currentBitrateKbps} kbps"
        } else {
            "$volStr • ${t.format.displayName}"
        }

        val notification = buildNotification(title, content)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun updatePlayerSessionAndNotification(playerUi: PlayerUiState) {
        if (isStopping.get()) return
        val track = playerUi.currentTrack
        if (track != null) {
            val metaBuilder = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, track.durationMs)

            track.artworkBytes?.let { bytes ->
                try {
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bmp)
                    }
                } catch (_: Throwable) {}
            }
            mediaSession?.setMetadata(metaBuilder.build())

            val isPlaying = playerUi.playerState == PlayerState.PLAYING
            val isPaused = playerUi.playerState == PlayerState.PAUSED
            val stateCode = when {
                isPlaying -> PlaybackState.STATE_PLAYING
                isPaused -> PlaybackState.STATE_PAUSED
                else -> PlaybackState.STATE_STOPPED
            }

            val stopCustomAction = PlaybackState.CustomAction.Builder(
                "ACTION_STOP",
                "Stop",
                R.drawable.ic_notif_stop
            ).build()

            val pbState = PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_SEEK_TO
                )
                .addCustomAction(stopCustomAction)
                .setState(stateCode, playerUi.positionMs, if (isPlaying) 1.0f else 0f)
                .build()
            mediaSession?.setPlaybackState(pbState)
            mediaSession?.isActive = isPlaying || isPaused
        }

        // When player goes IDLE, immediately stop the service and dismiss notification
        if (playerUi.playerState == PlayerState.IDLE && isStreaming && activeStreamSource == "DIRECT_AUDIO") {
            stopStreaming()
            return
        }

        updateNotification()
    }

    private fun buildNotification(title: String, content: String): Notification {
        val player = AppContainer.getPlayerManager(this)
        val playerUi = player.uiState.value
        val track = playerUi.currentTrack

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Minimal, elegant content: Strictly song title and artist
        val notifTitle = track?.title ?: title
        val notifContent = track?.artist ?: content

        val mediaBuilder = Notification.Builder(this, C3StreamerApplication.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stream_tile)
            .setContentTitle(notifTitle)
            .setContentText(notifContent)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        // Real Album Artwork as large icon
        track?.artworkBytes?.let { bytes ->
            try {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    mediaBuilder.setLargeIcon(bmp)
                }
            } catch (_: Throwable) {}
        }

        // 1. Previous Track Icon Action
        val prevIntent = Intent(this, StreamingService::class.java).apply { action = ACTION_PREV }
        val prevPending = PendingIntent.getService(
            this, 20, prevIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val prevAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notif_prev),
            "Previous",
            prevPending
        ).build()

        // 2. Play / Pause Icon Action
        val isPlaying = playerUi.playerState == PlayerState.PLAYING
        val playPauseIntent = Intent(this, StreamingService::class.java).apply { action = ACTION_PLAY_PAUSE }
        val playPausePending = PendingIntent.getService(
            this, 21, playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseAction = Notification.Action.Builder(
            Icon.createWithResource(
                this,
                if (isPlaying) R.drawable.ic_notif_pause else R.drawable.ic_notif_play
            ),
            if (isPlaying) "Pause" else "Play",
            playPausePending
        ).build()

        // 3. Dedicated Stop Icon Action
        val stopIntent = Intent(this, StreamingService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 22, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notif_stop),
            "Stop",
            stopPending
        ).build()

        // 4. Next Track Icon Action
        val nextIntent = Intent(this, StreamingService::class.java).apply { action = ACTION_NEXT }
        val nextPending = PendingIntent.getService(
            this, 23, nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notif_next),
            "Next",
            nextPending
        ).build()

        mediaBuilder.addAction(prevAction)
        mediaBuilder.addAction(playPauseAction)
        mediaBuilder.addAction(stopAction)
        mediaBuilder.addAction(nextAction)

        // MediaStyle renders actions as circular icons and suppresses text labels
        val mediaStyle = Notification.MediaStyle()
        mediaSession?.let {
            mediaStyle.setMediaSession(it.sessionToken)
        }
        mediaStyle.setShowActionsInCompactView(0, 1, 2, 3)
        mediaBuilder.style = mediaStyle

        return mediaBuilder.build()
    }

    private fun closeAppAndService() {
        Log.i(TAG, "closeAppAndService: Poweramp-style exit requested from notification")
        // 1. Stop streaming pipeline
        stopStreaming()

        // 2. Broadcast to finish MainActivity
        try {
            val exitIntent = Intent(BROADCAST_APP_EXIT).apply {
                setPackage(packageName)
            }
            sendBroadcast(exitIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast exit intent: ${e.message}")
        }

        // 3. Remove foreground notification and stop service
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}

        stopSelf()
    }

    fun stopStreaming() {
        if (isStopping.getAndSet(true)) return

        isStreaming = false
        restorePhoneSpeaker()
        statsJob?.cancel()
        reconnectJob?.cancel()

        pacedTransmitter?.stop()
        udpClient?.disconnect(isManual = true)
        captureManager?.stopCapture()
        ringBuffer.clear()

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        if (wifiLock?.isHeld == true) {
            wifiLock?.release()
        }

        _telemetry.value = _telemetry.value.copy(
            streamingState = StreamingState.IDLE,
            captureStatus = CaptureStatus.IDLE,
            currentBitrateKbps = 0
        )

        // 1. Completely deactivate MediaSession and set STATE_NONE so Android's media carousel dismisses instantly
        try {
            mediaSession?.isActive = false
            mediaSession?.setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_NONE, 0L, 0f)
                    .build()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error resetting mediaSession state: ${e.message}")
        }

        // 2. Remove foreground notification and explicitly cancel it so no tile lingers
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            manager?.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling notification: ${e.message}")
        }

        StreamSafetyCoordinator.notifyServiceStopped()
        stopSelf()
        Log.i(TAG, "StreamingService completely stopped and notification dismissed")
    }

    override fun onDestroy() {
        stopStreaming()
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
        } catch (_: Exception) {}
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    fun writeDirectAudio(src: ByteArray, offset: Int, length: Int): Int {
        return ringBuffer.write(src, offset, length)
    }

    fun getRingBufferAvailable(): Int = ringBuffer.available

    fun getRingBufferFreeSpace(): Int = ringBuffer.capacity - ringBuffer.available

    fun clearRingBuffer() {
        ringBuffer.clear()
    }

    companion object {
        const val NOTIFICATION_ID = 50005
        const val ACTION_START = "com.example.service.action.START"
        const val ACTION_START_DIRECT = "com.example.service.action.START_DIRECT"
        const val ACTION_STOP = "com.example.service.action.STOP"
        const val ACTION_PLAY_PAUSE = "com.example.service.action.PLAY_PAUSE"
        const val ACTION_PREV = "com.example.service.action.PREV"
        const val ACTION_NEXT = "com.example.service.action.NEXT"
        const val ACTION_RECONNECT = "com.example.service.action.RECONNECT"
        const val ACTION_TOGGLE_MUTE = "com.example.service.action.TOGGLE_MUTE"
        const val ACTION_VOLUME_UP = "com.example.service.action.VOLUME_UP"
        const val ACTION_VOLUME_DOWN = "com.example.service.action.VOLUME_DOWN"
        const val ACTION_CLOSE_APP = "com.example.service.action.CLOSE_APP"
        const val BROADCAST_APP_EXIT = "com.example.service.broadcast.APP_EXIT"

        var projectionResultCode: Int = 0
        var projectionIntentData: Intent? = null

        @Volatile
        var isStreaming: Boolean = false

        var instance: StreamingService? = null
            private set

        fun startDirectStreaming(context: Context) {
            val intent = Intent(context, StreamingService::class.java).apply {
                action = ACTION_START_DIRECT
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
