package com.example.domain.safety

import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.service.StreamingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ActiveStreamProcess(val id: String, val displayName: String) {
    NONE("none", "Standby"),
    SYSTEM_STREAM("system", "System Audio Stream")
}

object StreamSafetyCoordinator {
    private val _activeProcess = MutableStateFlow(ActiveStreamProcess.NONE)
    val activeProcess: StateFlow<ActiveStreamProcess> = _activeProcess.asStateFlow()

    private val _safetyEventNotice = MutableStateFlow<String?>(null)
    val safetyEventNotice: StateFlow<String?> = _safetyEventNotice.asStateFlow()

    fun startSystemStream(context: Context, resultCode: Int, data: Intent?) {
        _activeProcess.value = ActiveStreamProcess.SYSTEM_STREAM

        StreamingService.projectionResultCode = resultCode
        StreamingService.projectionIntentData = data

        val service = StreamingService.instance
        if (service != null && StreamingService.isStreaming) {
            service.switchToSystemAudio(resultCode, data)
            return
        }

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

    fun stopCurrentStream(context: Context) {
        _activeProcess.value = ActiveStreamProcess.NONE
        val stopIntent = Intent(context, StreamingService::class.java).apply {
            action = StreamingService.ACTION_STOP
        }
        context.startService(stopIntent)
    }

    fun notifyServiceStopped() {
        _activeProcess.value = ActiveStreamProcess.NONE
    }

    fun dismissNotice() {
        _safetyEventNotice.value = null
    }
}
