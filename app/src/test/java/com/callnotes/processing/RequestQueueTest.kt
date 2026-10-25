package com.callnotes.processing

import com.callnotes.util.Notifications
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestQueueTest {

    @After fun clear() = ProcessingStatus.clearQueue()

    private fun ids(stage: Stage) = ProcessingStatus.queue.value.filter { it.stage == stage }
        .map { if (it.isMemo) "m${it.memoId}" else "c${it.callId}" }

    @Test fun firstAskedFirstServed() {
        ProcessingStatus.enqueue(Request(Stage.TRANSCRIBE, callId = 7, at = 100))
        ProcessingStatus.enqueue(Request(Stage.TRANSCRIBE, callId = 3, at = 200))
        ProcessingStatus.enqueue(Request(Stage.TRANSCRIBE, memoId = 5, at = 300))
        assertEquals(listOf("c7", "c3", "m5"), ids(Stage.TRANSCRIBE))
        assertEquals(7L, ProcessingStatus.first(Stage.TRANSCRIBE)?.callId)
    }

    @Test fun askingAgainKeepsThePlace() {
        ProcessingStatus.enqueue(Request(Stage.TRANSCRIBE, callId = 1, at = 100))
        ProcessingStatus.enqueue(Request(Stage.TRANSCRIBE, callId = 2, at = 200))
        val before = ProcessingStatus.enqueued
        ProcessingStatus.enqueue(Request(Stage.TRANSCRIBE, callId = 1, at = 300))
        assertEquals(listOf("c1", "c2"), ids(Stage.TRANSCRIBE))
        assertEquals(before, ProcessingStatus.enqueued)
    }

    @Test fun aVoiceNoteFoundLaterTakesItsRecordedPlace() {
        // After a restart the note is read back from the database with its recording time.
        ProcessingStatus.enqueue(Request(Stage.TRANSCRIBE, callId = 1, at = 500))
        ProcessingStatus.enqueue(Request(Stage.TRANSCRIBE, memoId = 9, at = 400))
        assertEquals(listOf("m9", "c1"), ids(Stage.TRANSCRIBE))
    }

    @Test fun placesCountPerStage() {
        ProcessingStatus.enqueue(Request(Stage.TRANSCRIBE, callId = 1, at = 100))
        ProcessingStatus.enqueue(Request(Stage.SUMMARY, callId = 2, at = 150))
        ProcessingStatus.enqueue(Request(Stage.TRANSCRIBE, callId = 3, at = 200))
        val q = ProcessingStatus.queue.value
        assertEquals(2, ProcessingStatus.placeOf(q, Stage.TRANSCRIBE, 3))
        assertEquals(1, ProcessingStatus.placeOf(q, Stage.SUMMARY, 2))
        assertNull(ProcessingStatus.placeOf(q, Stage.SUMMARY, 3))
    }

    @Test fun cancellingACallRemovesBothStagesButNotANoteWithTheSameNumber() {
        ProcessingStatus.enqueue(Request(Stage.TRANSCRIBE, callId = 4, at = 100))
        ProcessingStatus.enqueue(Request(Stage.SUMMARY, callId = 4, at = 110))
        ProcessingStatus.enqueue(Request(Stage.TRANSCRIBE, memoId = 4, at = 120))
        ProcessingStatus.removeCall(4)
        assertEquals(listOf("m4"), ids(Stage.TRANSCRIBE))
        assertEquals(emptyList<String>(), ids(Stage.SUMMARY))
        assertEquals(1, ProcessingStatus.placeOfMemo(ProcessingStatus.queue.value, Stage.TRANSCRIBE, 4))
    }

    @Test fun takenRequestLeavesTheQueue() {
        val r = Request(Stage.SUMMARY, callId = 8, at = 100)
        ProcessingStatus.enqueue(r)
        ProcessingStatus.dequeue(r.copy(at = 999)) // same request, whatever its time
        assertNull(ProcessingStatus.first(Stage.SUMMARY))
    }

    @Test fun everyRequestHasItsOwnNotificationRow() {
        val rows = listOf(1L, 2L, 3L).flatMap { n ->
            Stage.entries.flatMap { st -> listOf(Notifications.rowId(st, n, 0), Notifications.rowId(st, 0, n)) }
        }
        assertEquals(rows.size, rows.toSet().size)
        // Clear of the other notification ids (recording, problems, tasks).
        assertTrue(rows.all { it < 0 })
    }
}
