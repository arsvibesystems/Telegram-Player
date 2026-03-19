package com.telegramplayer.data

import android.content.Context
import android.util.Log
import io.github.up9cloud.td.JsonClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * TDLib client manager using JSON interface.
 * Supports files up to 4GB using Long offsets with ReadFilePart.
 */
class TdClientManager(private val context: Context) {
    companion object {
        private const val TAG = "TdClientManager"
        private const val CHUNK_SIZE = 2L * 1024 * 1024 // 2MB chunks for responsive streaming
        private const val MAX_CACHE_SIZE = 300L * 1024 * 1024 // 300MB max cache
        private const val MAX_FILE_AGE_SECONDS = 7 * 24 * 60 * 60 // 7 days
    }

    private val clientId: Int = JsonClient.td_create_client_id()
    private val requestId = AtomicLong(1)
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isInitialized = false

    // Authentication state flow
    private val _authState = MutableStateFlow<AuthState>(AuthState.Initial)
    val authState: StateFlow<AuthState> = _authState

    // QR code link for authentication
    private val _qrCodeLink = MutableStateFlow<String?>(null)
    val qrCodeLink: StateFlow<String?> = _qrCodeLink

    // API credentials (to be set by user)
    var apiId: Int = 0
    var apiHash: String = ""

    init {
        Log.e(TAG, "TdClientManager created. ClientId: $clientId")
    }

    /**
     * Initialize TDLib and start receiving updates.
     * Must be called AFTER setting apiId and apiHash.
     */
    fun initialize() {
        if (isInitialized) {
            Log.e(TAG, "Already initialized, skipping")
            return
        }
        if (apiId == 0 || apiHash.isEmpty()) {
            Log.e(TAG, "Cannot initialize: apiId or apiHash not set")
            return
        }
        isInitialized = true
        Log.e(TAG, "Initializing TDLib with apiId: $apiId")
        startReceiver()
        // Send a dummy request to trigger the first updateAuthorizationState
        triggerAuthorizationStateUpdate()
    }

    private fun triggerAuthorizationStateUpdate() {
        // TDLib needs a request to start sending updates
        // getOption is a simple request that will trigger the auth flow
        val request = JSONObject().apply {
            put("@type", "getOption")
            put("name", "version")
            put("@extra", getNextRequestId())
        }
        send(request.toString())
    }

