package com.callnotes

import android.app.Application
import com.callnotes.data.AppDatabase
import com.callnotes.inference.ModelManager
import com.callnotes.util.Notifications

class CallNotesApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.build(this) }
    val models: ModelManager by lazy { ModelManager(this) }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}
