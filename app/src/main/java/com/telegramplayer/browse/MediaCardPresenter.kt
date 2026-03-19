package com.telegramplayer.browse

import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.telegramplayer.R
import com.telegramplayer.data.MediaItem
import com.telegramplayer.data.MediaType

/**
 * Enhanced presenter for media item cards in the Leanback browse grid.
 * Netflix-style vertical poster cards (2:3 aspect ratio).
 */
class MediaCardPresenter(
    private val cardWidth: Int = 220,
    private val cardHeight: Int = 330,
    private val onLongClick: ((MediaItem) -> Unit)? = null
) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            
            // Dark card background with subtle border
            val background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.card_background))
                cornerRadius = 8f * context.resources.displayMetrics.density
                setStroke(1, ContextCompat.getColor(context, R.color.card_border))
            }
            setBackgroundDrawable(background)
            
            // Info area styling
            infoAreaBackground = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.surface_elevated))
            }
            
            // Force Center Crop to avoid margins
            setMainImageScaleType(ImageView.ScaleType.CENTER_CROP)
        }
        
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val mediaItem = item as MediaItem
        val cardView = viewHolder.view as ImageCardView
        val context = cardView.context

        // Title 
        cardView.titleText = mediaItem.title
        
        // Duration and size
        val sizeText = formatSize(mediaItem.size)
        val durationText = formatDuration(mediaItem.duration)
        cardView.contentText = "$durationText • $sizeText"
        
        cardView.setMainImageDimensions(cardWidth, cardHeight)

        if (mediaItem.thumbnailPath != null) {
            com.bumptech.glide.Glide.with(context)
                .load(mediaItem.thumbnailPath)
                .centerCrop()
                .into(cardView.mainImageView)
            
            cardView.mainImageView.clearColorFilter()
        } else {
            // Set icon based on media type
            val iconRes = when (mediaItem.mediaType) {
                MediaType.VIDEO -> android.R.drawable.ic_media_play
                MediaType.AUDIO -> android.R.drawable.ic_lock_silent_mode_off
                MediaType.DOCUMENT -> android.R.drawable.ic_menu_save
            }
            
            cardView.mainImageView.apply {
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.CENTER
                setColorFilter(ContextCompat.getColor(context, R.color.primary))
                setBackgroundColor(ContextCompat.getColor(context, R.color.surface))
            }
        }
        
        // Focus listener for visual feedback
        cardView.setOnFocusChangeListener { view, hasFocus ->
            val v = view as ImageCardView
            if (hasFocus) {
                // Scale Up - meaningful pop
                v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(200).start()

                // Focused Background & Netflix Red Border
                val bg = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.card_background_focused))
                    cornerRadius = 8f * context.resources.displayMetrics.density
                    setStroke(4, ContextCompat.getColor(context, R.color.netflix_red))
                }
                v.background = bg
                v.infoAreaBackground = GradientDrawable().apply {
                     setColor(ContextCompat.getColor(context, R.color.netflix_red))
                }
            } else {
                // Scale Down
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()

                // Normal Background
                val bg = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.card_background))
                    cornerRadius = 8f * context.resources.displayMetrics.density
                    setStroke(1, ContextCompat.getColor(context, R.color.card_border))
                }
                v.background = bg
                v.infoAreaBackground = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.surface_elevated))
                }
            }
        }
        
        if (onLongClick != null) {
            cardView.setOnLongClickListener { 
                onLongClick.invoke(mediaItem)
                true
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.mainImage = null
        cardView.onFocusChangeListener = null
    }

    private fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format("%d:%02d", minutes, secs)
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> String.format("%.2f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
