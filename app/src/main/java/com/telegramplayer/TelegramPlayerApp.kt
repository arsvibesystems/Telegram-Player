package com.telegramplayer

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.telegramplayer.data.TdClientManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TelegramPlayerApp : Application(), DefaultLifecycleObserver {
    
    lateinit var tdClientManager: TdClientManager
        private set

    private val appScope = CoroutineScope(Dispatchers.Main)
    private var backgroundJob: Job? = null

    override fun onCreate() {
        super<Application>.onCreate()
        instance = this
        tdClientManager = TdClientManager(this).apply {
            apiId = 31075029
            apiHash = "7b098845a66acb65bdf33520d8d35602"
            initialize()
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        // Start background service to warm up and keep connection alive
        com.telegramplayer.data.TelegramBackgroundService.start(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        // App is in foreground
    }

    override fun onStop(owner: LifecycleOwner) {
        // App went to background
        // Removed TDLib shutdown to allow MX Player to continue streaming
    }

    companion object {
        lateinit var instance: TelegramPlayerApp
            private set

        fun getTdClient(): TdClientManager = instance.tdClientManager
    }
}
