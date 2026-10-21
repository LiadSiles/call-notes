package com.callnotes

import android.app.Application
import com.callnotes.data.AppDatabase
import com.callnotes.data.CallListItem
import com.callnotes.inference.ModelManager
import com.callnotes.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class CallNotesApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy { AppDatabase.build(this) }
    val models: ModelManager by lazy { ModelManager(this) }

    /**
     * The call list, kept warm for the whole process so screens show it instantly when
     * they reappear (re-subscribing per screen started from an empty list and flashed
     * the empty state). Null until the first database read completes.
     */
    val callList: StateFlow<List<CallListItem>?> by lazy {
        database.calls().observeList().stateIn(appScope, SharingStarted.Eagerly, null)
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}
