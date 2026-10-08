package com.example.ui.receivers

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.data.database.ReceiverEntity
import com.example.data.database.ReceiverRepository
import com.example.data.discovery.DiscoveredReceiver
import com.example.data.discovery.ReceiverDiscoveryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

class ReceiversViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppContainer.getDatabase(application)
    private val repository = ReceiverRepository(db.receiverDao())
    private val discoveryManager = ReceiverDiscoveryManager(application.applicationContext)
    private val prefsRepo = AppContainer.getPreferences(application)

    val savedReceivers: StateFlow<List<ReceiverEntity>> = repository.allReceivers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val discoveredReceivers: StateFlow<List<DiscoveredReceiver>> = discoveryManager.discoveredReceivers
    val isScanning: StateFlow<Boolean> = discoveryManager.isScanning

    private val _pingResult = MutableStateFlow<String?>(null)
    val pingResult: StateFlow<String?> = _pingResult.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getDefaultReceiver() // Ensures default C3 is in database
        }
    }

    fun startScan() {
        discoveryManager.startDiscovery()
    }

    fun stopScan() {
        discoveryManager.stopDiscovery()
    }

    fun selectReceiver(host: String, port: Int) {
        prefsRepo.updateTarget(host, port)
    }

    fun addReceiver(name: String, host: String, tcpPort: Int, httpPort: Int, isDefault: Boolean) {
        viewModelScope.launch {
            val entity = ReceiverEntity(
                name = name.ifBlank { host },
                host = host.trim(),
                tcpPort = tcpPort,
                httpPort = httpPort,
                isDefault = isDefault
            )
            repository.addReceiver(entity)
            if (isDefault) {
                prefsRepo.updateTarget(host, tcpPort)
            }
        }
    }

    fun deleteReceiver(receiver: ReceiverEntity) {
        viewModelScope.launch {
            repository.deleteReceiver(receiver)
        }
    }

    fun setDefaultReceiver(receiver: ReceiverEntity) {
        viewModelScope.launch {
            repository.setDefault(receiver.id)
            prefsRepo.updateTarget(receiver.host, receiver.tcpPort)
        }
    }

    fun testConnection(host: String, port: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            _pingResult.value = "Testing connection to $host:$port…"
            val start = System.currentTimeMillis()
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 2500)
                val latency = System.currentTimeMillis() - start
                socket.close()
                _pingResult.value = "Connected successfully! Latency: ${latency}ms"
            } catch (e: Exception) {
                _pingResult.value = "Connection failed: ${e.message}"
            }
        }
    }

    override fun onCleared() {
        discoveryManager.stopDiscovery()
        super.onCleared()
    }
}
