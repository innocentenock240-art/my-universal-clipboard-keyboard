package com.example

import android.app.Application
import com.example.sync.SyncRuntime

class UniversalClipboardApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Initialize authoritative application-wide synchronization runtime
        SyncRuntime.initialize(this)
    }
}
