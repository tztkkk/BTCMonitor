package com.tzt.btcmonitor

import android.app.Application

class BTCMonitorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContainer.initialize(this)
    }
}
