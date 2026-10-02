package com.example.data.network

import android.util.Log
import com.example.model.AudioStreamFormat
import com.example.model.StreamingState
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class HttpStreamServer(
    private val onStateChanged: (StreamingState, String?) -> Unit,
    private val onBytesTransmitted: (Long) -> Unit,
    private val onActiveClientsChanged: (Int) -> Unit
) {
    private val TAG = "HttpStreamServer"

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    private val clientStreams = CopyOnWriteArrayList<OutputStream>()

    val totalBytesWritten = AtomicLong(0L)

    @Synchronized
    fun startServer(port: Int, format: AudioStreamFormat): Boolean {
        if (isRunning.get()) stopServer()

        return try {
            val server = ServerSocket(port)
            serverSocket = server
            isRunning.set(true)
            onStateChanged(StreamingState.STREAMING, null)
            onActiveClientsChanged(0)

            acceptThread = Thread({
                while (isRunning.get() && !server.isClosed) {
                    try {
                        val client = server.accept()
                        handleNewClient(client, format)
                    } catch (_: Exception) {
                        break
                    }
                }
            }, "C3-HttpServer-Accept").apply {
                isDaemon = true
                start()
            }

            Log.i(TAG, "HTTP Server listening on port $port")
            true
        } catch (e: Exception) {
            val err = "Failed to start HTTP server on port $port: ${e.message}"
            Log.e(TAG, err, e)
            onStateChanged(StreamingState.ERROR, err)
            false
        }
    }

    private fun handleNewClient(clientSocket: Socket, format: AudioStreamFormat) {
        Thread({
            try {
                clientSocket.tcpNoDelay = true
                val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
                // Read HTTP request header lines
                var line = reader.readLine()
                while (!line.isNullOrEmpty()) {
                    line = reader.readLine()
                }

                val out = BufferedOutputStream(clientSocket.getOutputStream())
                val httpHeader = (
                    "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: audio/x-wav\r\n" +
                    "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Expires: 0\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray()

                out.write(httpHeader)

                // Write WAV header with 0x7FFFFFFF data length for infinite stream
                val wavHeader = C3Protocol.createWavHeader(format, dataLength = 0x7FFFFFFF)
                out.write(wavHeader)
                out.flush()

                clientStreams.add(out)
                onActiveClientsChanged(clientStreams.size)
                Log.i(TAG, "New HTTP audio client connected (Total: ${clientStreams.size})")

            } catch (e: Exception) {
                Log.w(TAG, "Error negotiating client connection: ${e.message}")
                try {
                    clientSocket.close()
                } catch (_: Exception) {}
            }
        }, "C3-HttpClient-${System.currentTimeMillis()}").start()
    }

    /**
     * Broadcasts an audio chunk to all active HTTP clients.
     */
    fun broadcastAudioChunk(buffer: ByteArray, offset: Int, length: Int) {
        if (!isRunning.get() || clientStreams.isEmpty()) return

        val deadClients = mutableListOf<OutputStream>()
        for (stream in clientStreams) {
            try {
                stream.write(buffer, offset, length)
                stream.flush()
            } catch (_: Exception) {
                deadClients.add(stream)
            }
        }

        if (deadClients.isNotEmpty()) {
            clientStreams.removeAll(deadClients)
            onActiveClientsChanged(clientStreams.size)
        }

        val total = totalBytesWritten.addAndGet(length.toLong())
        onBytesTransmitted(total)
    }

    @Synchronized
    fun stopServer() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        acceptThread?.interrupt()
        acceptThread = null

        for (stream in clientStreams) {
            try {
                stream.close()
            } catch (_: Exception) {}
        }
        clientStreams.clear()
        onActiveClientsChanged(0)
        onStateChanged(StreamingState.IDLE, null)
        Log.i(TAG, "HTTP server stopped")
    }

    fun getActiveClientCount(): Int = clientStreams.size
}
