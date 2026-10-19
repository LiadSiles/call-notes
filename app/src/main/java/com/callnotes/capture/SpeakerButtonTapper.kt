package com.callnotes.capture

import android.accessibilityservice.AccessibilityService
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Fallback when the audio API can't switch the route: tap the speaker toggle on
 * the in-call screen that is already showing. It never opens or navigates to any
 * screen — if the call screen isn't in front, it does nothing.
 */
object SpeakerButtonTapper {

    private val LABELS = listOf("רמקול", "דיבורית", "Speaker", "speaker")

    /** Returns true if a speaker toggle was found and tapped. */
    fun tap(service: AccessibilityService): Boolean {
        // Only the default dialer's windows: the in-call screen belongs to it, and this
        // keeps a "speaker" label in any other app from ever being touched.
        val dialer = service.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
            ?: return false
        val roots = buildList {
            service.rootInActiveWindow?.let { add(it) }
            service.windows.mapNotNullTo(this) { it.root }
        }.filter { it.packageName?.toString() == dialer }
        for (root in roots) {
            for (label in LABELS) {
                val candidates = root.findAccessibilityNodeInfosByText(label)
                for (node in candidates) {
                    if (node.isCheckable && node.isChecked) return false // already on
                    val target = clickableSelfOrParent(node) ?: continue
                    if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                }
            }
        }
        return false
    }

    private fun clickableSelfOrParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 4) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
            depth++
        }
        return null
    }
}
