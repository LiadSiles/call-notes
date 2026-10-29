package com.callnotes.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeathTest {

    private fun death(stage: String) = ProcessingLog.Death(stage, nativeCrash = false, memory = true)

    @Test fun summaryStageNamesItsCall() {
        assertEquals(40L, death("[summary] call 40: summarising precisely (3 questions per part) · 2 lines, 202 chars").summaryCall)
    }

    @Test fun otherStagesAreNotSummaries() {
        assertNull(death("[transcribe] end · transcribed 0, failed 0").summaryCall)
        assertNull(death("[transcribe] call 40: preparing audio").summaryCall)
        assertNull(death("[summary] loading analysis model gemma-4-e4b").summaryCall)
    }
}
