package com.example.data.database

import kotlinx.coroutines.flow.Flow

class ReceiverRepository(private val receiverDao: ReceiverDao) {

    val allReceivers: Flow<List<ReceiverEntity>> = receiverDao.getAllReceivers()

    suspend fun getDefaultReceiver(): ReceiverEntity? {
        val existingC3 = receiverDao.getReceiverByHost("c3music.local")
        if (existingC3 == null) {
            val defaultC3 = ReceiverEntity(
                name = "ESP32-C3 Music Receiver",
                host = "c3music.local",
                tcpPort = 50005,
                httpPort = 8080,
                isDefault = true,
                notes = "Standard mDNS hostname for ESP32-C3"
            )
            receiverDao.insertReceiver(defaultC3)
        }

        val existingS3 = receiverDao.getReceiverByHost("s3music.local")
        if (existingS3 == null) {
            val defaultS3 = ReceiverEntity(
                name = "ESP32-S3 Music Receiver",
                host = "s3music.local",
                tcpPort = 50005,
                httpPort = 8080,
                isDefault = false,
                notes = "Standard mDNS hostname for ESP32-S3"
            )
            receiverDao.insertReceiver(defaultS3)
        }

        return receiverDao.getDefaultReceiver()
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
