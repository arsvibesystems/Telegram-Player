package com.telegramplayer.browse

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.leanback.app.BackgroundManager
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.*
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.telegramplayer.R
import com.telegramplayer.TelegramPlayerApp
import com.telegramplayer.data.ChatInfo
import com.telegramplayer.data.ChatType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Sidebar action IDs
private const val ACTION_REFRESH = "action_refresh"
private const val ACTION_LATEST = "action_latest"

/**
 * Main Leanback browse fragment displaying Chat Folders.
 * Rows: Private Chats, Groups, Channels.
 * Features Netflix-style branding and background updates.
 */
class MainBrowseFragment : BrowseSupportFragment() {

    companion object {
        private const val TAG = "MainBrowseFragment"
    }

    private val tdClient get() = TelegramPlayerApp.getTdClient()
    private lateinit var backgroundManager: BackgroundManager
    private var defaultBackground: ColorDrawable? = null
    
    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter().apply {
        shadowEnabled = false
        selectEffectEnabled = true
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUI()
        setupBackgroundManager()
        setupEventListeners()
        loadChats()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.background = ColorDrawable(Color.TRANSPARENT)
    }

    private fun setupUI() {
        title = getString(R.string.browse_title)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        
        // Netflix-style branding
        brandColor = ContextCompat.getColor(requireContext(), R.color.netflix_red)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.netflix_red)

        adapter = rowsAdapter
    }
    
    private fun setupBackgroundManager() {
        backgroundManager = BackgroundManager.getInstance(requireActivity())
        backgroundManager.attach(requireActivity().window)
        defaultBackground = ColorDrawable(ContextCompat.getColor(requireContext(), R.color.netflix_background))
        backgroundManager.color = ContextCompat.getColor(requireContext(), R.color.netflix_background)
    }

    private fun setupEventListeners() {
        // Launch Video Grid / handle sidebar actions on Click
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when {
                item is SidebarAction && item.id == ACTION_REFRESH -> {
                    Toast.makeText(context, "Refreshing chats…", Toast.LENGTH_SHORT).show()
                    loadChats()
                }
                item is SidebarAction && item.id == ACTION_LATEST -> {
                    startActivity(Intent(context, LatestFilesActivity::class.java))
                }
                item is ChatInfo -> {
                    val intent = Intent(context, VideoGridActivity::class.java).apply {
                        putExtra(VideoGridActivity.EXTRA_CHAT_ID, item.id)
                        putExtra(VideoGridActivity.EXTRA_CHAT_TITLE, item.title)
                    }
                    startActivity(intent)
                }
                item is String && item == "Player Settings" -> {
                    startActivity(SettingsActivity.newIntent(requireContext()))
                }
            }
        }

        // Update background on item selection
        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, _ ->
            if (item is ChatInfo) {
                updateBackground(item.photoPath)
            }
        }
    }

    private fun updateBackground(uri: String?) {
        if (uri != null) {
            Glide.with(this)
                .load(uri)
                .apply(RequestOptions.centerCropTransform())
                .into(object : com.bumptech.glide.request.target.CustomTarget<android.graphics.drawable.Drawable>() {
                    override fun onResourceReady(
                        resource: android.graphics.drawable.Drawable,
                        transition: com.bumptech.glide.request.transition.Transition<in android.graphics.drawable.Drawable>?
                    ) {
                        backgroundManager.drawable = resource
                    }

                    override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {
                        backgroundManager.drawable = defaultBackground
                    }
                })
        } else {
            backgroundManager.drawable = defaultBackground
        }
    }

    private fun loadChats() {
        lifecycleScope.launch {
            try {
                // Loading Indicator — keep sidebar action rows at top
                rowsAdapter.clear()

                // ── Sidebar Action Rows (always first) ──────────────────────────
                addSidebarActionRow()

                val loadingHeader = HeaderItem(rowsAdapter.size().toLong(), "Loading chats...")
                val loadingAdapter = ArrayObjectAdapter(ChatCardPresenter())
                rowsAdapter.add(ListRow(loadingHeader, loadingAdapter))

                val chats = withContext(Dispatchers.IO) {
                    tdClient.getChats(150)
                }

                // Remove only the chat/settings rows (keep the 2 sidebar rows at indices 0 & 1)
                while (rowsAdapter.size() > 2) {
                    rowsAdapter.removeItems(2, 1)
                }

                // Group chats by type
                val privateChats = chats.filter { it.type == ChatType.PRIVATE }
                val groups = chats.filter { it.type == ChatType.GROUP }
                val channels = chats.filter { it.type == ChatType.CHANNEL }

                // Row: Private Chats
                if (privateChats.isNotEmpty()) {
                    addChatRow("💬 Private Chats", privateChats)
                }

                // Row: Groups
                if (groups.isNotEmpty()) {
                    addChatRow("👥 Groups", groups)
                }

                // Row: Channels
                if (channels.isNotEmpty()) {
                    addChatRow("📺 Channels", channels)
                }

                // Settings Row
                addSettingsRow()

            } catch (e: Exception) {
                Log.e(TAG, "Error loading chats", e)
                Toast.makeText(context, "Error loading chats", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Adds the Refresh and Latest Files action rows as the first two items
     * in the side shelf. These stay pinned at the top across refreshes.
     */
    private fun addSidebarActionRow() {
        val presenter = SidebarActionPresenter()

        // Row 0 — Refresh
        val refreshAdapter = ArrayObjectAdapter(presenter)
        refreshAdapter.add(
            SidebarAction(
                id = ACTION_REFRESH,
                label = "Refresh",
                iconRes = R.drawable.ic_refresh
            )
        )
        rowsAdapter.add(ListRow(HeaderItem(0, "⟳ Refresh"), refreshAdapter))

        // Row 1 — Latest Files
        val latestAdapter = ArrayObjectAdapter(presenter)
        latestAdapter.add(
            SidebarAction(
                id = ACTION_LATEST,
                label = "Latest Files",
                iconRes = R.drawable.ic_latest
            )
        )
        rowsAdapter.add(ListRow(HeaderItem(1, "🕐 Latest Files"), latestAdapter))
    }

    private fun addChatRow(title: String, chats: List<ChatInfo>) {
        val cardPresenter = ChatCardPresenter()
        val listRowAdapter = ArrayObjectAdapter(cardPresenter)
        
        // Add chats to row
        chats.forEach { listRowAdapter.add(it) }

        val headerItem = HeaderItem(rowsAdapter.size().toLong(), title)
        rowsAdapter.add(ListRow(headerItem, listRowAdapter))
    }

    private fun addSettingsRow() {
        val cardPresenter = SettingsCardPresenter()
        val listRowAdapter = ArrayObjectAdapter(cardPresenter)
        listRowAdapter.add("Player Settings")
        
        val headerItem = HeaderItem(rowsAdapter.size().toLong(), "Settings")
        rowsAdapter.add(ListRow(headerItem, listRowAdapter))
    }
}
