package com.telegramplayer

import android.app.Application
import com.telegramplayer.data.TdClientManager

class TelegramPlayerApp : Application() {
    
    lateinit var tdClientManager: TdClientManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        tdClientManager = TdClientManager(this)
    }

    companion object {
        lateinit var instance: TelegramPlayerApp
            private set

        fun getTdClient(): TdClientManager = instance.tdClientManager
    }
}
