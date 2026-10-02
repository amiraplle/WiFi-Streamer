package com.example.data.database

import kotlinx.coroutines.flow.Flow

class ReceiverRepository(private val receiverDao: ReceiverDao) {

    val allReceivers: Flow<List<ReceiverEntity>> = receiverDao.getAllReceivers()

    suspend fun getDefaultReceiver(): ReceiverEntity? {
        val def = receiverDao.getDefaultReceiver()
        if (def != null) return def
        // If empty, initialize with default C3 receiver
        val defaultC3 = ReceiverEntity(
            name = "ESP32-C3 Music Receiver",
            host = "c3music.local",
            tcpPort = 50005,
            httpPort = 8080,
            isDefault = true,
            notes = "Standard mDNS hostname for C3_Music receiver"
        )
        val id = receiverDao.insertReceiver(defaultC3)
        return defaultC3.copy(id = id)
    }

    suspend fun addReceiver(receiver: ReceiverEntity): Long {
        if (receiver.isDefault) {
            receiverDao.clearDefaultFlags()
        }
        return receiverDao.insertReceiver(receiver)
    }

    suspend fun updateReceiver(receiver: ReceiverEntity) {
        if (receiver.isDefault) {
            receiverDao.clearDefaultFlags()
        }
        receiverDao.updateReceiver(receiver)
    }

    suspend fun deleteReceiver(receiver: ReceiverEntity) {
        receiverDao.deleteReceiver(receiver)
    }

    suspend fun setDefault(id: Long) {
        receiverDao.clearDefaultFlags()
        receiverDao.setDefaultReceiver(id)
    }

    suspend fun markConnected(id: Long) {
        receiverDao.updateLastConnected(id, System.currentTimeMillis())
    }
}
