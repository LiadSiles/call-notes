package com.callnotes.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CallNotesTheme {
                // Hebrew-first: right-to-left whatever the system language.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    AppRoot()
                }
            }
        }
    }
}

private enum class Tab { Calls, Settings }

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val app = context.applicationContext as CallNotesApp
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()

    var tab by rememberSaveable { mutableStateOf(Tab.Calls) }
    var openCallId by rememberSaveable { mutableStateOf<Long?>(null) }

    // System settings may change while we're away (permissions, accessibility, battery).
    var resumeTick by remember { mutableIntStateOf(0) }
    val kickQueue: () -> Unit = {
        scope.launch(Dispatchers.IO) {
            if (prefs.autoTranscribe && app.database.calls().pendingCount() > 0) {
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

    val player = remember { AudioPlayer() }
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

    // Manual "transcribe now": doesn't depend on the auto-transcribe switch.
    val transcribeNow: () -> Unit = {
        scope.launch(Dispatchers.IO) { TranscriptionService.start(context) }
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
                onTranscribe = transcribeNow,
            )
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == Tab.Calls,
                            onClick = { tab = Tab.Calls },
                            icon = { Icon(Icons.Filled.Call, null) },
                            label = { Text("שיחות") },
                        )
                        NavigationBarItem(
                            selected = tab == Tab.Settings,
                            onClick = { tab = Tab.Settings },
                            icon = { Icon(Icons.Filled.Settings, null) },
                            label = { Text("הגדרות") },
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
