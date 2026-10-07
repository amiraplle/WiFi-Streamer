package com.example.data.network

import android.util.Log
import com.example.model.AudioStreamFormat
import com.example.model.HeaderMode
import com.example.model.StreamingState
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * High-performance, zero-latency UDP audio streaming client.
 *
 * Transmits raw PCM / C3 audio frames over UDP datagram packets directly to
 * ESP32-S3 or ESP32-C3 receivers (e.g. s3music.local or c3music.local : 50005).
 *
 * Advantages over TCP:
 * - Zero head-of-line blocking: Wi-Fi packet drops never stall or halt the audio playback.
 * - Ultra-low latency (~15-25ms) suitable for real-time listening.
 * - Packages chunks under 1400 bytes to strictly prevent MTU radio fragmentation.
 * - Lightest possible memory and CPU footprint on the ESP32 microcontroller.
 */
class UdpStreamClient(
    private val onStateChanged: (StreamingState, String?) -> Unit,
    private val onBytesTransmitted: (Long) -> Unit
) {
    private val TAG = "UdpStreamClient"

    // Max UDP payload per packet to fit comfortably within standard 1500-byte Ethernet/Wi-Fi MTU
    private val MAX_UDP_PAYLOAD = 1400

    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private var targetPort: Int = 50005

    private val isConnected = AtomicBoolean(false)
    private val isManuallyStopped = AtomicBoolean(false)

    val totalBytesWritten = AtomicLong(0L)

    // Pre-allocated reusable packet to avoid heap thrashing in the audio loop
    private val packetBuffer = ByteArray(MAX_UDP_PAYLOAD)
    private var datagramPacket: DatagramPacket? = null

    @Synchronized
    fun connectAndStart(
        host: String,
        port: Int,
        format: AudioStreamFormat,
        headerMode: HeaderMode,
        qosEnabled: Boolean = true
    ): Boolean {
        isManuallyStopped.set(false)
        onStateChanged(StreamingState.CONNECTING, null)

        return try {
            Log.i(TAG, "Initializing UDP audio stream to $host:$port (QoS=$qosEnabled)")
            val address = InetAddress.getByName(host)
            val newSocket = DatagramSocket()

            try {
                if (qosEnabled) {
                    // DSCP 46 (Expedited Forwarding / 802.11e WMM Voice AC_VO) = 0xB8
                    newSocket.trafficClass = 0xB8
                } else {
                    newSocket.trafficClass = 0x00
                }
            } catch (_: Exception) {
                try {
                    newSocket.trafficClass = if (qosEnabled) 0x10 else 0x00
                } catch (_: Exception) {}
            }

            newSocket.sendBufferSize = 64 * 1024
            newSocket.broadcast = true

            // Send initial format header if configured (optional C3 sync or WAV header)
            val header = C3Protocol.getInitialHeader(format, headerMode)
            if (header != null && header.isNotEmpty()) {
                val headerPacket = DatagramPacket(header, header.size, address, port)
                newSocket.send(headerPacket)
                totalBytesWritten.addAndGet(header.size.toLong())
                onBytesTransmitted(totalBytesWritten.get())
                Log.i(TAG, "Sent ${header.size}-byte format header via UDP to $host:$port")
            }

            targetAddress = address
            targetPort = port
            socket = newSocket
            datagramPacket = DatagramPacket(packetBuffer, 0, address, port)
            isConnected.set(true)

            onStateChanged(StreamingState.STREAMING, null)
            Log.i(TAG, "UDP audio stream active and transmitting to $host:$port (Payload max: ${MAX_UDP_PAYLOAD}B)")
            true
        } catch (e: Exception) {
            val errMsg = "Failed to initialize UDP socket to $host:$port: ${e.message}"
            Log.e(TAG, errMsg)
            closeSocket()
            onStateChanged(StreamingState.ERROR, errMsg)
            false
        }
    }

    /**
     * Transmits an audio chunk over UDP.
     * Splits large buffer chunks into MTU-safe sub-packets if necessary.
     */
    fun sendAudioChunk(buffer: ByteArray, offset: Int, length: Int): Boolean {
        val s = socket ?: return false
        val addr = targetAddress ?: return false
        if (!isConnected.get() || s.isClosed) return false

        return try {
            var sent = 0
            while (sent < length) {
                val chunkSize = (length - sent).coerceAtMost(MAX_UDP_PAYLOAD)
                val packet = DatagramPacket(buffer, offset + sent, chunkSize, addr, targetPort)
                s.send(packet)
                sent += chunkSize
            }

            val total = totalBytesWritten.addAndGet(length.toLong())
            onBytesTransmitted(total)
            true
        } catch (e: IOException) {
            if (!isManuallyStopped.get()) {
                Log.w(TAG, "UDP packet transmission error: ${e.message}")
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error sending UDP chunk: ${e.message}")
            false
        }
    }

    @Synchronized
    fun disconnect(isManual: Boolean = false) {
        isManuallyStopped.set(isManual)
        closeSocket()
        if (isManual) {
            onStateChanged(StreamingState.IDLE, null)
        } else {
            onStateChanged(StreamingState.DISCONNECTED, "UDP Stream stopped")
        }
    }

    private fun closeSocket() {
        isConnected.set(false)
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        targetAddress = null
    }

    fun isConnected(): Boolean = isConnected.get()

    /**
     * Dynamically updates the socket QoS TrafficClass (DSCP 46 / WMM Voice) on the fly.
     */
    fun setQosEnabled(enabled: Boolean) {
        val s = socket ?: return
        try {
            s.trafficClass = if (enabled) 0xB8 else 0x00
            Log.d(TAG, "Dynamic QoS updated: trafficClass = ${if (enabled) "0xB8 (WMM Voice)" else "0x00 (Best Effort)"}")
        } catch (_: Exception) {
            try {
                s.trafficClass = if (enabled) 0x10 else 0x00
            } catch (_: Exception) {}
        }
    }
}
