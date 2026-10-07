package com.example.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.example.MainActivity
import com.example.model.StreamingState

@RequiresApi(Build.VERSION_CODES.N)
class StreamingTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val service = StreamingService.instance
        if (service != null && service.telemetry.value.streamingState == StreamingState.STREAMING) {
            val stopIntent = Intent(this, StreamingService::class.java).apply {
                action = StreamingService.ACTION_STOP
            }
            startService(stopIntent)
        } else {
            // Bring up app to authorize MediaProjection or start stream
            val appIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivityAndCollapse(appIntent)
        }
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val service = StreamingService.instance
        val isStreaming = service != null && service.telemetry.value.streamingState == StreamingState.STREAMING

        tile.state = if (isStreaming) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (isStreaming) "Audio Streaming" else "Audio Studio"
        tile.subtitle = if (isStreaming) service?.telemetry?.value?.targetHost else "Tap to open"
        tile.updateTile()
    }
}
