package com.example.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.session.MediaSession
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
                if (code != 0) projectionResultCode = code
                if (data != null) projectionIntentData = data

                startForegroundWithNotification()
                startStreamingPipeline()
            }
            ACTION_STOP -> {
                stopStreaming()
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

    private fun startForegroundWithNotification() {
        val notification = buildNotification(
            title = getString(R.string.status_connecting),
            content = "Preparing audio streaming pipeline…"
        )

        val hasProjection = projectionIntentData != null && projectionResultCode != 0

        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (hasProjection && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
            Log.w(TAG, "Failed startForeground with type $serviceType, falling back to MICROPHONE: ${e.message}")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else {
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback startForeground also failed: ${e2.message}")
            }
        }
    }

    private fun startStreamingPipeline() {
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
            audioSource = prefs.audioSource,
            targetHost = prefs.targetHost,
            targetPort = prefs.targetPort,
            protocolMode = prefs.protocolMode,
            volumePercent = prefs.transmissionVolume,
            isMuted = prefs.isMuted
        )

        // Setup MediaProjection if capturing internal audio
        if (prefs.audioSource == AudioSourceType.INTERNAL_AUDIO && mediaProjection == null) {
            val projData = projectionIntentData
            val projCode = projectionResultCode
            if (projData != null && projCode != 0) {
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

                // If on Android 14+ and media projection is active, upgrade FGS type
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        val fullType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
                        val n = buildNotification(getString(R.string.status_streaming), "Internal audio capture active")
                        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, fullType)
                    } catch (e: Exception) {
                        Log.w(TAG, "Upgrade foreground type exception: ${e.message}")
                    }
                }
            }
        }

        // Clear ring buffer for fresh stream
        ringBuffer.clear()

        // Configure jitter buffer pre-roll and start paced transmitter
        pacedTransmitter?.setTargetLatencyPreset(prefs.bufferPreset, prefs.audioFormat)
        pacedTransmitter?.start(prefs.audioFormat)

        // Start Audio Capture
        val captureOk = captureManager?.startCapture(
            format = prefs.audioFormat,
            sourceType = prefs.audioSource,
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

    private fun buildNotification(title: String, content: String): Notification {
        val prefs = AppContainer.getPreferences(this).userPreferences.value

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 1. Mute / Unmute Action
        val muteIntent = Intent(this, StreamingService::class.java).apply { action = ACTION_TOGGLE_MUTE }
        val mutePendingIntent = PendingIntent.getService(
            this, 10, muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 2. Volume Down (-5%)
        val volDownIntent = Intent(this, StreamingService::class.java).apply { action = ACTION_VOLUME_DOWN }
        val volDownPendingIntent = PendingIntent.getService(
            this, 11, volDownIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 3. Volume Up (+5%)
        val volUpIntent = Intent(this, StreamingService::class.java).apply { action = ACTION_VOLUME_UP }
        val volUpPendingIntent = PendingIntent.getService(
            this, 12, volUpIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 4. Stop Action
        val stopIntent = Intent(this, StreamingService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 13, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 5. Close App [X] (Poweramp-style complete exit)
        val closeAppIntent = Intent(this, StreamingService::class.java).apply { action = ACTION_CLOSE_APP }
        val closeAppPendingIntent = PendingIntent.getService(
            this, 14, closeAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, C3StreamerApplication.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stream_tile)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                if (prefs.isMuted) R.drawable.ic_notif_vol_up else R.drawable.ic_notif_mute,
                if (prefs.isMuted) "Unmute" else "Mute",
                mutePendingIntent
            )
            .addAction(R.drawable.ic_notif_vol_down, "Vol -", volDownPendingIntent)
            .addAction(R.drawable.ic_notif_vol_up, "Vol +", volUpPendingIntent)
            .addAction(R.drawable.ic_notif_stop, "Stop", stopPendingIntent)
            .addAction(R.drawable.ic_notif_close, "Close", closeAppPendingIntent)
            .build()
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

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "StreamingService completely stopped")
    }

    override fun onDestroy() {
        stopStreaming()
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

    companion object {
        const val NOTIFICATION_ID = 50005
        const val ACTION_START = "com.example.service.action.START"
        const val ACTION_STOP = "com.example.service.action.STOP"
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
    }
}