    private fun startReceiver() {
        Log.e(TAG, "Starting receiver loop")
        scope.launch {
            while (isActive) {
                try {
                    val result = withContext(Dispatchers.IO) {
                        JsonClient.td_receive(1.0)
                    }
                    if (result != null) {
                        Log.e(TAG, "Received raw json: $result") // Verbose logging
                        handleResponse(result)
                    } else {
                         Log.e(TAG, "Received null response (timeout)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in receiver loop", e)
                }
            }
        }
    }

    private fun handleResponse(jsonString: String) {
        try {
            val json = JSONObject(jsonString)
            val extra = json.optString("@extra", "")
            val type = json.optString("@type", "")

            Log.e(TAG, "Handling event type: $type")

            // Handle pending request responses
            if (extra.isNotEmpty()) {
                val deferred = pendingRequests.remove(extra)
                if (deferred != null) {
                    Log.e(TAG, "Completing request: $extra")
                    deferred.complete(json)
                } else {
                    Log.e(TAG, "No pending request found for extra: $extra")
                }
            }

            // Handle updates
            when (type) {
                "updateAuthorizationState" -> handleAuthorizationState(json)
                "updateFile" -> handleFileUpdate(json)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing response", e)
        }
    }

    private fun handleAuthorizationState(json: JSONObject) {
        val state = json.getJSONObject("authorization_state")
        val stateType = state.getString("@type")

        Log.d(TAG, "Auth state: $stateType")

        when (stateType) {
            "authorizationStateWaitTdlibParameters" -> {
                _authState.value = AuthState.WaitingForParameters
                setTdlibParameters()
            }
            "authorizationStateWaitPhoneNumber" -> {
                _authState.value = AuthState.WaitingForPhoneNumber
            }
            "authorizationStateWaitOtherDeviceConfirmation" -> {
                val link = state.getString("link")
                _qrCodeLink.value = link
                _authState.value = AuthState.WaitingForQrScan
            }
            "authorizationStateWaitCode" -> {
                _authState.value = AuthState.WaitingForCode
            }
            "authorizationStateWaitPassword" -> {
                _authState.value = AuthState.WaitingForPassword
            }
            "authorizationStateReady" -> {
                _authState.value = AuthState.Authorized
                // Clean up old cache files on startup
                optimizeStorage()
            }
            "authorizationStateClosed" -> {
                _authState.value = AuthState.Closed
            }
        }
    }

    private fun setTdlibParameters() {
        val dbDir = File(context.filesDir, "tdlib")
        dbDir.mkdirs()

        val params = JSONObject().apply {
            put("@type", "setTdlibParameters")
            put("database_directory", dbDir.absolutePath)
            put("use_message_database", true)
            put("use_secret_chats", false)
            put("api_id", apiId)
            put("api_hash", apiHash)
            put("system_language_code", "en")
            put("device_model", "Fire TV")
            put("application_version", "1.0.0")
            put("enable_storage_optimizer", true)
            put("ignore_file_names", true)
            put("@extra", getNextRequestId())
        }
        send(params.toString())
    }

    fun requestQrCodeAuth() {
        val request = JSONObject().apply {
            put("@type", "requestQrCodeAuthentication")
            put("other_user_ids", org.json.JSONArray())
            put("@extra", getNextRequestId())
        }
        send(request.toString())
    }

    fun sendPhoneNumber(phoneNumber: String) {
        val request = JSONObject().apply {
            put("@type", "setAuthenticationPhoneNumber")
            put("phone_number", phoneNumber)
            put("@extra", getNextRequestId())
        }
        send(request.toString())
    }

    fun sendCode(code: String) {
        val request = JSONObject().apply {
            put("@type", "checkAuthenticationCode")
            put("code", code)
            put("@extra", getNextRequestId())
        }
        send(request.toString())
    }

    fun sendPassword(password: String) {
        val request = JSONObject().apply {
            put("@type", "checkAuthenticationPassword")
            put("password", password)
            put("@extra", getNextRequestId())
        }
        send(request.toString())
    }

    /**
     * Get list of chats (private, groups, channels)
     */
    suspend fun getChats(limit: Int = 100): List<ChatInfo> {
        val request = JSONObject().apply {
            put("@type", "getChats")
            put("chat_list", JSONObject().put("@type", "chatListMain"))
            put("limit", limit)
        }
        val response = sendAsync(request)

        val chatInfos = mutableListOf<ChatInfo>()
        val chatIds = response.getJSONArray("chat_ids")

        for (i in 0 until chatIds.length()) {
            val chatId = chatIds.getLong(i)
            val chatInfo = getChat(chatId)
            if (chatInfo != null) {
                chatInfos.add(chatInfo)
            }
        }
        return chatInfos
    }

    private suspend fun getChat(chatId: Long): ChatInfo? {
        val request = JSONObject().apply {
            put("@type", "getChat")
            put("chat_id", chatId)
        }
        return try {
            val response = sendAsync(request)
            val type = response.getJSONObject("type")
            val chatType = when (type.getString("@type")) {
                "chatTypePrivate" -> ChatType.PRIVATE
                "chatTypeSupergroup" -> if (type.optBoolean("is_channel")) ChatType.CHANNEL else ChatType.GROUP
                "chatTypeBasicGroup" -> ChatType.GROUP
                else -> ChatType.PRIVATE
            }

            // Parse photo
            var photoPath: String? = null
            val photo = response.optJSONObject("photo")
            if (photo != null) {
                val small = photo.getJSONObject("small")
                val local = small.getJSONObject("local")
                val path = local.optString("path", "")
                if (path.isNotEmpty() && File(path).exists()) {
                    photoPath = path
                } else {
                    // Trigger download if not available
                    downloadThumbnail(small.getInt("id"))
                }
            }

            // Parse last message date (for sorting)
            val lastMessage = response.optJSONObject("last_message")
            val lastMessageDate = lastMessage?.optInt("date", 0) ?: 0
            
            ChatInfo(
                id = response.getLong("id"),
                title = response.getString("title"),
                type = chatType,
                photoPath = photoPath,
                lastMessageDate = lastMessageDate
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting chat $chatId", e)
            null
        }
    }

    private fun downloadThumbnail(fileId: Int) {
        val request = JSONObject().apply {
            put("@type", "downloadFile")
            put("file_id", fileId)
            put("priority", 1)
            put("synchronous", false)
        }
        send(request.toString())
    }

    /**
     * Search for media messages in a chat.
     * Searches for both Videos and Documents (filtered for video types) to support MKV, AVI, etc.
     */
    suspend fun searchChatMedia(chatId: Long, limit: Int = 100): List<MediaItem> {
        return try {
            val videoDeferred = scope.async(Dispatchers.IO) {
                searchMessagesByType(chatId, limit, "searchMessagesFilterVideo")
            }
            val documentDeferred = scope.async(Dispatchers.IO) {
                searchMessagesByType(chatId, limit, "searchMessagesFilterDocument")
            }

            val videoMessages = videoDeferred.await()
            val documentMessages = documentDeferred.await()

            val mediaItems = mutableListOf<MediaItem>()

            // Process Videos
            for (i in 0 until videoMessages.length()) {
                val message = videoMessages.getJSONObject(i)
                parseVideoMessage(message, chatId)?.let { mediaItems.add(it) }
            }

            // Process Documents (filter for video types)
            for (i in 0 until documentMessages.length()) {
                val message = documentMessages.getJSONObject(i)
                parseDocumentMessage(message, chatId)?.let { mediaItems.add(it) }
            }

            // Deduplicate by file ID and sort by date (newest first - implicit from search)
            mediaItems.distinctBy { it.fileId }
                .sortedByDescending { it.messageId }

        } catch (e: Exception) {
            Log.e(TAG, "Error searching media", e)
            emptyList()
        }
    }

    private suspend fun searchMessagesByType(chatId: Long, limit: Int, filterType: String): org.json.JSONArray {
        val request = JSONObject().apply {
            put("@type", "searchChatMessages")
            put("chat_id", chatId)
            put("query", "")
            put("from_message_id", 0)
            put("offset", 0)
            put("limit", limit)
            put("filter", JSONObject().put("@type", filterType))
        }
        val response = sendAsync(request)
        return response.optJSONArray("messages") ?: org.json.JSONArray()
    }

    private suspend fun parseVideoMessage(message: JSONObject, chatId: Long): MediaItem? {
        val content = message.getJSONObject("content")
        if (content.getString("@type") != "messageVideo") return null

        val video = content.getJSONObject("video")
        val file = video.getJSONObject("video")
        val remoteFile = file.getJSONObject("remote")
        val chatTitle = getChat(chatId)?.title ?: "Unknown"

        // Parse Thumbnail
        var thumbnailPath: String? = null
        val thumbnail = video.optJSONObject("thumbnail")
        if (thumbnail != null) {
            val fileLocal = thumbnail.getJSONObject("file").getJSONObject("local")
            val path = fileLocal.optString("path", "")
            if (path.isNotEmpty() && File(path).exists()) {
                thumbnailPath = path
            } else {
                 downloadThumbnail(thumbnail.getJSONObject("file").getInt("id"))
            }
        }

        return MediaItem(
            id = file.getInt("id").toLong(),
            chatId = chatId,
            messageId = message.getLong("id"),
            fileId = file.getInt("id"),
            remoteFileId = remoteFile.getString("id"),
            title = video.optString("file_name", "Video"),
            fileName = video.optString("file_name", "video.mp4"),
            mimeType = video.optString("mime_type", "video/mp4"),
            duration = video.optInt("duration", 0),
            size = file.getLong("expected_size"),
            thumbnailPath = thumbnailPath,
            chatTitle = chatTitle,
            mediaType = MediaType.VIDEO
        )
    }

    private suspend fun parseDocumentMessage(message: JSONObject, chatId: Long): MediaItem? {
        val content = message.getJSONObject("content")
        if (content.getString("@type") != "messageDocument") return null

        val document = content.getJSONObject("document")
        val file = document.getJSONObject("document")
        val fileName = document.optString("file_name", "")
        val mimeType = document.optString("mime_type", "")

        val isVideo = isVideoFile(fileName, mimeType)
        val isSubtitle = isSubtitleFile(fileName)

        // Filter: Must be a video or subtitle file
        if (!isVideo && !isSubtitle) return null

        // Parse Thumbnail
        var thumbnailPath: String? = null
        val thumbnail = document.optJSONObject("thumbnail")
        if (thumbnail != null) {
            val fileLocal = thumbnail.getJSONObject("file").getJSONObject("local")
            val path = fileLocal.optString("path", "")
            if (path.isNotEmpty() && File(path).exists()) {
                thumbnailPath = path
            } else {
                 downloadThumbnail(thumbnail.getJSONObject("file").getInt("id"))
            }
        }

        val remoteFile = file.getJSONObject("remote")
        val chatTitle = getChat(chatId)?.title ?: "Unknown"

        return MediaItem(
            id = file.getInt("id").toLong(),
            chatId = chatId,
            messageId = message.getLong("id"),
            fileId = file.getInt("id"),
            remoteFileId = remoteFile.getString("id"),
            title = fileName.ifEmpty { if (isSubtitle) "Subtitle" else "Video Document" },
            fileName = fileName,
            mimeType = mimeType,
            duration = 0, // Documents don't have duration metadata usually
            size = file.getLong("expected_size"),
            thumbnailPath = thumbnailPath,
            chatTitle = chatTitle,
            mediaType = if (isSubtitle) MediaType.DOCUMENT else MediaType.VIDEO
        )
    }

    private fun isVideoFile(fileName: String, mimeType: String): Boolean {
        if (mimeType.startsWith("video/")) return true
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in setOf("mkv", "avi", "mp4", "mov", "flv", "wmv", "m4v", "webm", "3gp")
    }

    private fun isSubtitleFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in setOf("srt", "ass", "vtt", "sub", "ssa", "ttml", "sbv", "scc")
    }

    /**
     * Downloads a file and exports it to the device's public Download directory.
     * Suspends until complete or timeout.
     */
    suspend fun exportFileToDownloads(fileId: Int, fileName: String, onProgress: (Int) -> Unit): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Request download
                val downloadRequest = JSONObject().apply {
                    put("@type", "downloadFile")
                    put("file_id", fileId)
                    put("priority", 32)
                    put("offset", 0)
                    put("limit", 0) // Download whole file
                    put("synchronous", false) 
                }
                send(downloadRequest.toString())

                // Poll for completion
                var localPath = ""
                var isCompleted = false
                var attempts = 0
                while (!isCompleted && attempts < 120) { // 2 minutes timeout for subtitles
                    val getFileReq = JSONObject().apply {
                        put("@type", "getFile")
                        put("file_id", fileId)
                    }
                    val response = sendAsync(getFileReq)
                    val expectedSize = response.optLong("expected_size", 1).coerceAtLeast(1)
                    val local = response.optJSONObject("local")
                    if (local != null) {
                        isCompleted = local.optBoolean("is_downloading_completed", false)
                        val downloadedSize = local.optLong("downloaded_size", 0)
                        val progress = ((downloadedSize.toFloat() / expectedSize.toFloat()) * 100).toInt()
                        
                        withContext(Dispatchers.Main) {
                            onProgress(progress.coerceIn(0, 100))
                        }

                        if (isCompleted) {
                            localPath = local.optString("path", "")
                            break
                        }
                    }
                    delay(1000)
                    attempts++
                }

                if (isCompleted && localPath.isNotEmpty()) {
                    val sourceFile = File(localPath)
                    if (sourceFile.exists()) {
                        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                        if (!downloadsDir.exists()) downloadsDir.mkdirs()
                        
                        val destFile = File(downloadsDir, fileName)
                        sourceFile.copyTo(destFile, overwrite = true)
                        return@withContext true
                    }
                }
                false
            } catch (e: Exception) {
                Log.e(TAG, "Error exporting file to downloads", e)
                false
            }
        }
    }

    /**
     * Read file part using TdApi.ReadFilePart.
     * Supports 4GB files with Long offset.
     * Includes retry logic for transient failures.
     */
    suspend fun readFilePart(fileId: Int, offset: Long, count: Long): ByteArray? {
        val maxRetries = 3
        var lastException: Exception? = null
        
        repeat(maxRetries) { attempt ->
            try {
                val result = readFilePartInternal(fileId, offset, count)
                if (result != null) return result
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Read file part attempt ${attempt + 1}/$maxRetries failed", e)
                if (attempt < maxRetries - 1) {
                    kotlinx.coroutines.delay(500L * (attempt + 1)) // Exponential backoff
                }
            }
        }
        
        Log.e(TAG, "Error reading file part after $maxRetries attempts", lastException)
        return null
    }
    
    private suspend fun readFilePartInternal(fileId: Int, offset: Long, count: Long): ByteArray? {
        // First, ensure we have the remote file info
        val downloadRequest = JSONObject().apply {
            put("@type", "downloadFile")
            put("file_id", fileId)
            put("priority", 32)
            put("offset", offset)
            put("limit", count)
            put("synchronous", true)
        }

        val downloadResponse = sendAsync(downloadRequest)
        val localPath = downloadResponse.optJSONObject("local")?.optString("path", "") ?: ""

        if (localPath.isNotEmpty()) {
            val file = File(localPath)
            if (file.exists()) {
                return file.inputStream().use { input ->
                    input.skip(offset)
                    val buffer = ByteArray(count.toInt().coerceAtMost(CHUNK_SIZE.toInt()))
                    val bytesRead = input.read(buffer)
                    if (bytesRead > 0) buffer.copyOf(bytesRead) else null
                }
            }
        }

        // Use readFilePart if available
        val readRequest = JSONObject().apply {
            put("@type", "readFilePart")
            put("file_id", fileId)
            put("offset", offset)
            put("count", count.coerceAtMost(CHUNK_SIZE))
        }

        val response = sendAsync(readRequest)
        val dataBase64 = response.optString("data", "")
        if (dataBase64.isNotEmpty()) {
            return android.util.Base64.decode(dataBase64, android.util.Base64.DEFAULT)
        }
        
        return null
    }

    /**
     * Get file size using Long for 4GB support.
     */
    suspend fun getFileSize(fileId: Int): Long {
        val request = JSONObject().apply {
            put("@type", "getFile")
            put("file_id", fileId)
        }

        return try {
            val response = sendAsync(request)
            response.getLong("expected_size")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file size", e)
            0L
        }
    }

    private fun handleFileUpdate(json: JSONObject) {
        // Handle file download progress updates
        val file = json.optJSONObject("file")
        if (file != null) {
            val fileId = file.getInt("id")
            val local = file.optJSONObject("local")
            if (local != null) {
                val isDownloadingCompleted = local.optBoolean("is_downloading_completed", false)
                val downloadedSize = local.optLong("downloaded_size", 0)
                Log.d(TAG, "File $fileId: downloaded=$downloadedSize, completed=$isDownloadingCompleted")
            }
            // Trigger storage optimization on every update (aggressive)
            optimizeStorage()
        }
    }

    private suspend fun sendAsync(request: JSONObject): JSONObject {
        val id = getNextRequestId()
        request.put("@extra", id)

        val deferred = CompletableDeferred<JSONObject>()
        pendingRequests[id] = deferred

        send(request.toString())

        return withTimeout(120000) { deferred.await() } // 120 seconds for large file operations
    }

    private fun send(request: String) {
        Log.e(TAG, "Sending to client $clientId: $request")
        JsonClient.td_send(clientId, request)
    }

    private fun getNextRequestId(): String {
        return "req_${requestId.getAndIncrement()}"
    }

    /**
     * Delete a specific file from TDLib cache.
     * Call this after video playback completes to free up space.
     */
    fun deleteFile(fileId: Int) {
        scope.launch {
            try {
                val request = JSONObject().apply {
                    put("@type", "deleteFile")
                    put("file_id", fileId)
                }
                sendAsync(request)
                Log.d(TAG, "Deleted file: $fileId")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting file $fileId", e)
            }
        }
    }

    /**
     * Optimize storage by deleting old/unused files.
     * Keeps cache under MAX_CACHE_SIZE and removes files older than MAX_FILE_AGE_SECONDS.
     * Preserves thumbnails, profile photos, stickers, and wallpapers.
     */
    fun optimizeStorage() {
        scope.launch {
            try {
                Log.d(TAG, "Running storage optimization...")
                val request = JSONObject().apply {
                    put("@type", "optimizeStorage")
                    put("size", MAX_CACHE_SIZE)
                    put("ttl", MAX_FILE_AGE_SECONDS)
                    put("count", -1) // No file count limit
                    put("immunity_delay", 0) // Files must be at least 1 hour old
                    // File types to delete (videos, documents, audio, etc.)
                    // Exclude thumbnails (1), profile photos (2), stickers (5), wallpapers (8)
                    put("file_types", org.json.JSONArray().apply {
                        put(JSONObject().put("@type", "fileTypeVideo"))
                        put(JSONObject().put("@type", "fileTypeDocument"))
                        put(JSONObject().put("@type", "fileTypeAudio"))
                        put(JSONObject().put("@type", "fileTypeVideoNote"))
                        put(JSONObject().put("@type", "fileTypeVoiceNote"))
                    })
                    put("chat_ids", org.json.JSONArray()) // All chats
                    put("exclude_chat_ids", org.json.JSONArray()) // No exclusions
                    put("return_deleted_file_statistics", false)
                    put("chat_limit", 0)
                }
                val response = sendAsync(request)
                val freedSize = response.optLong("size", 0)
                val freedCount = response.optInt("count", 0)
                Log.d(TAG, "Storage optimized: freed ${freedSize / 1024 / 1024}MB, $freedCount files")
            } catch (e: Exception) {
                Log.e(TAG, "Error optimizing storage", e)
            }
        }
    }

    fun close() {
        scope.cancel()
    }
}

sealed class AuthState {
    object Initial : AuthState()
    object WaitingForParameters : AuthState()
    object WaitingForPhoneNumber : AuthState()
    object WaitingForQrScan : AuthState()
    object WaitingForCode : AuthState()
    object WaitingForPassword : AuthState()
    object Authorized : AuthState()
    object Closed : AuthState()
}
