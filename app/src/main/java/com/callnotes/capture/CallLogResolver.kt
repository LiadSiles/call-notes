package com.callnotes.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat

/**
 * Matches a finished recording to its call-log entry (number, name, real duration).
 * Optional: without READ_CALL_LOG it returns null and the call is saved without them.
 * The name is the one the dialer cached in the log — no contacts permission needed.
 */
object CallLogResolver {

    data class Info(
        val number: String?,
        val name: String?,
        val incoming: Boolean?,
        /** Connected duration in seconds; 0 means the call was never answered. */
        val durationSec: Long,
    )

    fun resolve(context: Context, startedAt: Long, endedAt: Long): Info? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) return null

        val projection = arrayOf(
            CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.DATE,
        )
        // DATE is when the call began (dial or ring), so allow a margin before OFFHOOK.
        val selection = "${CallLog.Calls.DATE} BETWEEN ? AND ?"
        val args = arrayOf((startedAt - 90_000).toString(), endedAt.toString())

        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection, selection, args,
                "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                val type = c.getInt(c.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                Info(
                    number = c.getString(c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)),
                    name = c.getString(c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME))
                        ?.takeIf { it.isNotBlank() },
                    incoming = when (type) {
                        CallLog.Calls.INCOMING_TYPE -> true
                        CallLog.Calls.OUTGOING_TYPE -> false
                        else -> null
                    },
                    durationSec = c.getLong(c.getColumnIndexOrThrow(CallLog.Calls.DURATION)),
                )
            }
        } catch (e: SecurityException) {
            null
        }
    }
}
