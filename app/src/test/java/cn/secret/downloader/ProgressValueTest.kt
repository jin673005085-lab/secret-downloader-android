package cn.secret.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressValueTest {
    @Test fun sameAttemptDoesNotGoBackwards() {
        val progress = ProgressValue()
        assertTrue(progress.update("a:0", 0.6f))
        assertFalse(progress.update("a:0", 0.2f))
        assertEquals(0.6f, progress.value!!, 0f)
    }

    @Test fun retryAndDifferentTaskResetTheFloor() {
        val progress = ProgressValue()
        progress.update("a:0", 0.8f)
        assertTrue(progress.update("a:1", 0f))
        assertEquals(0f, progress.value!!, 0f)
        progress.update("a:1", 0.5f)
        assertTrue(progress.update("b:0", 0.1f))
        assertEquals(0.1f, progress.value!!, 0f)
    }

    @Test fun unknownStageDoesNotLoseTheKnownFloor() {
        val progress = ProgressValue()
        progress.update("a:0", 0.7f)
        progress.update("a:0", null)
        assertNull(progress.value)
        progress.update("a:0", 0.4f)
        assertEquals(0.7f, progress.value!!, 0f)
    }

    @Test fun completionAlwaysFinishesAndBoundsAreClamped() {
        val progress = ProgressValue()
        progress.update("a:0", -5f)
        assertEquals(0f, progress.value!!, 0f)
        progress.update("a:0", 0.3f)
        progress.update("a:0", null, completed = true)
        assertEquals(1f, progress.value!!, 0f)
        progress.update("a:0", 0f)
        assertEquals(1f, progress.value!!, 0f)
        progress.update("b:0", 5f)
        assertEquals(1f, progress.value!!, 0f)
    }

    @Test fun nonFiniteValuesAreIndeterminate() {
        val progress = ProgressValue()
        progress.update("a:0", Float.NaN)
        assertNull(progress.value)
        progress.update("a:0", Float.POSITIVE_INFINITY)
        assertNull(progress.value)
    }
}
