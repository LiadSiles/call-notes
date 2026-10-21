package com.callnotes.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** What the setup checklist shows; re-checked on every resume. */
data class SetupState(
    val permissionsOk: Boolean,
    val a11yOn: Boolean,
    val batteryOk: Boolean,
    val callLogOk: Boolean,
    val notificationsOk: Boolean,
) {
    val requiredOk get() = permissionsOk && a11yOn
}

@Composable
fun rememberSetupState(tick: Int): SetupState {
    val context = LocalContext.current
    return remember(tick) {
        SetupState(
            permissionsOk = SetupChecks.allGranted(context, SetupChecks.requiredPermissions),
            a11yOn = SetupChecks.accessibilityEnabled(context),
            batteryOk = SetupChecks.batteryUnrestricted(context),
            callLogOk = SetupChecks.granted(context, SetupChecks.CALL_LOG),
            notificationsOk = SetupChecks.allGranted(context, SetupChecks.notificationPermission),
        )
    }
}
