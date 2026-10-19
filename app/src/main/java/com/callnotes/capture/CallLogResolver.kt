package com.callnotes.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** Matches a finished recording to its call-log entry (number, name, real duration). */
object CallLogResolver {

    data class Info(
        val number: String?,
        val name: String?,
        val incoming: Boolean?,
        /** Connected duration in seconds; 0 means the call was never answered. */
        val durationSec: Long,
    )

    fun resolve(context: Context, startedAt: Long, endedAt: Long): Info? {
        if (!granted(context, Manifest.permission.READ_CALL_LOG)) return null

        val projection = arrayOf(
            CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.DATE,
        )
        // DATE is when the call began (dial or ring), so allow a margin before OFFHOOK.
        val selection = "${CallLog.Calls.DATE} BETWEEN ? AND ?"
        val args = arrayOf((startedAt - 90_000).toString(), endedAt.toString())

        val info = try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection, selection, args,
                "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                val type = c.getInt(c.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                Info(
                    number = c.getString(c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)),
                    name = c.getString(c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)),
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
        } ?: return null

        val name = info.name?.takeIf { it.isNotBlank() } ?: lookupContact(context, info.number)
        return info.copy(name = name)
    }

    private fun lookupContact(context: Context, number: String?): String? {
        if (number.isNullOrBlank() || !granted(context, Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
        )
        return try {
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
