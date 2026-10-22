package com.callnotes.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskMatcherTest {
    private fun c(t: String, owner: String? = "me", due: String? = null) = TaskMatcher.Candidate(t, owner, due)

    @Test fun rewordedTaskMatches() {
        assertTrue(TaskMatcher.same(c("לשלוח את הצעת המחיר לדני"), c("לשלוח הצעת מחיר לדני")))
        assertTrue(TaskMatcher.same(c("להזמין את הטכנאי למזגן"), c("הזמנת טכנאי מזגן", due = "2026-10-25")
            .copy(title = "להזמין טכנאי למזגן")))
    }

    @Test fun differentTasksDontMatch() {
        assertFalse(TaskMatcher.same(c("לשלוח את הצעת המחיר לדני"), c("לשלוח את החשבונית לדני")))
        assertFalse(TaskMatcher.same(c("להתקשר לרופא"), c("להתקשר לבנק")))
    }

    @Test fun differentOwnerOrDateDontMatch() {
        assertFalse(TaskMatcher.same(c("לשלוח הצעת מחיר", "me"), c("לשלוח הצעת מחיר", "other")))
        assertFalse(TaskMatcher.same(c("לשלוח הצעת מחיר", due = "2026-10-20"), c("לשלוח הצעת מחיר", due = "2026-10-27")))
        assertTrue(TaskMatcher.same(c("לשלוח הצעת מחיר", due = null), c("לשלוח הצעת מחיר", due = "2026-10-27")))
    }

    @Test fun findsFirstOriginal() {
        val open = listOf(c("לקנות מתנה לאמא"), c("לשלוח הצעת מחיר ללקוח"))
        assertEquals(1, TaskMatcher.findOriginal(c("שליחת הצעת מחיר ללקוח").copy(title = "לשלוח את הצעת המחיר ללקוח"), open))
        assertNull(TaskMatcher.findOriginal(c("לתקן את הברז"), open))
    }
}
