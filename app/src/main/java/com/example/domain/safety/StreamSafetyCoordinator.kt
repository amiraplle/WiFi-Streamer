package com.example.domain.safety

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.service.StreamingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ActiveStreamProcess(val id: String, val displayName: String) {
    NONE("none", "Standby"),
    SYSTEM_STREAM("system", "System Audio Stream"),
    BROWSER_STREAM("browser", "In-App Browser Stream")
}

/**
 * Safety State Checker & Process Interlock Engine.
 * Guarantees strict single-process isolation:
 * If one streaming process is running (e.g. Browser), the other (System) CANNOT be ON.
 * Starting one immediately disengages and terminates the other.
 */
object StreamSafetyCoordinator {
    private const val TAG = "StreamSafety"

    private val _activeProcess = MutableStateFlow(ActiveStreamProcess.NONE)
    val activeProcess: StateFlow<ActiveStreamProcess> = _activeProcess.asStateFlow()

    private val _safetyEventNotice = MutableStateFlow<String?>(null)
    val safetyEventNotice: StateFlow<String?> = _safetyEventNotice.asStateFlow()

    /**
     * Activates System Audio Streaming.
     * If Browser Streaming is currently running, it is immediately killed before starting.
     */
    fun startSystemStream(context: Context, resultCode: Int, data: Intent?) {
        val previousProcess = _activeProcess.value
        if (previousProcess == ActiveStreamProcess.BROWSER_STREAM) {
            Log.w(TAG, "SAFETY INTERLOCK: Terminating active Browser stream before starting System capture!")
            _safetyEventNotice.value = "Safety Interlock: In-App Browser stream was disengaged to run System Audio capture."
            // Shut down existing service transport
            stopTransport(context)
        } else {
            _safetyEventNotice.value = "Safety Interlock: System Audio capture active. Browser audio interlocked (OFF)."
        }

        _activeProcess.value = ActiveStreamProcess.SYSTEM_STREAM

        StreamingService.projectionResultCode = resultCode
        StreamingService.projectionIntentData = data

        val startIntent = Intent(context, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START
            putExtra("result_code", resultCode)
            if (data != null) {
                putExtra("intent_data", data)
            }
            putExtra("stream_source", "system")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(startIntent)
        } else {
            context.startService(startIntent)
        }
    }

    /**
     * Activates In-App Browser Streaming.
     * If System Audio Streaming is currently running, it is immediately killed before starting.
     */
    fun startBrowserStream(context: Context, resultCode: Int = 0, data: Intent? = null) {
        val previousProcess = _activeProcess.value
        if (previousProcess == ActiveStreamProcess.SYSTEM_STREAM) {
            Log.w(TAG, "SAFETY INTERLOCK: Terminating active System Audio capture before starting Browser stream!")
            _safetyEventNotice.value = "Safety Interlock: System Audio capture was disengaged to run In-App Browser stream."
            // Shut down existing service transport
            stopTransport(context)
        } else {
            _safetyEventNotice.value = "Safety Interlock: In-App Browser stream active. System Audio capture interlocked (OFF)."
        }

        _activeProcess.value = ActiveStreamProcess.BROWSER_STREAM

        if (resultCode != 0 && data != null) {
            StreamingService.projectionResultCode = resultCode
            StreamingService.projectionIntentData = data
        }

        val startIntent = Intent(context, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START
            if (resultCode != 0) {
                putExtra("result_code", resultCode)
            }
            if (data != null) {
                putExtra("intent_data", data)
            }
            putExtra("stream_source", "browser")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(startIntent)
        } else {
            context.startService(startIntent)
        }
    }

    /**
     * Explicit stop requested by user or UI button.
     */
    fun stopCurrentStream(context: Context) {
        val prev = _activeProcess.value
        _activeProcess.value = ActiveStreamProcess.NONE
        _safetyEventNotice.value = "All streams disengaged. Safety interlock returned to Standby."
        stopTransport(context)
    }

    private fun stopTransport(context: Context) {
        val stopIntent = Intent(context, StreamingService::class.java).apply {
            action = StreamingService.ACTION_STOP
        }
        context.startService(stopIntent)
    }

    /**
     * Called when the StreamingService shuts down via notification or error.
     */
    fun notifyServiceStopped() {
        _activeProcess.value = ActiveStreamProcess.NONE
    }

    fun dismissNotice() {
        _safetyEventNotice.value = null
    }
}
