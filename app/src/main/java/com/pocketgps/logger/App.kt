package com.pocketgps.logger

import android.app.Application
import com.pocketgps.logger.ServiceLocator

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
