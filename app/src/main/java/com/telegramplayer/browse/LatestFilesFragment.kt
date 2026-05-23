package com.telegramplayer.browse

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.*
import androidx.lifecycle.lifecycleScope
import com.telegramplayer.R
import com.telegramplayer.TelegramPlayerApp
import com.telegramplayer.data.MediaItem
import com.telegramplayer.data.MediaType
import com.telegramplayer.streaming.StreamingProxyServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fragment that shows the most recent video and subtitle files across ALL chats.
 * Fetches the latest N items from each chat in parallel and merges, sorted by date.
 */
class LatestFilesFragment : VerticalGridSupportFragment() {

    companion object {
        private const val TAG = "LatestFilesFragment"
        private const val ITEMS_PER_CHAT = 20   // Fetch last 20 items per chat
        private const val MAX_TOTAL_ITEMS = 60  // Show top 60 most recent across all chats
    }

    private val tdClient get() = TelegramPlayerApp.getTdClient()
    private lateinit var gridAdapter: ArrayObjectAdapter
    private val proxyServer = StreamingProxyServer.getInstance()

    private var pendingDownloadItem: MediaItem? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                pendingDownloadItem?.let { downloadSubtitle(it) }
            } else {
                Toast.makeText(context, "Storage permission required to download", Toast.LENGTH_SHORT).show()
            }
        }

    private val manageStorageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                pendingDownloadItem?.let { downloadSubtitle(it) }
            } else {
                Toast.makeText(context, "All files access required to download", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUI()
        loadLatestFiles()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: triggering post-playback cache cleanup")
        tdClient.forceCleanup()
        proxyServer.clearAllCaches()
    }

    private fun setupUI() {
        title = "🕐 Latest Files"

        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val minColumnWidth = 380
        val columns = (width / minColumnWidth).coerceAtLeast(4)
        val usableWidth = width - (80 * displayMetrics.density)
        val cardWidth = ((usableWidth / columns) / 1.15).toInt()
        val cardHeight = (cardWidth * 3 / 2)

        gridAdapter = ArrayObjectAdapter(MediaCardPresenter(cardWidth, cardHeight) { mediaItem ->
            handleLongClick(mediaItem)
        })

        val gridPresenter = VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_NONE, false).apply {
            numberOfColumns = columns
            shadowEnabled = false
        }

        setGridPresenter(gridPresenter)
        adapter = gridAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is MediaItem) {
                if (item.mediaType == MediaType.DOCUMENT) {
                    Toast.makeText(context, "Long press 'Select' to download this file", Toast.LENGTH_SHORT).show()
                } else {
                    playWithSelectedPlayer(item)
                }
            }
        }
    }

    private fun loadLatestFiles() {
        lifecycleScope.launch {
            try {
                gridAdapter.clear()

                // Get all chats first
                val chats = withContext(Dispatchers.IO) {
                    tdClient.getChats(150)
                }

                if (chats.isEmpty()) {
                    Toast.makeText(context, "No chats found", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Fetch latest files from all chats in parallel
                val allItems = withContext(Dispatchers.IO) {
                    chats.map { chat ->
                        async {
                            try {
                                tdClient.searchChatMedia(chat.id, ITEMS_PER_CHAT)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to fetch media for chat ${chat.id}", e)
                                emptyList()
                            }
                        }
                    }.awaitAll().flatten()
                }

                // Sort by messageId descending (newest first) and cap total
                val sorted = allItems
                    .distinctBy { it.fileId }
                    .sortedByDescending { it.messageId }
                    .take(MAX_TOTAL_ITEMS)

                gridAdapter.clear()
                sorted.forEach { gridAdapter.add(it) }

                if (sorted.isEmpty()) {
                    Toast.makeText(context, "No recent files found", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading latest files", e)
                Toast.makeText(context, "Error loading latest files", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun playWithSelectedPlayer(mediaItem: MediaItem) {
        lifecycleScope.launch {
            try {
                val resolvedFileId = withContext(Dispatchers.IO) {
                    tdClient.prepareFileForStreaming(mediaItem.fileId, mediaItem.remoteFileId)
                }

                var confirmedSize = withContext(Dispatchers.IO) {
                    tdClient.getFileSize(resolvedFileId)
                }

                if (confirmedSize <= 0L && mediaItem.size > 0L) {
                    confirmedSize = mediaItem.size
                }

                proxyServer.registerFile(resolvedFileId, mediaItem.fileName, confirmedSize, mediaItem.remoteFileId)
                val streamUrl = proxyServer.getStreamUrl(resolvedFileId)

                Toast.makeText(context, "Playing: ${mediaItem.title}", Toast.LENGTH_SHORT).show()

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(streamUrl), mediaItem.mimeType)
                    putExtra("title", mediaItem.title)
                    putExtra("secure_uri", true)
                }

                val playerManager = com.telegramplayer.data.PlayerManager(requireContext())
                val selectedPackage = playerManager.getSelectedPlayer()

                if (selectedPackage != null) {
                    intent.setPackage(selectedPackage)
                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Selected player not found", Toast.LENGTH_SHORT).show()
                        launchPlayerChooser(intent)
                    }
                } else {
                    launchPlayerChooser(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error preparing file for streaming", e)
                Toast.makeText(context, "Error preparing video", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchPlayerChooser(intent: Intent) {
        try {
            startActivity(Intent.createChooser(intent, "Select Video Player"))
        } catch (e: Exception) {
            Toast.makeText(context, "No video player found", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleLongClick(mediaItem: MediaItem) {
        if (mediaItem.mediaType != MediaType.DOCUMENT) return

        pendingDownloadItem = mediaItem

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                downloadSubtitle(mediaItem)
            } else {
                Toast.makeText(context, "Please allow 'All files access' to save to Downloads", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
                manageStorageLauncher.launch(intent)
            }
        } else {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    requireContext(), android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                downloadSubtitle(mediaItem)
            } else {
                requestPermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private fun downloadSubtitle(item: MediaItem) {
        Toast.makeText(context, "Downloading ${item.title}...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                tdClient.exportFileToDownloads(item.fileId, item.fileName) { _ -> }
            }
            if (success) {
                Toast.makeText(context, "Saved to Download folder", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Failed to download", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
