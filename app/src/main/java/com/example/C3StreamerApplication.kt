package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.room.Room
import com.example.data.database.AppDatabase
import com.example.data.preferences.UserPreferencesRepository

object AppContainer {
    @Volatile
    private var databaseInstance: AppDatabase? = null

    @Volatile
    private var preferencesInstance: UserPreferencesRepository? = null

    fun getDatabase(context: Context): AppDatabase {
        return databaseInstance ?: synchronized(this) {
            databaseInstance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "c3_streamer_database"
            ).fallbackToDestructiveMigration().build().also { databaseInstance = it }
        }
    }

    fun getPreferences(context: Context): UserPreferencesRepository {
        return preferencesInstance ?: synchronized(this) {
            preferencesInstance ?: UserPreferencesRepository(context.applicationContext).also {
                preferencesInstance = it
            }
        }
    }
}

class C3StreamerApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("C3StreamerApplication", "FATAL CRASH on thread ${thread.name}: ${throwable.message}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        try {
            // Warm up container
            AppContainer.getDatabase(this)
            AppContainer.getPreferences(this)
        } catch (e: Exception) {
            android.util.Log.e("C3StreamerApplication", "AppContainer warmup error: ${e.message}", e)
        }

        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "c3_streaming_channel"
        var instance: C3StreamerApplication? = null
            private set
    }
}
