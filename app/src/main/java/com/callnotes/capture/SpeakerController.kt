package com.callnotes.capture

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Tries to move call audio to the loudspeaker through the audio API (optional "auto
 * speaker" setting, for phones whose mic hears the far side too quietly).
 *
 * Whether Android honours this for a cellular call owned by another app varies by device
 * and version, so [isSpeakerOn] reports the real route: success is verified, never assumed.
 */
class SpeakerController(context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private var changedByApi = false

    fun isSpeakerOn(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            audio.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn
        }

    /** A wired, USB or Bluetooth headset is connected — leave the route alone. */
    fun headsetConnected(): Boolean =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADSET_TYPES }

    /** Returns true if the request was accepted (still verify with [isSpeakerOn]). */
    fun requestSpeaker(): Boolean {
        if (isSpeakerOn()) return true
        return try {
            changedByApi = if (Build.VERSION.SDK_INT >= 31) {
                val speaker = audio.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                speaker != null && audio.setCommunicationDevice(speaker)
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = true
                true
            }
            changedByApi
        } catch (e: Exception) {
            false
        }
    }

    /** Undo our API change after the call so other apps aren't left on speaker. */
    fun restore() {
        if (!changedByApi) return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                audio.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = false
            }
        } catch (_: Exception) {
        }
        changedByApi = false
    }

    private companion object {
        val HEADSET_TYPES = buildSet {
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
            if (Build.VERSION.SDK_INT >= 31) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
        }
    }
}
