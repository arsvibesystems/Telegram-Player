package com.telegramplayer.browse

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import com.telegramplayer.R

/**
 * Presenter for sidebar action items (Refresh, Latest Files).
 * Renders a compact icon + label card styled for the side shelf.
 */
class SidebarActionPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sidebar_action, parent, false)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val action = item as SidebarAction
        val view = viewHolder.view

        val icon = view.findViewById<ImageView>(R.id.sidebar_action_icon)
        val label = view.findViewById<TextView>(R.id.sidebar_action_label)

        icon.setImageResource(action.iconRes)
        label.text = action.label

        view.setOnFocusChangeListener { v, hasFocus ->
            val alpha = if (hasFocus) 1.0f else 0.65f
            v.alpha = alpha
            val scale = if (hasFocus) 1.08f else 1.0f
            v.animate().scaleX(scale).scaleY(scale).setDuration(180).start()
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        viewHolder.view.onFocusChangeListener = null
    }
}
