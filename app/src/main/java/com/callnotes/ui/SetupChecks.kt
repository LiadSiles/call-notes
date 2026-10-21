package com.callnotes.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.callnotes.capture.CallRecorderService

/** Everything the recording flow depends on, checked fresh every time the app resumes. */
object SetupChecks {

    /** Recording can't work without these. */
    val requiredPermissions: Array<String> = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_PHONE_STATE,
    )

    /** Call details (number, name, direction) — the app records fine without it. */
    const val CALL_LOG = Manifest.permission.READ_CALL_LOG

    /** Recording / problem notifications — optional. Empty below Android 13. */
    val notificationPermission: Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun allGranted(context: Context, permissions: Array<String>): Boolean =
        permissions.all { granted(context, it) }

    fun accessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(context, CallRecorderService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun batteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

    val isXiaomiFamily: Boolean
        get() = listOf(Build.MANUFACTURER, Build.BRAND).any {
            it.equals("xiaomi", true) || it.equals("redmi", true) || it.equals("poco", true)
        }

    fun accessibilitySettings() =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @SuppressLint("BatteryLife") // sideloaded build; Play policy doesn't apply here
    fun batteryRequest(context: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The system list where battery optimisation can be turned back on; falls back to app details. */
    fun openBatterySettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            context.startActivity(appDetails(context))
        }
    }

    /** HyperOS / MIUI "Autostart" screen; falls back to the app's settings page. */
    fun startXiaomiAutostart(context: Context) {
        val autostart = Intent().setComponent(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(autostart)
        } catch (e: Exception) {
            context.startActivity(appDetails(context))
        }
    }
}
