package com.example.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.example.data.network.NetworkUtils
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

        scanJob = scope.launch {
            // 1. Proactively probe default mDNS hostnames c3music.local & s3music.local
            launch { probeDirectMdns("c3music.local", "ESP32-C3") }
            launch { probeDirectMdns("s3music.local", "ESP32-S3") }

            // 2. Start Android NSD discovery for mDNS advertisements
            startNsdDiscovery()

            // 3. Actively scan the local /24 subnet for any ESP32 listening on port 50005
            launch { scanSubnetForC3Receivers() }

            delay(timeoutMs)
            stopDiscovery()
        }
    }

    private suspend fun probeDirectMdns(hostname: String, deviceLabel: String) = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()
            val address = InetAddress.getByName(hostname)
            val ip = address.hostAddress ?: hostname

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

            val receiver = DiscoveredReceiver(
                name = if (reachable) "$deviceLabel (Online)" else "$hostname (Resolved)",
                host = ip,
                port = 50005,
                isC3Default = true,
                pingMs = pingTime
            )

            addDiscoveredReceiver(receiver)
        } catch (e: Exception) {
            Log.d(TAG, "$hostname not resolved via mDNS: ${e.message}")
        }
    }

    /**
     * Scans the local Wi-Fi /24 subnet (e.g. 192.168.1.1 to 192.168.1.254) on port 50005.
     * Guarantees finding the ESP32-C3 even if the router drops mDNS / multicast packets.
     */
    private suspend fun scanSubnetForC3Receivers() = withContext(Dispatchers.IO) {
        val localIp = NetworkUtils.getLocalIpAddress(context) ?: return@withContext
        val lastDotIndex = localIp.lastIndexOf('.')
        if (lastDotIndex <= 0) return@withContext
        val prefix = localIp.substring(0, lastDotIndex + 1)

        // Scan in batches of 32 concurrent probes to be light on the router
        (1..254).chunked(32).forEach { batch ->
            val jobs = batch.map { hostNum ->
                val targetIp = "$prefix$hostNum"
                if (targetIp == localIp) return@map null
                launch {
                    try {
                        val startTime = System.currentTimeMillis()
                        val socket = Socket()
                        socket.connect(InetSocketAddress(targetIp, 50005), 400)
                        val pingMs = System.currentTimeMillis() - startTime
                        socket.close()

                        val discovered = DiscoveredReceiver(
                            name = "ESP32-C3 Receiver ($targetIp)",
                            host = targetIp,
                            port = 50005,
                            isC3Default = true,
                            pingMs = pingMs
                        )
                        addDiscoveredReceiver(discovered)
                        Log.i(TAG, "Found active C3 receiver on subnet: $targetIp:50005 in ${pingMs}ms")
                    } catch (_: Exception) {}
                }
            }
            jobs.filterNotNull().forEach { it.join() }
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
