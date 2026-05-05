package com.telegramplayer.streaming

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.telegramplayer.TelegramPlayerApp
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Local HTTP streaming proxy server using NanoHTTPD.
 * Handles Range Requests for seeking in MX Player.
 * Uses Long offsets to support files up to 4GB.
 */
class StreamingProxyServer(port: Int = 0) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "StreamingProxy"
        private const val CHUNK_SIZE = 2L * 1024 * 1024 // 2MB chunks for responsive streaming
        private const val CLEANUP_DELAY_MS = 60_000L // 60 seconds after last access

        @Volatile
        private var instance: StreamingProxyServer? = null

        fun getInstance(): StreamingProxyServer {
            return instance ?: synchronized(this) {
                instance ?: StreamingProxyServer().also { instance = it }
            }
        }
    }

    // Cache file sizes to avoid repeated lookups
    private val fileSizeCache = ConcurrentHashMap<Int, Long>()
    private val fileNameCache = ConcurrentHashMap<Int, String>()
    // Cache remote file IDs for resolving newly discovered files
    private val remoteFileIdCache = ConcurrentHashMap<Int, String>()
    
    // Track last access time and cleanup runnables for each file
    private val lastAccessTime = ConcurrentHashMap<Int, Long>()
    private val cleanupRunnables = ConcurrentHashMap<Int, Runnable>()
    private val handler = Handler(Looper.getMainLooper())

    fun registerFile(fileId: Int, fileName: String, fileSize: Long, remoteFileId: String = "") {
        fileSizeCache[fileId] = fileSize
        fileNameCache[fileId] = fileName
        if (remoteFileId.isNotEmpty()) {
            remoteFileIdCache[fileId] = remoteFileId
        }
    }

    fun getStreamUrl(fileId: Int): String {
        if (!this.isAlive) {
            try {
                this.start(SOCKET_READ_TIMEOUT, false)
                Log.d(TAG, "Auto-started proxy server on port $listeningPort")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to auto-start proxy server", e)
            }
        }
        return "http://127.0.0.1:$listeningPort/stream/$fileId/video.mp4"
    }
    
    /**
     * Schedule cleanup for a file after CLEANUP_DELAY_MS of inactivity.
     * Cancels any existing cleanup runnable and schedules a new one.
     */
    private fun scheduleCleanup(fileId: Int) {
        // Cancel existing cleanup
        cleanupRunnables[fileId]?.let { handler.removeCallbacks(it) }
        
        // Schedule new cleanup
        val runnable = Runnable {
            val lastAccess = lastAccessTime[fileId] ?: 0L
            val elapsed = System.currentTimeMillis() - lastAccess
            
            if (elapsed >= CLEANUP_DELAY_MS) {
                Log.d(TAG, "Cleaning up cached file: $fileId (inactive for ${elapsed/1000}s)")
                TelegramPlayerApp.getTdClient().deleteFile(fileId)
                
                // Clear local caches
                fileSizeCache.remove(fileId)
                fileNameCache.remove(fileId)
                remoteFileIdCache.remove(fileId)
                lastAccessTime.remove(fileId)
                cleanupRunnables.remove(fileId)
            }
        }
        
        cleanupRunnables[fileId] = runnable
        handler.postDelayed(runnable, CLEANUP_DELAY_MS)
    }
    
    /**
     * Mark a file as accessed, refreshing the cleanup timer.
     */
    private fun markAccessed(fileId: Int) {
        lastAccessTime[fileId] = System.currentTimeMillis()
        scheduleCleanup(fileId)
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "Request: ${session.method} $uri")

        if (!uri.startsWith("/stream/")) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }

        val fileIdStr = uri.removePrefix("/stream/").split("/").firstOrNull()
        val fileId = fileIdStr?.toIntOrNull()

        if (fileId == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid file ID")
        }

        // Get file size
        val fileSize = fileSizeCache[fileId] ?: runBlocking {
            TelegramPlayerApp.getTdClient().getFileSize(fileId).also {
                fileSizeCache[fileId] = it
            }
        }

        if (fileSize <= 0) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        }
        
        // Track file access for cleanup scheduling
        markAccessed(fileId)

        val fileName = fileNameCache[fileId] ?: "video.mp4"
        val mimeType = getMimeType(fileName)

        // Parse Range header
        val rangeHeader = session.headers["range"]
        Log.d(TAG, "Range header: $rangeHeader, File size: $fileSize")

        return if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            handleRangeRequest(fileId, fileSize, rangeHeader, mimeType)
        } else {
            handleFullRequest(fileId, fileSize, mimeType)
        }
    }

    private fun handleRangeRequest(
        fileId: Int,
        fileSize: Long,
        rangeHeader: String,
        mimeType: String
    ): Response {
        // Parse range: "bytes=start-end" or "bytes=start-"
        val rangeValue = rangeHeader.removePrefix("bytes=")
        val parts = rangeValue.split("-")

        val startByte = parts[0].toLongOrNull() ?: 0L
        val endByte = if (parts.size > 1 && parts[1].isNotEmpty()) {
            parts[1].toLongOrNull() ?: (fileSize - 1)
        } else {
            // If no end specified, return a chunk
            (startByte + CHUNK_SIZE - 1).coerceAtMost(fileSize - 1)
        }

        // Validate range
        if (startByte >= fileSize || endByte >= fileSize || startByte > endByte) {
            val response = newFixedLengthResponse(
                Response.Status.RANGE_NOT_SATISFIABLE,
                "text/plain",
                "Range not satisfiable"
            )
            response.addHeader("Content-Range", "bytes */$fileSize")
            return response
        }

        val contentLength = endByte - startByte + 1
        Log.d(TAG, "Serving range: $startByte-$endByte/$fileSize (${contentLength} bytes)")

        // Fetch the data chunk; if it fails, attempt to resolve via remoteFileId
        var data = runBlocking {
            TelegramPlayerApp.getTdClient().readFilePart(
                fileId,
                startByte,
                contentLength.coerceAtMost(CHUNK_SIZE)
            )
        }

        // Fallback: newly added files may have an unregistered local fileId.
        // Use remoteFileId to force-register the file with TDLib, then retry.
        if ((data == null || data.isEmpty()) && remoteFileIdCache.containsKey(fileId)) {
            val remoteId = remoteFileIdCache[fileId]!!
            Log.w(TAG, "readFilePart returned null for fileId=$fileId, trying getRemoteFile($remoteId)")
            val resolvedId = runBlocking {
                TelegramPlayerApp.getTdClient().getFileByRemoteId(remoteId)
            }
            if (resolvedId != null && resolvedId != fileId) {
                Log.d(TAG, "Resolved new fileId=$resolvedId, retrying readFilePart")
                data = runBlocking {
                    TelegramPlayerApp.getTdClient().readFilePart(
                        resolvedId,
                        startByte,
                        contentLength.coerceAtMost(CHUNK_SIZE)
                    )
                }
            }
        }

        if (data == null || data.isEmpty()) {
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Failed to read file data"
            )
        }

        val actualEnd = startByte + data.size - 1
        val response = newFixedLengthResponse(
            Response.Status.PARTIAL_CONTENT,
            mimeType,
            ByteArrayInputStream(data),
            data.size.toLong()
        )

        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Range", "bytes $startByte-$actualEnd/$fileSize")
        response.addHeader("Content-Length", data.size.toString())

        return response
    }

    private fun handleFullRequest(fileId: Int, fileSize: Long, mimeType: String): Response {
        Log.d(TAG, "Serving full file (first chunk): fileId=$fileId, size=$fileSize")

        // For full request, return first chunk; fallback to remoteFileId resolution if needed
        var data = runBlocking {
            TelegramPlayerApp.getTdClient().readFilePart(fileId, 0, CHUNK_SIZE)
        }

        if ((data == null || data.isEmpty()) && remoteFileIdCache.containsKey(fileId)) {
            val remoteId = remoteFileIdCache[fileId]!!
            Log.w(TAG, "Full request readFilePart null for fileId=$fileId, trying getRemoteFile($remoteId)")
            val resolvedId = runBlocking {
                TelegramPlayerApp.getTdClient().getFileByRemoteId(remoteId)
            }
            if (resolvedId != null && resolvedId != fileId) {
                data = runBlocking {
                    TelegramPlayerApp.getTdClient().readFilePart(resolvedId, 0, CHUNK_SIZE)
                }
            }
        }

        if (data == null || data.isEmpty()) {
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Failed to read file data"
            )
        }

        // Return 200 with Accept-Ranges header so player knows to use range requests
        val response = newFixedLengthResponse(
            Response.Status.OK,
            mimeType,
            ByteArrayInputStream(data),
            data.size.toLong()
        )

        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Length", fileSize.toString())

        return response
    }

    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "m4v" -> "video/x-m4v"
            "3gp" -> "video/3gpp"
            "ts" -> "video/mp2t"
            "flv" -> "video/x-flv"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
    }
}
