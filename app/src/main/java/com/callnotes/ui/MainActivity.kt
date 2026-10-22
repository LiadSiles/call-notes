package com.callnotes.ui

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.callnotes.CallNotesApp
import com.callnotes.capture.CallRecorderService
import com.callnotes.processing.TranscriptionService
import com.callnotes.ui.theme.CallNotesTheme
import com.callnotes.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The only screen host: three tabs (calls, tasks, settings) and the call detail, all in
 * Compose. A FragmentActivity because BiometricPrompt (app lock) needs one. Notification
 * taps arrive as [openRequest] / [openTaskRequest].
 */
class MainActivity : androidx.fragment.app.FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) openFrom(intent)
        enableEdgeToEdge()
        setContent {
            CallNotesTheme {
                // Hebrew-first: right-to-left whatever the system language.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    val locked by AppLock.locked.collectAsState()
                    // AppRoot stays composed underneath, so unlocking returns to the same screen.
                    AppRoot()
                    if (locked) LockScreen(this@MainActivity)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppLock.onStart(this)
    }

    override fun onStop() {
        super.onStop()
        AppLock.onStop()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    /** A notification about a call opens that call. */
    private fun openFrom(intent: android.content.Intent?) {
        intent?.getLongExtra(com.callnotes.util.Notifications.EXTRA_OPEN_CALL, -1L)?.takeIf { it > 0 }
            ?.let { openRequest.value = it }
        intent?.getLongExtra(com.callnotes.util.Notifications.EXTRA_OPEN_TASK, -1L)?.takeIf { it > 0 }
            ?.let { openTaskRequest.value = it }
    }

    companion object {
        /** Call to open, set from a notification tap; consumed by [AppRoot]. */
        val openRequest = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
        /** Task to show in the tasks screen, from a "new task" notification. */
        val openTaskRequest = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
    }
}

private enum class Tab { Calls, Tasks, Settings }

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val app = context.applicationContext as CallNotesApp
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()

    var tab by rememberSaveable { mutableStateOf(Tab.Calls) }
    var openCallId by rememberSaveable { mutableStateOf<Long?>(null) }
    /** Transcript line to scroll to when the call opens (from a task / key point). */
    var openFocus by remember { mutableStateOf<Int?>(null) }
    var openFocusText by remember { mutableStateOf<String?>(null) }
    /** Task to scroll to and highlight in the tasks screen (from a notification). */
    var focusTask by remember { mutableStateOf<Long?>(null) }
    val openRequest by MainActivity.openRequest.collectAsState()
    LaunchedEffect(openRequest) {
        openRequest?.let { openCallId = it; tab = Tab.Calls; MainActivity.openRequest.value = null }
    }
    val openTaskRequest by MainActivity.openTaskRequest.collectAsState()
    LaunchedEffect(openTaskRequest) {
        openTaskRequest?.let { openCallId = null; tab = Tab.Tasks; focusTask = it; MainActivity.openTaskRequest.value = null }
    }
    val openTasks by remember { app.database.calls().observeOpenTaskCount() }.collectAsState(0)

    // System settings may change while we're away (permissions, accessibility, battery).
    var resumeTick by remember { mutableIntStateOf(0) }
    val kickQueue: () -> Unit = {
        scope.launch(Dispatchers.IO) {
            val dao = app.database.calls()
            val analysable = app.models.readyModel(com.callnotes.inference.ModelRole.ANALYZE) != null &&
                dao.unanalyzedCount(prefs.autoMaxMs) > 0
            if (prefs.autoTranscribe && (dao.pendingCount(prefs.autoMaxMs) > 0 || analysable)) {
                TranscriptionService.start(context)
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        resumeTick++
        CallRecorderService.refreshFromUi()
        app.models.refresh()
        kickQueue()
    }

    val player = remember { AudioPlayer(context.applicationContext) }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(player.isPlaying) {
        while (player.isPlaying) { player.refreshPosition(); delay(200) }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            try {
                Importer.import(context, uri)
                if (prefs.autoTranscribe) TranscriptionService.start(context)
            } catch (_: Exception) {
                // Unreadable file: nothing was added.
            }
        }
    }

    // Manual "transcribe now": doesn't depend on the auto-transcribe switch, and this
    // call goes ahead of anything already queued.
    val transcribeNow: (Long) -> Unit = { id ->
        scope.launch(Dispatchers.IO) { TranscriptionService.start(context, callId = id) }
    }
    val listState = rememberLazyListState()

    // One animated host for list <-> detail, so moving between them slides and fades
    // instead of tearing the screen down (which flashed the window background).
    AnimatedContent(
        targetState = openCallId,
        transitionSpec = {
            val opening = targetState != null
            val dir = if (opening) 1 else -1
            (slideInHorizontally(tween(260)) { w -> -dir * w / 5 } + fadeIn(tween(220)))
                .togetherWith(slideOutHorizontally(tween(260)) { w -> dir * w / 8 } + fadeOut(tween(160)))
        },
        label = "nav",
    ) { detail ->
        if (detail != null) {
            BackHandler { openCallId = null }
            CallDetailScreen(
                callId = detail,
                player = player,
                onBack = { openCallId = null },
                onTranscribe = { transcribeNow(detail) },
                focusSegment = openFocus,
                focusText = openFocusText,
                onFocusConsumed = { openFocus = null; openFocusText = null },
            )
        } else {
            val hairline = MaterialTheme.colorScheme.outlineVariant
            val navColors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 0.dp,
                        modifier = Modifier.drawBehind {
                            // Hairline above the bar instead of a shadow.
                            drawLine(hairline, androidx.compose.ui.geometry.Offset(0f, 0f),
                                androidx.compose.ui.geometry.Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
                        },
                    ) {
                        NavigationBarItem(
                            selected = tab == Tab.Calls,
                            onClick = { tab = Tab.Calls },
                            icon = { Icon(Icons.Filled.Call, null) },
                            label = { Text("שיחות") },
                            colors = navColors,
                        )
                        NavigationBarItem(
                            selected = tab == Tab.Tasks,
                            onClick = { tab = Tab.Tasks },
                            icon = {
                                androidx.compose.material3.BadgedBox(badge = {
                                    if (openTasks > 0) androidx.compose.material3.Badge { Text("$openTasks") }
                                }) { Icon(Icons.Filled.CheckCircle, null) }
                            },
                            label = { Text("משימות") },
                            colors = navColors,
                        )
                        NavigationBarItem(
                            selected = tab == Tab.Settings,
                            onClick = { tab = Tab.Settings },
                            icon = { Icon(Icons.Filled.Settings, null) },
                            label = { Text("הגדרות") },
                            colors = navColors,
                        )
                    }
                },
            ) { padding ->
                Crossfade(targetState = tab, animationSpec = tween(180), label = "tabs") { current ->
                    when (current) {
                        Tab.Calls -> CallsScreen(
                            contentPadding = padding,
                            resumeTick = resumeTick,
                            listState = listState,
                            onOpen = { openCallId = it },
                            onOpenSettings = { tab = Tab.Settings },
                            onImport = { importLauncher.launch(arrayOf("audio/*")) },
                            onOpenAt = { callId, segment, text ->
                                openFocus = segment; openFocusText = text; openCallId = callId
                            },
                        )
                        Tab.Tasks -> TasksScreen(
                            contentPadding = padding,
                            focusTaskId = focusTask,
                            onFocusConsumed = { focusTask = null },
                            onOpenCall = { callId, segment, text ->
                                openFocus = segment; openFocusText = text; openCallId = callId
                            },
                        )
                        Tab.Settings -> SettingsScreen(
                            contentPadding = padding,
                            resumeTick = resumeTick,
                            onModelReady = kickQueue,
                        )
                    }
                }
            }
        }
    }
}
