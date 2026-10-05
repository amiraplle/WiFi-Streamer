package com.example.domain.audio

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

data class AudioInterruptionEvent(
    val id: Long = System.nanoTime(),
    val timestamp: Long = System.currentTimeMillis(),
    val category: String, // e.g. "OS Capture Stall", "RingBuffer Starvation", "AudioRecord Error", "Wi-Fi Transport Stall"
    val description: String,
    val durationMs: Long? = null
) {
    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
}

object AudioInterruptionLogger {
    private const val TAG = "AudioInterruption"
    private const val MAX_LOGS = 100

    private val deque = ConcurrentLinkedDeque<AudioInterruptionEvent>()
    private val _logsFlow = MutableStateFlow<List<AudioInterruptionEvent>>(emptyList())
    val logsFlow: StateFlow<List<AudioInterruptionEvent>> = _logsFlow.asStateFlow()

    fun log(category: String, description: String, durationMs: Long? = null) {
        val event = AudioInterruptionEvent(
            category = category,
            description = description,
            durationMs = durationMs
        )
        deque.addFirst(event)
        while (deque.size > MAX_LOGS) {
            deque.removeLast()
        }
        _logsFlow.value = deque.toList()
        val durationStr = if (durationMs != null) " (${durationMs}ms)" else ""
        Log.w(TAG, "[$category]$durationStr $description")
    }

    fun clear() {
        deque.clear()
        _logsFlow.value = emptyList()
    }
}
