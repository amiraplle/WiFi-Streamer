package com.example.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
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
import com.example.data.network.HttpStreamServer
import com.example.data.network.TcpStreamClient
import com.example.data.preferences.UserPreferences
import com.example.domain.audio.AudioCaptureManager
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
    private var mediaProjection: MediaProjection? = null

    private var captureManager: AudioCaptureManager? = null
    private var tcpClient: TcpStreamClient? = null
    private var httpServer: HttpStreamServer? = null

    private var statsJob: Job? = null
    private var reconnectJob: Job? = null

    private var sessionStartTime = 0L
    private var lastBytesCount = 0L
    private var reconnectAttempts = 0
    private val isStopping = AtomicBoolean(false)

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

        tcpClient = TcpStreamClient(
            onStateChanged = { state, error -> handleTransportState(state, error) },
            onBytesTransmitted = { /* updated lock-free via atomic */ }
        )

        httpServer = HttpStreamServer(
            onStateChanged = { state, error -> handleTransportState(state, error) },
            onBytesTransmitted = { /* updated lock-free via atomic */ },
            onActiveClientsChanged = { count ->
                _telemetry.value = _telemetry.value.copy(activeClientsCount = count)
            }
        )

        captureManager = AudioCaptureManager(
            onCaptureStatusChanged = { status, error -> handleCaptureStatus(status, error) },
            onAudioChunkReady = { buffer, length, _ ->
                if (_telemetry.value.protocolMode == ProtocolMode.RAW_TCP_CLIENT) {
                    tcpClient?.sendAudioChunk(buffer, 0, length)
                } else {
                    httpServer?.broadcastAudioChunk(buffer, 0, length)
                }
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_START -> {
                startForegroundWithNotification()
                startStreamingPipeline()
            }
            ACTION_STOP -> {
                stopStreaming()
            }
            ACTION_RECONNECT -> {
                reconnect()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification(
            title = getString(R.string.status_connecting),
            content = "Preparing audio streaming pipeline…"
        )

        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
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
        } catch (e: Exception) {
            Log.w(TAG, "Failed startForeground with type $serviceType, falling back to 0: ${e.message}")
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
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

        wakeLock?.acquire(8 * 3600 * 1000L) // Safe 8-hour max wake lock

        val prefs = AppContainer.getPreferences(this).userPreferences.value
        captureManager?.volumePercent = prefs.transmissionVolume
        captureManager?.isMuted = prefs.isMuted

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

        // Start Audio Capture
        val captureOk = captureManager?.startCapture(
            format = prefs.audioFormat,
            sourceType = prefs.audioSource,
            latencyPreset = prefs.bufferPreset,
            mediaProjection = mediaProjection
        ) ?: false

        if (!captureOk) {
            Log.e(TAG, "Audio capture initialization failed")
            return
        }

        // Start Transport
        startTransport(prefs)

        // Start Telemetry reporting loop
        startStatsLoop()
    }

    private fun startTransport(prefs: UserPreferences) {
        serviceScope.launch(Dispatchers.IO) {
            if (prefs.protocolMode == ProtocolMode.RAW_TCP_CLIENT) {
                tcpClient?.connectAndStart(
                    host = prefs.targetHost,
                    port = prefs.targetPort,
                    timeoutMs = prefs.connectionTimeoutMs,
                    format = prefs.audioFormat,
                    headerMode = prefs.headerMode
                )
            } else {
                httpServer?.startServer(
                    port = prefs.httpPort,
                    format = prefs.audioFormat
                )
            }
        }
    }

    private fun handleTransportState(state: StreamingState, error: String?) {
        val current = _telemetry.value
        _telemetry.value = current.copy(
            streamingState = state,
            lastError = error ?: current.lastError
        )

        updateNotification()

        if (state == StreamingState.DISCONNECTED && !isStopping.get()) {
            val prefs = AppContainer.getPreferences(this).userPreferences.value
            if (prefs.autoReconnect && prefs.protocolMode == ProtocolMode.RAW_TCP_CLIENT) {
                triggerAutoReconnect(prefs)
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

    private fun triggerAutoReconnect(prefs: UserPreferences) {
        if (reconnectAttempts >= prefs.maxReconnectRetries) {
            Log.w(TAG, "Max reconnect retries reached ($reconnectAttempts)")
            _telemetry.value = _telemetry.value.copy(
                streamingState = StreamingState.ERROR,
                lastError = "Connection dropped. Max retry limit reached."
            )
            updateNotification()
            return
        }

        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            reconnectAttempts++
            _telemetry.value = _telemetry.value.copy(
                streamingState = StreamingState.RECONNECTING,
                reconnectCount = reconnectAttempts
            )
            updateNotification()

            val backoffMs = (1000L * reconnectAttempts).coerceAtMost(5000L)
            Log.i(TAG, "Auto-reconnecting attempt #$reconnectAttempts in ${backoffMs}ms...")
            delay(backoffMs)

            if (!isStopping.get()) {
                tcpClient?.connectAndStart(
                    host = prefs.targetHost,
                    port = prefs.targetPort,
                    timeoutMs = prefs.connectionTimeoutMs,
                    format = prefs.audioFormat,
                    headerMode = prefs.headerMode
                )
            }
        }
    }

    fun reconnect() {
        val prefs = AppContainer.getPreferences(this).userPreferences.value
        reconnectAttempts = 0
        serviceScope.launch(Dispatchers.IO) {
            tcpClient?.disconnect(isManual = false)
            delay(200)
            tcpClient?.connectAndStart(
                host = prefs.targetHost,
                port = prefs.targetPort,
                timeoutMs = prefs.connectionTimeoutMs,
                format = prefs.audioFormat,
                headerMode = prefs.headerMode
            )
        }
    }

    fun setVolume(volume: Int) {
        captureManager?.volumePercent = volume
        _telemetry.value = _telemetry.value.copy(volumePercent = volume)
    }

    fun setMuted(muted: Boolean) {
        captureManager?.isMuted = muted
        _telemetry.value = _telemetry.value.copy(isMuted = muted)
    }

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            while (isActive && !isStopping.get()) {
                delay(1000)
                val now = System.currentTimeMillis()
                val duration = if (sessionStartTime > 0) (now - sessionStartTime) / 1000 else 0

                val currentBytes = if (_telemetry.value.protocolMode == ProtocolMode.RAW_TCP_CLIENT) {
                    tcpClient?.totalBytesWritten?.get() ?: 0L
                } else {
                    httpServer?.totalBytesWritten?.get() ?: 0L
                }

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
        val title = when (t.streamingState) {
            StreamingState.STREAMING -> "Streaming to ${t.targetHost}:${t.targetPort}"
            StreamingState.CONNECTING -> "Connecting to ${t.targetHost}…"
            StreamingState.RECONNECTING -> "Reconnecting to ${t.targetHost} (#${t.reconnectCount})…"
            StreamingState.DISCONNECTED -> "Disconnected from receiver"
            StreamingState.ERROR -> "Streaming Error: ${t.lastError ?: "Unknown"}"
            StreamingState.IDLE -> "C3 Audio Streamer Ready"
        }

        val content = "${t.format.displayName} • ${t.currentBitrateKbps} kbps • ${t.formattedDuration}"
        val notification = buildNotification(title, content)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, content: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, StreamingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val reconnectIntent = Intent(this, StreamingService::class.java).apply {
            action = ACTION_RECONNECT
        }
        val reconnectPendingIntent = PendingIntent.getService(
            this, 2, reconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, C3StreamerApplication.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stream_tile)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notification_stop), stopPendingIntent)
            .addAction(android.R.drawable.ic_popup_sync, getString(R.string.notification_reconnect), reconnectPendingIntent)
            .build()
    }

    fun stopStreaming() {
        if (isStopping.getAndSet(true)) return

        statsJob?.cancel()
        reconnectJob?.cancel()

        tcpClient?.disconnect(isManual = true)
        httpServer?.stopServer()
        captureManager?.stopCapture()

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
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
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 50005
        const val ACTION_START = "com.example.service.action.START"
        const val ACTION_STOP = "com.example.service.action.STOP"
        const val ACTION_RECONNECT = "com.example.service.action.RECONNECT"

        var projectionResultCode: Int = 0
        var projectionIntentData: Intent? = null

        var instance: StreamingService? = null
            private set
    }
}
