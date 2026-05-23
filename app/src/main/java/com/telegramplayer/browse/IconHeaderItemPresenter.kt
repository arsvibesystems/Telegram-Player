package com.telegramplayer.browse

import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowHeaderPresenter
import com.telegramplayer.R

/**
 * Custom Header Presenter for Sidebar Navigation.
 * Extends base RowHeaderPresenter with custom styling.
 */
class IconHeaderItemPresenter(private val onHeaderAction: (String) -> Unit = {}) : RowHeaderPresenter() {

    override fun onCreateViewHolder(parent: ViewGroup): RowHeaderPresenter.ViewHolder {
        val viewHolder = super.onCreateViewHolder(parent) as RowHeaderPresenter.ViewHolder
        
        // Try to find TextView within the header view hierarchy
        val view = viewHolder.view
        if (view is TextView) {
            view.setTextColor(ContextCompat.getColor(parent.context, R.color.sidebar_text))
        } else if (view is ViewGroup) {
            // Search for TextView in the view hierarchy
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child is TextView) {
                    child.setTextColor(ContextCompat.getColor(parent.context, R.color.sidebar_text))
                    break
                }
            }
        }
        
        return viewHolder
    }

    override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
        super.onBindViewHolder(viewHolder, item)
        val headerItem = (item as? Row)?.headerItem
        
        if (headerItem?.name == "⟳ Refresh") {
            viewHolder.view.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    onHeaderAction("REFRESH")
                    true
                } else {
                    false
                }
            }
            viewHolder.view.setOnClickListener {
                onHeaderAction("REFRESH")
            }
        } else {
            viewHolder.view.setOnKeyListener(null)
            viewHolder.view.setOnClickListener(null)
        }
    }
    
    override fun onSelectLevelChanged(holder: RowHeaderPresenter.ViewHolder) {
        val view = holder.view
        val alpha = if (holder.selectLevel > 0.5f) 1.0f else 0.5f
        view.alpha = alpha
    }
}
