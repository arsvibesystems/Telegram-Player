package com.telegramplayer.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Repository for fetching movie posters from TMDB (The Movie Database).
 * Parses filenames to extract movie title + year, then queries TMDB search API.
 * 
 * Caching strategy:
 * 1. In-memory LRU cache (100 entries) for instant access
 * 2. SharedPreferences disk cache for offline/restart persistence
 * 3. Network call to TMDB API as last resort
 * 
 * "NO_POSTER" sentinel value is cached to avoid repeated API calls for non-movie files.
 */
class TmdbRepository private constructor(context: Context) {

    companion object {
        private const val TAG = "TmdbRepository"
        private const val TMDB_BASE_URL = "https://api.themoviedb.org/3"
        private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p"
        private const val POSTER_SIZE = "w500" // Good quality for TV cards
        private const val PREFS_NAME = "tmdb_poster_cache_v2"
        private const val NO_POSTER = "NO_POSTER" // Sentinel for "we tried, nothing found"
        private const val CACHE_MAX_SIZE = 100
        private const val API_TIMEOUT_MS = 8000

        @Volatile
        private var instance: TmdbRepository? = null

        fun getInstance(context: Context): TmdbRepository {
            return instance ?: synchronized(this) {
                instance ?: TmdbRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val apiKey = com.telegramplayer.BuildConfig.TMDB_API_KEY
    private val memoryCache = LruCache<String, String>(CACHE_MAX_SIZE)
    private val diskCache: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Check if a file looks like a movie/TV show based on filename, duration, and size.
     * Skips short clips, phone recordings, screen captures, etc.
     *
     * @param fileName The video file name
     * @param durationSeconds Video duration in seconds (0 if unknown)
     * @param fileSizeBytes File size in bytes
     * @return true if this looks like a movie worth querying TMDB for
     */
    fun looksLikeMovie(fileName: String, durationSeconds: Int = 0, fileSizeBytes: Long = 0): Boolean {
        // If duration is known, use it: >= 20 mins is a movie
        if (durationSeconds > 0) {
            return durationSeconds >= 1200
        }
        
        // If duration is unknown, fall back to file size: >= 200 MB is a movie
        return fileSizeBytes >= 209_715_200
    }

    /**
     * Search for a movie poster URL given a video filename.
     * Returns a TmdbResult containing poster URL, or null.
     *
     * Call [looksLikeMovie] first to avoid unnecessary API calls for non-movie files.
     * This is safe to call from any coroutine — it handles IO dispatching internally.
     */
    suspend fun searchMoviePoster(fileName: String): TmdbResult? = withContext(Dispatchers.IO) {
        try {
            val cacheKey = fileName.lowercase().trim()

            // 1. Check memory cache
            memoryCache.get(cacheKey)?.let { cached ->
                return@withContext parseCachedResult(cached)
            }

            // 2. Check disk cache
            diskCache.getString(cacheKey, null)?.let { cached ->
                memoryCache.put(cacheKey, cached) // Promote to memory
                return@withContext parseCachedResult(cached)
            }

            // 3. Parse filename and query TMDB
            val parsed = parseFileName(fileName)
            if (parsed.title.isBlank()) {
                cachePoster(cacheKey, NO_POSTER)
                return@withContext null
            }

            // Try with year first (more accurate)
            var result = queryTmdb(parsed.title, parsed.year)

            // Fallback: try without year
            if (result == null && parsed.year != null) {
                result = queryTmdb(parsed.title, null)
            }

            // Fallback: try shorter title (first 3 words)
            if (result == null) {
                val shortTitle = parsed.title.split(" ").take(3).joinToString(" ")
                if (shortTitle != parsed.title && shortTitle.length >= 3) {
                    result = queryTmdb(shortTitle, parsed.year)
                }
            }

            // Cache the result (including "not found")
            val cacheStr = if (result != null) result.posterUrl else NO_POSTER
            cachePoster(cacheKey, cacheStr)

            result
        } catch (e: Exception) {
            Log.e(TAG, "Error searching poster for: $fileName", e)
            null
        }
    }

    private fun parseCachedResult(cached: String): TmdbResult? {
        if (cached == NO_POSTER) return null
        val parts = cached.split("|")
        val url = parts[0]
        return TmdbResult(url)
    }

    /**
     * Parse a video filename into a clean movie title and optional year.
     * 
     * Handles common patterns:
     * - "The.Batman.2022.1080p.BluRay.x264-GROUP.mkv" → ("The Batman", 2022)
     * - "Inception (2010) [1080p].mkv" → ("Inception", 2010)
     * - "Movie Name S01E05 720p.mp4" → ("Movie Name", null) — TV show detection
     * - "random_file_name.avi" → ("random file name", null)
     */
    private fun parseFileName(fileName: String): ParsedFileName {
        // Remove file extension
        var name = fileName.substringBeforeLast('.')

        // Remove common group tags in brackets/parentheses at the end
        name = name.replace(Regex("\\[.*?\\]"), " ")
        name = name.replace(Regex("\\((?!\\d{4}).*?\\)"), " ") // Remove non-year parentheses

        // Extract year (4-digit number between 1920-2030)
        val yearRegex = Regex("(?:^|[.\\s_\\-(])((?:19|20)\\d{2})(?:[.\\s_\\-)\\[]|$)")
        val yearMatch = yearRegex.find(name)
        val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()

        // Take everything before the year as the title
        val titlePart = if (yearMatch != null) {
            name.substring(0, yearMatch.range.first)
        } else {
            // No year found — try to cut at common quality/encoding tags
            val cutPatterns = listOf(
                "1080p", "720p", "480p", "2160p", "4k",
                "bluray", "bdrip", "brrip", "webrip", "web-dl", "webdl",
                "hdtv", "dvdrip", "hdrip", "camrip", "hdcam",
                "x264", "x265", "h264", "h265", "hevc", "aac", "dts",
                "remux", "proper", "repack", "extended", "unrated",
                "s0", "s1", "s2" // TV show season indicators
            )
            var cutIndex = name.length
            for (pattern in cutPatterns) {
                val idx = name.lowercase().indexOf(pattern)
                if (idx > 0 && idx < cutIndex) {
                    cutIndex = idx
                }
            }
            name.substring(0, cutIndex)
        }

        // Clean up: replace dots, underscores, hyphens with spaces
        val cleanTitle = titlePart
            .replace('.', ' ')
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        return ParsedFileName(cleanTitle, year)
    }

    /**
     * Query TMDB search/movie endpoint.
     * Returns the TmdbResult for the first result, or null.
     */
    private fun queryTmdb(title: String, year: Int?): TmdbResult? {
        try {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            var url = "$TMDB_BASE_URL/search/movie?api_key=$apiKey&query=$encodedTitle&language=en-US&page=1"
            if (year != null) {
                url += "&year=$year"
            }

            Log.d(TAG, "TMDB query: title=\"$title\", year=$year")

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = API_TIMEOUT_MS
            connection.readTimeout = API_TIMEOUT_MS

            try {
                if (connection.responseCode != 200) {
                    Log.w(TAG, "TMDB API returned ${connection.responseCode}")
                    return null
                }

                val responseBody = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(responseBody)
                val results = json.optJSONArray("results")

                if (results == null || results.length() == 0) {
                    Log.d(TAG, "No TMDB results for: $title")
                    return null
                }

                // Take the first result's poster
                val firstResult = results.getJSONObject(0)
                val posterPath = firstResult.optString("poster_path", "")

                if (posterPath.isEmpty() || posterPath == "null") {
                    Log.d(TAG, "No poster_path for: $title")
                    return null
                }

                val posterUrl = "$TMDB_IMAGE_BASE/$POSTER_SIZE$posterPath"
                Log.d(TAG, "Found poster: $posterUrl for \"$title\"")
                return TmdbResult(posterUrl)

            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "TMDB API error for: $title", e)
            return null
        }
    }

    private fun cachePoster(key: String, value: String) {
        memoryCache.put(key, value)
        diskCache.edit().putString(key, value).apply()
    }

    /**
     * Clear all cached poster data (useful for debugging / cache refresh).
     */
    fun clearCache() {
        memoryCache.evictAll()
        diskCache.edit().clear().apply()
    }

    private data class ParsedFileName(
        val title: String,
        val year: Int?
    )
}

data class TmdbResult(
    val posterUrl: String
)
