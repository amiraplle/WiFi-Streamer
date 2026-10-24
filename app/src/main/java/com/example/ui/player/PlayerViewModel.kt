package com.example.ui.player

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.model.AudioTrack
import com.example.model.PlayerUiState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

import android.content.Intent

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val playerManager = AppContainer.getPlayerManager(application)
    val uiState: StateFlow<PlayerUiState> = playerManager.uiState

    fun playTrack(index: Int) {
        playerManager.playTrackAtIndex(index)
    }

    fun togglePlayPause() {
        playerManager.togglePlayPause()
    }

    fun playNext() {
        playerManager.playNext()
    }

    fun playPrevious() {
        playerManager.playPrevious()
    }

    fun seekTo(positionMs: Long) {
        playerManager.seekTo(positionMs)
    }

    fun toggleShuffle() {
        playerManager.toggleShuffle()
    }

    fun toggleRepeat() {
        playerManager.toggleRepeat()
    }

    fun addPickedFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val contentResolver = getApplication<Application>().contentResolver
            val tracks = uris.map { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Throwable) {}
                val fileName = uri.lastPathSegment ?: "Audio Track"
                playerManager.extractTrackMetadata(uri, fileName)
            }
            playerManager.addTracks(tracks, playImmediately = false)
        }
    }

    fun loadFolder(folderUri: Uri, onComplete: (Int) -> Unit) {
        playerManager.loadFromFolder(folderUri, onComplete)
    }

    fun clearQueue() {
        playerManager.clearQueue()
    }

    val availableFormats = listOf(
        com.example.model.AudioStreamFormat.FORMAT_44K_16BIT_STEREO,
        com.example.model.AudioStreamFormat.FORMAT_48K_16BIT_STEREO,
        com.example.model.AudioStreamFormat.FORMAT_44K_24BIT_STEREO,
        com.example.model.AudioStreamFormat.FORMAT_48K_24BIT_STEREO
    )

    fun updateAudioFormat(format: com.example.model.AudioStreamFormat) {
        playerManager.updateTargetFormat(format)
    }

    fun startTransmission() {
        if (uiState.value.playerState == com.example.model.PlayerState.PAUSED) {
            playerManager.togglePlayPause()
        } else if (uiState.value.playlist.isNotEmpty()) {
            val idx = if (uiState.value.currentIndex in uiState.value.playlist.indices) uiState.value.currentIndex else 0
            playerManager.playTrackAtIndex(idx)
        } else {
            playerManager.togglePlayPause()
        }
    }

    fun stopTransmission() {
        playerManager.stopPlayback()
    }

    fun stopPlayback() {
        playerManager.stopPlayback()
    }
}
