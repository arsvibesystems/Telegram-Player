package com.telegramplayer.browse

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.telegramplayer.R
import com.telegramplayer.data.MediaItem
import com.telegramplayer.data.MediaType
import com.telegramplayer.data.TmdbRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Premium Netflix-style presenter for media item cards.
 * 
 * Visual design:
 * - 2:3 poster aspect ratio (vertical movie poster style)
 * - Rounded corners (12dp) with subtle card elevation
 * - Focus: smooth 1.08x scale + cinema-gold glowing border + elevated info bar
 * - Unfocus: spring-back to 1.0x with muted styling
 * - Poster loading priority: Telegram thumbnail → TMDB poster → gradient placeholder
 */
class MediaCardPresenter(
    private val cardWidth: Int = 220,
    private val cardHeight: Int = 330,
    private val onLongClick: ((MediaItem) -> Unit)? = null
) : Presenter() {

    companion object {
        private const val CORNER_RADIUS_DP = 12f
        private const val FOCUS_SCALE = 1.08f
        private const val FOCUS_ANIM_DURATION = 250L
        private const val FOCUS_BORDER_WIDTH = 3
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val jobs = mutableMapOf<ViewHolder, kotlinx.coroutines.Job>()

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val density = parent.context.resources.displayMetrics.density
        val cornerRadius = CORNER_RADIUS_DP * density

        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true

            // Dark card background with rounded corners and subtle border
            val background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.card_background))
                setCornerRadius(cornerRadius)
                setStroke(1, ContextCompat.getColor(context, R.color.card_border))
            }
            setBackgroundDrawable(background)

            // Info area: dark translucent bottom section
            infoAreaBackground = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.surface_elevated))
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, cornerRadius, cornerRadius, cornerRadius, cornerRadius)
            }

            // Poster image fills card with center crop
            setMainImageScaleType(ImageView.ScaleType.CENTER_CROP)
        }

        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val mediaItem = item as MediaItem
        val cardView = viewHolder.view as ImageCardView
        val context = cardView.context
        val density = context.resources.displayMetrics.density
        val cornerRadius = CORNER_RADIUS_DP * density

        // ── Title & Metadata ──
        cardView.titleText = mediaItem.title
        val durationText = formatDuration(mediaItem.duration)
        val sizeText = formatSize(mediaItem.size)
        cardView.contentText = if (mediaItem.duration > 0) "$durationText • $sizeText" else sizeText

        cardView.setMainImageDimensions(cardWidth, cardHeight)

        // ── Poster Loading (TMDB priority) ──
        // Set gradient placeholder immediately to avoid blank flashes
        setGradientPlaceholder(cardView, mediaItem)



        if (mediaItem.mediaType == MediaType.VIDEO) {
            val tmdbRepo = TmdbRepository.getInstance(context)
            if (tmdbRepo.looksLikeMovie(mediaItem.fileName, mediaItem.duration, mediaItem.size)) {
                // Priority 1: Fetch TMDB poster asynchronously
                val job = scope.launch {
                    val result = tmdbRepo.searchMoviePoster(mediaItem.fileName)
                    
                    // Safety check: skip if view is detached or context is destroyed
                    if (context is android.app.Activity && context.isDestroyed) return@launch
                    
                    try {
                        if (result != null && result.posterUrl.isNotEmpty()) {
                            com.bumptech.glide.Glide.with(context)
                                .load(result.posterUrl)
                                .centerCrop()
                                .placeholder(cardView.mainImageView.drawable)
                                .into(cardView.mainImageView)
                            cardView.mainImageView.clearColorFilter()
                        } else if (mediaItem.thumbnailPath != null) {
                            // Priority 2: Fallback to Telegram-provided thumbnail
                            com.bumptech.glide.Glide.with(context)
                                .load(mediaItem.thumbnailPath)
                                .centerCrop()
                                .placeholder(cardView.mainImageView.drawable)
                                .into(cardView.mainImageView)
                            cardView.mainImageView.clearColorFilter()
                        }
                    } catch (e: Exception) {
                        // Ignore Glide destroyed activity exceptions
                    }
                }
                jobs[viewHolder] = job
            } else {
                // Not a movie (e.g. short clip). Priority: Telegram thumbnail
                if (mediaItem.thumbnailPath != null) {
                    com.bumptech.glide.Glide.with(context)
                        .load(mediaItem.thumbnailPath)
                        .centerCrop()
                        .placeholder(cardView.mainImageView.drawable)
                        .into(cardView.mainImageView)
                    cardView.mainImageView.clearColorFilter()
                }
            }
        } else {
            // Not a video (audio/doc). Priority: Telegram thumbnail
            if (mediaItem.thumbnailPath != null) {
                com.bumptech.glide.Glide.with(context)
                    .load(mediaItem.thumbnailPath)
                    .centerCrop()
                    .placeholder(cardView.mainImageView.drawable)
                    .into(cardView.mainImageView)
                cardView.mainImageView.clearColorFilter()
            }
        }

        // ── Premium Focus/Unfocus Animations ──
        cardView.setOnFocusChangeListener { view, hasFocus ->
            val v = view as ImageCardView
            if (hasFocus) {
                // Smooth scale up with overshoot interpolator feel
                v.animate()
                    .scaleX(FOCUS_SCALE)
                    .scaleY(FOCUS_SCALE)
                    .setDuration(FOCUS_ANIM_DURATION)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                    .start()

                // Cinema-gold glowing border
                val focusBg = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.card_background_focused))
                    setCornerRadius(cornerRadius)
                    setStroke(
                        (FOCUS_BORDER_WIDTH * density).toInt(),
                        ContextCompat.getColor(context, R.color.focus_glow)
                    )
                }
                v.background = focusBg

                // Highlighted info area (accent color)
                v.infoAreaBackground = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.netflix_red))
                    cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, cornerRadius, cornerRadius, cornerRadius, cornerRadius)
                }

                // Bring to front for overlap effect
                v.translationZ = 8f * density

            } else {
                // Smooth scale down
                v.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(FOCUS_ANIM_DURATION)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                    .start()

                // Normal subtle border
                val normalBg = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.card_background))
                    setCornerRadius(cornerRadius)
                    setStroke(1, ContextCompat.getColor(context, R.color.card_border))
                }
                v.background = normalBg

                // Normal info area
                v.infoAreaBackground = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.surface_elevated))
                    cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, cornerRadius, cornerRadius, cornerRadius, cornerRadius)
                }

                // Reset elevation
                v.translationZ = 0f
            }
        }

        // ── Long Click (document download) ──
        if (onLongClick != null) {
            cardView.setOnLongClickListener {
                onLongClick.invoke(mediaItem)
                true
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        jobs.remove(viewHolder)?.cancel()
        
        try {
            com.bumptech.glide.Glide.with(cardView.context).clear(cardView.mainImageView)
        } catch (e: Exception) {}
        
        cardView.mainImage = null
        cardView.onFocusChangeListener = null
    }

    /**
     * Sets a gradient placeholder with a media-type icon.
     * Creates a premium dark gradient background instead of a blank card.
     */
    private fun setGradientPlaceholder(cardView: ImageCardView, mediaItem: MediaItem) {
        val context = cardView.context

        // Set gradient background on the image area
        val gradientDrawable = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                ContextCompat.getColor(context, R.color.tmdb_placeholder_start),
                ContextCompat.getColor(context, R.color.tmdb_placeholder_end)
            )
        )
        cardView.mainImageView.setImageDrawable(gradientDrawable)

        // Overlay a centered media-type icon
        val iconRes = when (mediaItem.mediaType) {
            MediaType.VIDEO -> android.R.drawable.ic_media_play
            MediaType.AUDIO -> android.R.drawable.ic_lock_silent_mode_off
            MediaType.DOCUMENT -> android.R.drawable.ic_menu_save
        }
        cardView.mainImageView.apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER
            setColorFilter(ContextCompat.getColor(context, R.color.text_secondary))
            setBackgroundDrawable(gradientDrawable)
        }
    }

    private fun formatDuration(seconds: Int): String {
        if (seconds <= 0) return ""
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) {
            "${hours}h ${minutes}m"
        } else {
            "${minutes}m"
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
