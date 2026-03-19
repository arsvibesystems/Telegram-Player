package com.telegramplayer.browse

import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.telegramplayer.R

/**
 * Presenter for the Settings card.
 */
class SettingsCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(300, 300)
        }
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val cardView = viewHolder.view as ImageCardView
        
        if (item is String) {
            cardView.titleText = item
            cardView.contentText = "Configure app settings"
            
            // Use a generic settings icon or a drawable resource
            // For now, setting a background color or simple icon
            cardView.mainImage = ContextCompat.getDrawable(cardView.context, android.R.drawable.ic_menu_preferences)
            cardView.setMainImageScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE)
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.badgeImage = null
        cardView.mainImage = null
    }
}
