package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "receivers")
data class ReceiverEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val host: String,
    val tcpPort: Int = 50005,
    val httpPort: Int = 8080,
    val isDefault: Boolean = false,
    val lastConnected: Long = 0L,
    val notes: String = ""
)
