package com.telegramplayer.browse

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.telegramplayer.R
import com.telegramplayer.data.ChatInfo
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.bumptech.glide.request.RequestOptions

/**
 * Presenter for Chat/Folder cards on the main screen.
 * Displays chat thumbnail and title with enhanced focus effects.
 * Large cards for TV viewing.
 */
class ChatCardPresenter : Presenter() {

    companion object {
        private const val CARD_WIDTH = 280
        private const val CARD_HEIGHT = 280
    }

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
            
            // Dark card background
            val background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.card_background))
                cornerRadius = 16f * context.resources.displayMetrics.density
            }
            setBackgroundDrawable(background)
            
            // Info area styling
            infoAreaBackground = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.surface_elevated))
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 16f, 16f, 16f, 16f)
            }
        }
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val chat = item as ChatInfo
        val cardView = viewHolder.view as ImageCardView
        val context = cardView.context

        cardView.titleText = chat.title
        cardView.contentText = if (chat.mediaCount > 0) "${chat.mediaCount} videos" else ""
        
        // Load Thumbnail or Fallback
        if (chat.photoPath != null) {
            Glide.with(context)
                .load(chat.photoPath)
                .apply(RequestOptions().transform(CircleCrop()))
                .error(R.drawable.ic_folder)
                .into(cardView.mainImageView)
        } else {
            val icon = ContextCompat.getDrawable(context, R.drawable.ic_folder)
            cardView.mainImageView.setImageDrawable(icon)
            cardView.mainImageView.setColorFilter(getColorForId(chat.id))
        }

        // Custom Focus Handling - No Zoom, just Border
        cardView.setOnFocusChangeListener { view, hasFocus ->
            val v = view as ImageCardView
            if (hasFocus) {
                // No Scale - Zoom removed as per request
                v.animate().cancel()
                v.scaleX = 1.0f
                v.scaleY = 1.0f
                
                // Netflix Red Border on focus
                val border = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(ContextCompat.getColor(context, R.color.card_background_focused))
                    setStroke(6, ContextCompat.getColor(context, R.color.netflix_red))
                    cornerRadius = 16f * context.resources.displayMetrics.density
                }
                v.background = border
            } else {
                // Reset Scale
                v.animate().cancel()
                v.scaleX = 1.0f
                v.scaleY = 1.0f
                
                // Normal Background
                val bg = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.card_background))
                    cornerRadius = 16f * context.resources.displayMetrics.density
                }
                v.background = bg
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.mainImage = null
        cardView.onFocusChangeListener = null
    }

    private fun getColorForId(id: Long): Int {
        val colors = listOf(
            0xFFE57373, 0xFFF06292, 0xFFBA68C8, 0xFF9575CD, 0xFF7986CB, 
            0xFF64B5F6, 0xFF4FC3F7, 0xFF4DD0E1, 0xFF4DB6AC, 0xFF81C784,
            0xFFAED581, 0xFFFF8A65, 0xFFD4E157, 0xFFFFD54F, 0xFFFFB74D
        )
        return colors[(kotlin.math.abs(id) % colors.size).toInt()].toInt()
    }
}
