package com.example.data.network

import android.util.Log
import com.example.model.AudioStreamFormat
import com.example.model.HeaderMode
import com.example.model.StreamingState
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class TcpStreamClient(
    private val onStateChanged: (StreamingState, String?) -> Unit,
    private val onBytesTransmitted: (Long) -> Unit
) {
    private val TAG = "TcpStreamClient"

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private val isConnected = AtomicBoolean(false)
    private val isManuallyStopped = AtomicBoolean(false)

    val totalBytesWritten = AtomicLong(0L)

    @Synchronized
    fun connectAndStart(
        host: String,
        port: Int,
        timeoutMs: Int,
        format: AudioStreamFormat,
        headerMode: HeaderMode
    ): Boolean {
        isManuallyStopped.set(false)
        onStateChanged(StreamingState.CONNECTING, null)

        return try {
            Log.i(TAG, "Connecting TCP socket to $host:$port (Timeout: ${timeoutMs}ms)")
            val newSocket = Socket()
            newSocket.tcpNoDelay = true
            newSocket.sendBufferSize = 64 * 1024
            newSocket.soTimeout = 10000

            newSocket.connect(InetSocketAddress(host, port), timeoutMs)
            val os = BufferedOutputStream(newSocket.getOutputStream(), 16 * 1024)

            // Transmit format header if applicable
            val header = C3Protocol.getInitialHeader(format, headerMode)
            if (header != null && header.isNotEmpty()) {
                Log.i(TAG, "Sending ${header.size}-byte format header to receiver")
                os.write(header)
                os.flush()
                totalBytesWritten.addAndGet(header.size.toLong())
                onBytesTransmitted(totalBytesWritten.get())
            }

            socket = newSocket
            outputStream = os
            isConnected.set(true)
            onStateChanged(StreamingState.STREAMING, null)
            Log.i(TAG, "TCP connected and streaming to $host:$port")
            true
        } catch (e: Exception) {
            val errMsg = "Connection failed to $host:$port: ${e.message}"
            Log.e(TAG, errMsg, e)
            closeSocket()
            onStateChanged(StreamingState.ERROR, errMsg)
            false
        }
    }

    /**
     * Sends an audio chunk. Called by the streaming pipeline.
     */
    fun sendAudioChunk(buffer: ByteArray, offset: Int, length: Int): Boolean {
        if (!isConnected.get() || isManuallyStopped.get()) return false

        return try {
            val os = outputStream ?: return false
            os.write(buffer, offset, length)
            os.flush()
            val total = totalBytesWritten.addAndGet(length.toLong())
            onBytesTransmitted(total)
            true
        } catch (e: IOException) {
            if (!isManuallyStopped.get()) {
                val errMsg = "TCP network transmission error: ${e.message}"
                Log.e(TAG, errMsg)
                closeSocket()
                onStateChanged(StreamingState.DISCONNECTED, errMsg)
            }
            false
        }
    }

    @Synchronized
    fun disconnect(isManual: Boolean = true) {
        isManuallyStopped.set(isManual)
        closeSocket()
        if (isManual) {
            onStateChanged(StreamingState.IDLE, null)
        }
    }

    private fun closeSocket() {
        isConnected.set(false)
        try {
            outputStream?.flush()
        } catch (_: Exception) {}
        try {
            outputStream?.close()
        } catch (_: Exception) {}
        try {
            socket?.close()
        } catch (_: Exception) {}
        outputStream = null
        socket = null
    }

    fun isStreaming(): Boolean = isConnected.get() && !isManuallyStopped.get()
}
