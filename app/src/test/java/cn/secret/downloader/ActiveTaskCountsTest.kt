package cn.secret.downloader

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveTaskCountsTest {
    @Test fun countsQueuedAndRunningJobsByKind() {
        val counts = ActiveTaskCounts()
        assertEquals(1, counts.start(1, false))
        assertEquals(2, counts.start(2, true))
        assertEquals(3, counts.start(3, false))
        assertEquals(2, counts.downloads)
        assertEquals(1, counts.conversions)
        assertEquals(2, counts.finish(1))
        assertEquals(1, counts.downloads)
        assertEquals(1, counts.conversions)
    }

    @Test fun failedOrRejectedJobCanFinishWithoutAffectingOtherJobs() {
        val counts = ActiveTaskCounts()
        counts.start(1, false)
        counts.start(2, true)
        counts.finish(2)
        assertEquals(1, counts.downloads)
        assertEquals(0, counts.conversions)
        counts.finish(2)
        assertEquals(1, counts.downloads)
    }

    @Test fun shutdownClearsQueuedJobsAndLateWorkerCompletionStaysAtZero() {
        val counts = ActiveTaskCounts()
        counts.start(1, false)
        counts.start(2, true)
        counts.clear()
        assertEquals(0, counts.downloads)
        assertEquals(0, counts.conversions)
        assertEquals(0, counts.finish(1))
        assertEquals(0, counts.finish(2))
        assertEquals(0, counts.downloads)
        assertEquals(0, counts.conversions)
    }

    @Test fun previousServiceCleanupDoesNotChangeNewServiceCounts() {
        val previous = ActiveTaskCounts()
        previous.start(1, false)
        previous.clear()
        val current = ActiveTaskCounts()
        current.start(1, true)
        previous.finish(1)
        assertEquals(0, current.downloads)
        assertEquals(1, current.conversions)
    }
}
