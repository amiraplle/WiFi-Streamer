package com.example.ui.browser

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class YouTubeTrack(
    val id: String,
    val title: String,
    val artist: String,
    val durationText: String,
    val thumbnailUrl: String,
    val streamUrl: String? = null
)

/**
 * High-reliability YouTube / YouTube Music stream resolver and search client.
 * Connects to federated open instances (Piped & Invidious) to extract direct audio stream URLs
 * (M4A/AAC/Opus) playable directly in ExoPlayer without ads, video decoding CPU overhead, or WebView throttling.
 */
object YouTubeStreamResolver {
    private const val TAG = "YouTubeStreamResolver"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val PIPED_INSTANCES = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.tokhmi.xyz",
        "https://api.piped.private.coffee",
        "https://piped-api.garudalinux.org",
        "https://pipedapi.colinslegacy.com",
        "https://pipedapi.r4fo.com"
    )

    private val INVIDIOUS_INSTANCES = listOf(
        "https://inv.nadeko.net",
        "https://invidious.nerdvpn.de",
        "https://vid.puffyan.us",
        "https://invidious.jing.rocks",
        "https://yewtu.be"
    )

    /**
     * Extracts YouTube video ID from standard YouTube or YouTube Music URLs.
     */
    fun extractVideoId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.length == 11 && !trimmed.contains(" ") && !trimmed.contains("/")) {
            return trimmed
        }

        val patterns = listOf(
            Pattern.compile("(?:v=|/v/|youtu\\.be/|/embed/|/shorts/|watch\\?v=)([a-zA-Z0-9_-]{11})"),
            Pattern.compile("music\\.youtube\\.com/watch\\?v=([a-zA-Z0-9_-]{11})"),
            Pattern.compile("youtube\\.com/watch\\?v=([a-zA-Z0-9_-]{11})")
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(trimmed)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }

    /**
     * Searches YouTube Music / YouTube tracks.
     */
    suspend fun searchTracks(query: String): List<YouTubeTrack> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        // 1. Try Piped instances first with music filter
        for (instance in PIPED_INSTANCES) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = "$instance/search?q=$encoded&filter=music_songs"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use
                        val json = JSONObject(body)
                        val items = json.optJSONArray("items") ?: JSONArray()
                        val results = mutableListOf<YouTubeTrack>()
                        for (i in 0 until items.length().coerceAtMost(25)) {
                            val item = items.getJSONObject(i)
                            val itemUrl = item.optString("url", "")
                            val videoId = extractVideoId(itemUrl) ?: item.optString("id", "")
                            if (videoId.length == 11) {
                                val title = item.optString("title", "Unknown Title")
                                val uploaderName = item.optString("uploaderName", "Artist")
                                val durationSeconds = item.optLong("duration", 0L)
                                val durationText = if (durationSeconds > 0) {
                                    val m = durationSeconds / 60
                                    val s = durationSeconds % 60
                                    String.format("%d:%02d", m, s)
                                } else {
                                    ""
                                }
                                val thumbnail = item.optString("thumbnail", "")
                                results.add(
                                    YouTubeTrack(
                                        id = videoId,
                                        title = title,
                                        artist = uploaderName,
                                        durationText = durationText,
                                        thumbnailUrl = thumbnail
                                    )
                                )
                            }
                        }
                        if (results.isNotEmpty()) {
                            return@withContext results
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Search failed on $instance: ${e.message}")
            }
        }

        // 2. Fallback to Invidious search
        for (instance in INVIDIOUS_INSTANCES) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = "$instance/api/v1/search?q=$encoded&type=video"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use
                        val items = JSONArray(body)
                        val results = mutableListOf<YouTubeTrack>()
                        for (i in 0 until items.length().coerceAtMost(25)) {
                            val item = items.getJSONObject(i)
                            val videoId = item.optString("videoId", "")
                            if (videoId.length == 11) {
                                val title = item.optString("title", "Unknown Title")
                                val author = item.optString("author", "Artist")
                                val lengthSeconds = item.optLong("lengthSeconds", 0L)
                                val durationText = if (lengthSeconds > 0) {
                                    val m = lengthSeconds / 60
                                    val s = lengthSeconds % 60
                                    String.format("%d:%02d", m, s)
                                } else {
                                    ""
                                }
                                val thumbs = item.optJSONArray("videoThumbnails")
                                val thumbnail = if (thumbs != null && thumbs.length() > 0) {
                                    thumbs.getJSONObject(0).optString("url", "")
                                } else {
                                    "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                                }

                                results.add(
                                    YouTubeTrack(
                                        id = videoId,
                                        title = title,
                                        artist = author,
                                        durationText = durationText,
                                        thumbnailUrl = thumbnail
                                    )
                                )
                            }
                        }
                        if (results.isNotEmpty()) {
                            return@withContext results
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Invidious search failed on $instance: ${e.message}")
            }
        }

        emptyList()
    }

    /**
     * Resolves direct playable ExoPlayer audio stream URL for a given YouTube video ID.
     */
    suspend fun resolveAudioStream(videoId: String): Pair<String, YouTubeTrack>? = withContext(Dispatchers.IO) {
        // Try Piped instances first
        for (instance in PIPED_INSTANCES) {
            try {
                val url = "$instance/streams/$videoId"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use
                        val json = JSONObject(body)
                        val title = json.optString("title", "YouTube Track")
                        val uploader = json.optString("uploader", "YouTube Music")
                        val thumbnail = json.optString("thumbnailUrl", "https://i.ytimg.com/vi/$videoId/hqdefault.jpg")

                        val audioStreams = json.optJSONArray("audioStreams")
                        if (audioStreams != null && audioStreams.length() > 0) {
                            var bestUrl: String? = null
                            var bestBitrate = 0L

                            for (i in 0 until audioStreams.length()) {
                                val s = audioStreams.getJSONObject(i)
                                val streamUrl = s.optString("url", "")
                                val bitrate = s.optLong("bitrate", 0L)
                                val mimeType = s.optString("mimeType", "")
                                // Prefer mp4 / m4a or high-quality webm/opus
                                if (streamUrl.isNotBlank() && (bitrate > bestBitrate || bestUrl == null)) {
                                    bestBitrate = bitrate
                                    bestUrl = streamUrl
                                }
                            }

                            if (!bestUrl.isNullOrBlank()) {
                                val track = YouTubeTrack(
                                    id = videoId,
                                    title = title,
                                    artist = uploader,
                                    durationText = "",
                                    thumbnailUrl = thumbnail,
                                    streamUrl = bestUrl
                                )
                                return@withContext Pair(bestUrl, track)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Piped stream resolve failed on $instance: ${e.message}")
            }
        }

        // Try Invidious instances
        for (instance in INVIDIOUS_INSTANCES) {
            try {
                val url = "$instance/api/v1/videos/$videoId"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use
                        val json = JSONObject(body)
                        val title = json.optString("title", "YouTube Track")
                        val author = json.optString("author", "YouTube Music")
                        val adaptive = json.optJSONArray("adaptiveFormats")
                        if (adaptive != null && adaptive.length() > 0) {
                            var bestUrl: String? = null
                            var bestBitrate = 0L
                            for (i in 0 until adaptive.length()) {
                                val fmt = adaptive.getJSONObject(i)
                                val streamType = fmt.optString("type", "")
                                if (streamType.startsWith("audio/")) {
                                    val streamUrl = fmt.optString("url", "")
                                    val bitrate = fmt.optLong("bitrate", 0L)
                                    if (streamUrl.isNotBlank() && (bitrate > bestBitrate || bestUrl == null)) {
                                        bestBitrate = bitrate
                                        bestUrl = streamUrl
                                    }
                                }
                            }

                            if (!bestUrl.isNullOrBlank()) {
                                val track = YouTubeTrack(
                                    id = videoId,
                                    title = title,
                                    artist = author,
                                    durationText = "",
                                    thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                                    streamUrl = bestUrl
                                )
                                return@withContext Pair(bestUrl, track)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Invidious stream resolve failed on $instance: ${e.message}")
            }
        }

        null
    }
}
