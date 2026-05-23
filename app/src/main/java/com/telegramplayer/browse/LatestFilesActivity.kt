package com.telegramplayer.browse

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.telegramplayer.R

/**
 * Activity that hosts the LatestFilesFragment.
 * Shows the most recent video and subtitle files across all chats.
 */
class LatestFilesActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_latest_files)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.latest_files_frame, LatestFilesFragment())
                .commit()
        }
    }
}
