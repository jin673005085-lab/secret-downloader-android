package cn.secret.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskTest {
    @Test fun engineLinkageFailureBecomesRetryableAndDoesNotReviveStoppedTasks() {
        val parsing = Task("startup", TaskType.DOWNLOAD, "startup", status = TaskStatus.PARSING,
            speedBps = 100, etaSeconds = 5)
        val failed = TaskTransitions.fail(parsing, NoClassDefFoundError("MissingEngineDependency"), 200)
        assertEquals(TaskStatus.FAILED, failed.status)
        assertTrue(failed.errorMessage!!.contains("引擎无法初始化"))
        assertTrue(failed.errorMessage!!.contains("MissingEngineDependency"))
        assertNull(failed.speedBps)
        assertNull(failed.etaSeconds)
        assertEquals(200L, failed.finishedAt)
        assertEquals(TaskStatus.QUEUED, TaskTransitions.retry(failed).status)
        for (status in listOf(TaskStatus.PAUSED, TaskStatus.CANCELED, TaskStatus.COMPLETED, TaskStatus.INTERRUPTED)) {
            val stopped = parsing.copy(status = status)
            assertEquals(stopped, TaskTransitions.fail(stopped, LinkageError("late error"), 300))
        }
    }

    private fun task(status: TaskStatus = TaskStatus.RUNNING) = Task("id", TaskType.DOWNLOAD, "测试任务",
        status = status, stage = TaskStage.DOWNLOADING, progress = .42f, bytesDone = 42,
        bytesTotal = 100, speedBps = 12, etaSeconds = 5, createdAt = 100)

    @Test fun pauseAndResumePreserveProgressWhileRetryStartsANewAttempt() {
        val paused = TaskTransitions.pause(task())
        assertEquals(TaskStatus.PAUSED, paused.status)
        assertEquals(.42f, paused.progress!!, 0f)
        assertNull(paused.speedBps)
        val resumed = TaskTransitions.resume(paused)
        assertEquals(TaskStatus.RUNNING, resumed.status)
        assertEquals(42L, resumed.bytesDone)
        val retried = TaskTransitions.retry(task(TaskStatus.FAILED).copy(errorMessage = "失败", finishedAt = 200))
        assertEquals(TaskStatus.QUEUED, retried.status)
        assertEquals(1, retried.attempt)
        assertEquals(0f, retried.progress!!, 0f)
        assertEquals(0L, retried.bytesDone)
        assertNull(retried.finishedAt)
        assertNull(retried.errorMessage)
    }

    @Test fun processRestorationInterruptsUnfinishedWorkWithoutAutomaticResume() {
        for (status in listOf(TaskStatus.QUEUED, TaskStatus.PARSING, TaskStatus.RUNNING, TaskStatus.PAUSED)) {
            val restored = TaskTransitions.interruptedOnRestart(task(status))
            assertEquals(TaskStatus.INTERRUPTED, restored.status)
            assertEquals(.42f, restored.progress!!, 0f)
            assertNull(restored.speedBps)
            assertNull(restored.etaSeconds)
            assertTrue(restored.errorMessage!!.contains("中断"))
        }
        for (status in listOf(TaskStatus.FAILED, TaskStatus.COMPLETED, TaskStatus.CANCELED, TaskStatus.INTERRUPTED)) {
            val original = task(status)
            assertEquals(original, TaskTransitions.interruptedOnRestart(original))
        }
    }

    @Test fun stoppedOrFinishedTasksDoNotAcceptInvalidControls() {
        for (status in listOf(TaskStatus.PAUSED, TaskStatus.FAILED, TaskStatus.COMPLETED, TaskStatus.CANCELED, TaskStatus.INTERRUPTED)) {
            assertEquals(task(status), TaskTransitions.pause(task(status)))
        }
        assertEquals(task(), TaskTransitions.retry(task()))
        assertEquals(task(TaskStatus.COMPLETED), TaskTransitions.cancel(task(TaskStatus.COMPLETED), 200))
        val canceled = TaskTransitions.cancel(task(), 200)
        assertEquals(TaskStatus.CANCELED, canceled.status)
        assertEquals(200L, canceled.finishedAt)
        assertFalse(canceled.isOngoing)
        assertFalse(task(TaskStatus.COMPLETED).isOngoing)
        assertTrue(task(TaskStatus.FAILED).isOngoing)
        assertTrue(task(TaskStatus.PAUSED).isOngoing)
    }

    @Test fun serializedTasksPreserveOptionalFieldsLongSizesAndParameters() {
        val original = task(TaskStatus.FAILED).copy(
            title = "引号\"与换行\n封面", sourceUrl = "https://example.com/video", coverUri = "content://media/2",
            bytesDone = 5_000_000_000L, bytesTotal = 9_000_000_000L, outputUri = "content://media/3",
            errorMessage = "示例错误", params = "{\"fps\":15,\"cover\":\"第2秒\"}", attempt = 3)
        assertEquals(original, TaskCodec.decode(TaskCodec.encode(original)))
        val unknown = task(TaskStatus.PARSING).copy(progress = null, bytesTotal = null, speedBps = null, etaSeconds = null)
        assertEquals(unknown, TaskCodec.decode(TaskCodec.encode(unknown)))
    }

    @Test fun fakeEngineHasIndeterminateParsingMonotonicProgressAndTerminalCompletion() {
        var current = task(TaskStatus.QUEUED).copy(progress = null, bytesDone = 0)
        current = FakeTaskSource.advance(current, 1_000, 0)
        assertEquals(TaskStatus.PARSING, current.status)
        assertNull(current.progress)
        assertEquals(current, FakeTaskSource.advance(current, 1_200, 200))
        current = FakeTaskSource.advance(current, 2_400, 1_400)
        assertEquals(TaskStatus.RUNNING, current.status)
        var previous = 0f
        repeat(260) {
            current = FakeTaskSource.advance(current, 3_000 + it * 200L, 2_000)
            assertTrue(current.progress!! >= previous)
            assertTrue(current.progress!! in 0f..1f)
            previous = current.progress!!
        }
        assertEquals(TaskStatus.COMPLETED, current.status)
        assertEquals(1f, current.progress!!, 0f)
        assertEquals(current.bytesTotal, current.bytesDone)
        assertNull(current.speedBps)
        assertNull(current.etaSeconds)
        assertEquals(current, FakeTaskSource.advance(current, 100_000, 100_000))
        assertEquals(task(TaskStatus.PAUSED), FakeTaskSource.advance(task(TaskStatus.PAUSED), 100_000, 100_000))
        assertEquals(task(TaskStatus.INTERRUPTED), FakeTaskSource.advance(task(TaskStatus.INTERRUPTED), 100_000, 100_000))
    }

    @Test fun seededDemonstrationIncludesEveryRequestedState() {
        val seeds = FakeTaskSource.initialTasks(1_000_000)
        assertEquals(seeds.size, seeds.map { it.id }.toSet().size)
        assertEquals(TaskStatus.entries.toSet(), seeds.map { it.status }.toSet())
        assertEquals(TaskType.entries.toSet(), seeds.filter { it.status == TaskStatus.COMPLETED }.map { it.type }.toSet())
        assertTrue(seeds.any { it.status == TaskStatus.RUNNING })
        assertTrue(seeds.any { it.status == TaskStatus.PARSING && it.progress == null })
        assertTrue(seeds.any { it.status == TaskStatus.PAUSED })
        assertTrue(seeds.any { it.status == TaskStatus.FAILED && !it.errorMessage.isNullOrBlank() })
        assertTrue(seeds.any { it.status == TaskStatus.COMPLETED && it.progress == 1f })
    }
}
