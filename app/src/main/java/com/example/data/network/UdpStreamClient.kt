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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    private var lastKnownResolvedAddress: InetAddress? = null

    private val isConnected = AtomicBoolean(false)
    private val isManuallyStopped = AtomicBoolean(false)

    val totalBytesWritten = AtomicLong(0L)

    // Pre-allocated reusable packet to avoid heap thrashing in the audio loop
    private val packetBuffer = ByteArray(MAX_UDP_PAYLOAD)
    private var datagramPacket: DatagramPacket? = null
    private var currentFormat: AudioStreamFormat = AudioStreamFormat.FORMAT_48K_16BIT_STEREO

    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var discoveryJob: Job? = null

    @Synchronized
    fun connectAndStart(
        host: String,
        port: Int,
        format: AudioStreamFormat,
        headerMode: HeaderMode,
        qosEnabled: Boolean = true
    ): Boolean {
        isManuallyStopped.set(false)
        currentFormat = format
        targetPort = port
        discoveryJob?.cancel()

        onStateChanged(StreamingState.CONNECTING, "Searching for $host...")

        return try {
            Log.i(TAG, "Initializing UDP audio stream to $host:$port (QoS=$qosEnabled)")
            var resolvedAddress: InetAddress? = null
            try {
                val resolved = InetAddress.getByName(host)
                if (!resolved.isLoopbackAddress && resolved.hostAddress != "255.255.255.255") {
                    resolvedAddress = resolved
                    lastKnownResolvedAddress = resolved
                }
            } catch (e: Exception) {
                Log.w(TAG, "Host $host is currently offline or unresolvable: ${e.message}")
            }

            val isDeviceFound = resolvedAddress != null
            val address = resolvedAddress ?: lastKnownResolvedAddress ?: InetAddress.getByName("255.255.255.255").also {
                Log.w(TAG, "No ESP32 receiver found at $host; broadcasting to 255.255.255.255 while waiting for receiver")
            }

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

            newSocket.sendBufferSize = 128 * 1024
            newSocket.broadcast = true

            targetAddress = address
            socket = newSocket
            datagramPacket = DatagramPacket(packetBuffer, 0, address, port)
            isConnected.set(true)

            if (isDeviceFound) {
                // Send initial format header if configured (optional C3 sync or WAV header)
                val header = C3Protocol.getInitialHeader(format, headerMode)
                if (header != null && header.isNotEmpty()) {
                    val headerPacket = DatagramPacket(header, header.size, address, port)
                    newSocket.send(headerPacket)
                    totalBytesWritten.addAndGet(header.size.toLong())
                    onBytesTransmitted(totalBytesWritten.get())
                    Log.i(TAG, "Sent ${header.size}-byte format header via UDP to $host:$port")
                }
                onStateChanged(StreamingState.STREAMING, address.hostAddress)
                Log.i(TAG, "ESP32 receiver verified online at ${address.hostAddress}. Streaming active.")
            } else {
                // Device not found: Keep in CONNECTING state with clear status message
                onStateChanged(StreamingState.CONNECTING, "No device found ($host)")
                Log.w(TAG, "UDP audio stream active in background, but no device found at $host. State: CONNECTING")
                // Probe actively for 20 seconds max, then stop to strictly conserve phone battery and CPU
                startDiscoveryLoop(host, port, headerMode)
            }
            true
        } catch (e: Exception) {
            val errMsg = "Failed to initialize UDP socket to $host:$port: ${e.message}"
            Log.e(TAG, errMsg)
            closeSocket()
            onStateChanged(StreamingState.ERROR, errMsg)
            false
        }
    }

    private fun startDiscoveryLoop(host: String, port: Int, headerMode: HeaderMode) {
        discoveryJob?.cancel()
        discoveryJob = clientScope.launch {
            val maxAttempts = 10 // 10 attempts x 2s = 20s search timeout
            var attempts = 0

            while (isActive && isConnected.get() && !isManuallyStopped.get() && attempts < maxAttempts) {
                delay(2000)
                if (!isActive || !isConnected.get() || isManuallyStopped.get()) break
                attempts++

                try {
                    val check = InetAddress.getByName(host)
                    val isValid = !check.isLoopbackAddress && check.hostAddress != "255.255.255.255"

                    if (isValid) {
                        Log.i(TAG, "ESP32 receiver discovered at ${check.hostAddress}! Switching state to STREAMING.")
                        targetAddress = check
                        lastKnownResolvedAddress = check
                        datagramPacket = DatagramPacket(packetBuffer, 0, check, port)

                        val header = C3Protocol.getInitialHeader(currentFormat, headerMode)
                        if (header != null && header.isNotEmpty()) {
                            try {
                                val headerPacket = DatagramPacket(header, header.size, check, port)
                                socket?.send(headerPacket)
                            } catch (_: Exception) {}
                        }

                        onStateChanged(StreamingState.STREAMING, check.hostAddress)
                        // Device found, exit background probing loop immediately
                        return@launch
                    }
                } catch (_: Exception) {
                    // Host not resolved yet
                }
            }

            // Active 20s search window ended: cease all background network probing to save battery & CPU
            if (isConnected.get() && !isManuallyStopped.get() && (targetAddress == null || targetAddress?.hostAddress == "255.255.255.255")) {
                Log.i(TAG, "Search timeout (20s) reached for $host. Stopped background probing to save battery.")
                onStateChanged(StreamingState.CONNECTING, "No device found ($host)")
            }
        }
    }

    fun updateFormat(format: AudioStreamFormat, headerMode: HeaderMode) {
        currentFormat = format
        val addr = targetAddress
        val s = socket
        if (isConnected.get() && s != null && !s.isClosed && addr != null) {
            val header = C3Protocol.getInitialHeader(format, headerMode)
            if (header != null && header.isNotEmpty()) {
                try {
                    val headerPacket = DatagramPacket(header, header.size, addr, targetPort)
                    s.send(headerPacket)
                    totalBytesWritten.addAndGet(header.size.toLong())
                    Log.i(TAG, "Transmitted on-the-fly ${header.size}-byte format sync header to $addr:$targetPort (${format.displayName})")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed sending on-the-fly format header: ${e.message}")
                }
            }
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

        val frameSize = currentFormat.frameSizeBytes.coerceAtLeast(1)
        val maxAlignedPayload = (MAX_UDP_PAYLOAD / frameSize) * frameSize

        return try {
            if (length <= maxAlignedPayload) {
                val packet = DatagramPacket(buffer, offset, length, addr, targetPort)
                s.send(packet)
            } else {
                var sent = 0
                while (sent < length) {
                    val chunkSize = (length - sent).coerceAtMost(maxAlignedPayload)
                    val packet = DatagramPacket(buffer, offset + sent, chunkSize, addr, targetPort)
                    s.send(packet)
                    sent += chunkSize
                }
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

    /**
     * Transmits an in-band or control datagram (such as live metadata or volume)
     * directly to the receiver over the established UDP stream socket (Port 50005).
     */
    fun sendControlPacket(packetBytes: ByteArray): Boolean {
        val s = socket ?: return false
        val addr = targetAddress ?: lastKnownResolvedAddress ?: return false
        if (!isConnected.get() || s.isClosed) return false

        return try {
            val packet = DatagramPacket(packetBytes, packetBytes.size, addr, targetPort)
            s.send(packet)
            Log.d(TAG, "Sent UDP control packet (${packetBytes.size} bytes) to $addr:$targetPort")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed sending UDP control packet: ${e.message}")
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
        discoveryJob?.cancel()
        discoveryJob = null
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        targetAddress = null
    }

    fun isConnected(): Boolean = isConnected.get()

    fun getResolvedAddress(): InetAddress? = targetAddress ?: lastKnownResolvedAddress

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
