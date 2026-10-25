package com.example.ui.player

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Ultra fast in-memory LRU cache for decoded album artwork ImageBitmaps.
 * Eliminates repeated BitmapFactory decoding on the UI thread during LazyColumn scrolling,
 * ensuring fluid 60/120 FPS performance even for libraries with hundreds of songs.
 */
object ArtworkCache {
    private val memoryCache: LruCache<String, ImageBitmap> = object : LruCache<String, ImageBitmap>(128) {}

    fun getOrDecode(id: String, bytes: ByteArray?): ImageBitmap? {
        if (bytes == null || bytes.isEmpty()) return null
        val cached = memoryCache.get(id)
        if (cached != null) return cached

        return try {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                val imageBitmap = bitmap.asImageBitmap()
                memoryCache.put(id, imageBitmap)
                imageBitmap
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun clear() {
        memoryCache.evictAll()
    }
}
