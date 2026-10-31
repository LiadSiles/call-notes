package com.callnotes.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class DeathTest {

    private fun death(stage: String) = ProcessingLog.Death(stage, nativeCrash = false, memory = true)

    @Test fun summaryStageNamesItsCall() {
        assertEquals(40L, death("[summary] call 40: summarising precisely (3 questions per part) · 2 lines, 202 chars").summaryCall)
    }

    @Test fun gpuStages() {
        assertTrue(death("[summary] loading analysis model on the GPU").onGpu)
        assertTrue(death("[summary] call 40: summarising on the GPU precisely (3 questions per part) · 2 lines, 202 chars").onGpu)
        assertEquals(40L, death("[summary] call 40: summarising on the GPU quickly · 2 lines").summaryCall)
        assertFalse(death("[summary] analysis model loaded in 1.1 s on the CPU · free RAM 3.6 GB").onGpu)
        assertFalse(death("[summary] call 40: summarising precisely (3 questions per part)").onGpu)
    }

    @Test fun cleanupsAreRecognised() {
        // Exit descriptions from the owner's log.
        assertTrue(ProcessingLog.isCleanup("SwipeUpClean"))
        assertTrue(ProcessingLog.isCleanup("OneKeyClean"))
        assertTrue(ProcessingLog.isCleanup("[FORCE STOP] stop com.callnotes due to from process:com.miui.securitycenter"))
        assertFalse(ProcessingLog.isCleanup("[FORCE STOP] stop com.callnotes due to used too many pss resource,pss used:8524200kb,pss threshold:6291456kb"))
        assertFalse(ProcessingLog.isCleanup("stop com.callnotes due to installPackageLI"))
        assertFalse(ProcessingLog.isCleanup(null))
    }

    @Test fun otherStagesAreNotSummaries() {
        assertNull(death("[transcribe] end · transcribed 0, failed 0").summaryCall)
        assertNull(death("[transcribe] call 40: preparing audio").summaryCall)
        assertNull(death("[summary] loading analysis model gemma-4-e4b").summaryCall)
    }
}
