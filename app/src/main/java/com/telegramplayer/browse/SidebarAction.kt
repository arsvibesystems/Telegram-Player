package com.telegramplayer.browse

/**
 * Represents a sidebar action item (Refresh, Latest Files, etc.)
 */
data class SidebarAction(
    val id: String,
    val label: String,
    val iconRes: Int
)
