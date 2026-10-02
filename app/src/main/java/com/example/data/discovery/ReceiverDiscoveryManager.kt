package com.example.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

data class DiscoveredReceiver(
    val name: String,
    val host: String,
    val port: Int,
    val isC3Default: Boolean,
    val pingMs: Long? = null
)

class ReceiverDiscoveryManager(private val context: Context) {

    private val TAG = "ReceiverDiscovery"
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var scanJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _discoveredReceivers = MutableStateFlow<List<DiscoveredReceiver>>(emptyList())
    val discoveredReceivers: StateFlow<List<DiscoveredReceiver>> = _discoveredReceivers.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    fun startDiscovery(timeoutMs: Long = 12000L) {
        if (_isScanning.value) return
        _isScanning.value = true

        acquireMulticastLock()

        // Probe default c3music.local in background coroutine
        scanJob = scope.launch {
            probeC3MusicLocal()

            startNsdDiscovery()

            delay(timeoutMs)
            stopDiscovery()
        }
    }

    private suspend fun probeC3MusicLocal() = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()
            val address = InetAddress.getByName("c3music.local")
            val ip = address.hostAddress ?: "c3music.local"

            // Test TCP port 50005
            var reachable = false
            var pingTime: Long? = null
            try {
                val testSocket = Socket()
                testSocket.connect(InetSocketAddress(ip, 50005), 1500)
                pingTime = System.currentTimeMillis() - startTime
                testSocket.close()
                reachable = true
            } catch (_: Exception) {}

            val c3 = DiscoveredReceiver(
                name = if (reachable) "ESP32-C3 (Online)" else "c3music.local (Resolved)",
                host = ip,
                port = 50005,
                isC3Default = true,
                pingMs = pingTime
            )

            addDiscoveredReceiver(c3)
        } catch (e: Exception) {
            Log.d(TAG, "c3music.local not resolved yet: ${e.message}")
        }
    }

    private fun startNsdDiscovery() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "NSD discovery failed to start: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "NSD discovery failed to stop: $errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                Log.d(TAG, "NSD discovery started for $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                Log.d(TAG, "NSD discovery stopped")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo == null) return
                resolveService(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                Log.d(TAG, "Service lost: ${serviceInfo?.serviceName}")
            }
        }

        discoveryListener = listener
        try {
            nsdManager?.discoverServices("_c3music._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "Exception starting NSD discovery: ${e.message}")
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.w(TAG, "Resolve failed: $errorCode")
            }

            override fun onServiceResolved(resolved: NsdServiceInfo?) {
                val host = resolved?.host?.hostAddress ?: return
                val port = resolved.port
                val name = resolved.serviceName ?: "ESP32-C3 Receiver"

                val receiver = DiscoveredReceiver(
                    name = name,
                    host = host,
                    port = port,
                    isC3Default = name.contains("c3", ignoreCase = true) || host == "c3music.local"
                )
                addDiscoveredReceiver(receiver)
            }
        }

        try {
            nsdManager?.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.w(TAG, "Resolve service exception: ${e.message}")
        }
    }

    private fun addDiscoveredReceiver(receiver: DiscoveredReceiver) {
        val current = _discoveredReceivers.value.toMutableList()
        val index = current.indexOfFirst { it.host == receiver.host && it.port == receiver.port }
        if (index != -1) {
            current[index] = receiver
        } else {
            current.add(receiver)
        }
        _discoveredReceivers.value = current
    }

    fun stopDiscovery() {
        if (!_isScanning.value) return
        _isScanning.value = false

        scanJob?.cancel()
        scanJob = null

        discoveryListener?.let {
            try {
                nsdManager?.stopServiceDiscovery(it)
            } catch (_: Exception) {}
        }
        discoveryListener = null

        releaseMulticastLock()
        Log.d(TAG, "Receiver discovery stopped cleanly")
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("C3MusicDiscoveryLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            multicastLock?.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire multicast lock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release multicast lock: ${e.message}")
        }
    }
}
