package com.callnotes.capture

import com.callnotes.data.CallEntity
import com.callnotes.data.CallKind
import com.callnotes.data.CallStatus
import com.callnotes.data.SpeakerResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingCallsTest {

    private fun call(number: String?, name: String?, path: String?) = CallEntity(
        startedAt = 1_792_580_000_000, endedAt = 1_792_580_412_000, incoming = true, number = number,
        contactName = name, filePath = path, audioSource = "voice_recognition", peakLevel = 21_000,
        silenced = false, speakerResult = SpeakerResult.ON, speakerMethod = "api",
        status = if (path != null) CallStatus.RECORDED else CallStatus.RECORD_FAILED,
        kind = CallKind.CELLULAR, foreground = true,
    )

    @Test fun aCallSurvivesTheTripBetweenProcesses() {
        val c = call("050-1234567", "דני כהן", "/data/user/0/com.callnotes/files/recordings/call_1.wav")
        // Through text, as written to and read from the file.
        assertEquals(c, PendingCalls.fromJson(JSONObject(PendingCalls.toJson(c).toString())))
    }

    @Test fun missingValuesStayMissing() {
        val c = call(null, null, null)
        assertEquals(c, PendingCalls.fromJson(JSONObject(PendingCalls.toJson(c).toString())))
    }
}
