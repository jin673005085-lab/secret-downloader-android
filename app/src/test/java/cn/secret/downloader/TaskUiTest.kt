package cn.secret.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskUiTest {
    @Test fun historyIncludesCanceledAndKeepsRetryableTasksInProgress() {
        val tasks = TaskStatus.entries.map { Task(it.name, TaskType.DOWNLOAD, it.name, status = it) }
        assertEquals(setOf(TaskStatus.COMPLETED, TaskStatus.CANCELED),
            TaskUi.filter(tasks, true, TaskTypeFilter.ALL).map { it.status }.toSet())
        assertEquals(TaskStatus.entries.toSet() - setOf(TaskStatus.COMPLETED, TaskStatus.CANCELED),
            TaskUi.filter(tasks, false, TaskTypeFilter.ALL).map { it.status }.toSet())
    }

    @Test fun conversionChipIncludesGifAndLivePhoto() {
        val tasks = TaskType.entries.map { Task(it.name, it, it.name) }
        assertEquals(listOf(TaskType.LIVE_PHOTO, TaskType.GIF),
            TaskUi.filter(tasks, false, TaskTypeFilter.CONVERSION).map { it.type })
        assertEquals(listOf(TaskType.DOWNLOAD),
            TaskUi.filter(tasks, false, TaskTypeFilter.DOWNLOAD).map { it.type })
        assertTrue(TaskUi.filter(tasks, true, TaskTypeFilter.CONVERSION).isEmpty())
    }

    @Test fun missingMeasurementsAndLargeDurationsHaveReadableLabels() {
        assertEquals("0 B", TaskUi.bytes(-1))
        assertEquals("速度 --", TaskUi.speed(null))
        assertEquals("剩余 --", TaskUi.eta(null))
        assertEquals("剩余 --", TaskUi.eta(-1))
        assertEquals("即将完成", TaskUi.eta(0))
        assertEquals("剩余 59秒", TaskUi.eta(59))
        assertEquals("剩余 1分1秒", TaskUi.eta(61))
        assertEquals("剩余 25小时1分", TaskUi.eta(90_060))
        assertTrue(TaskUi.bytes(Long.MAX_VALUE).endsWith("GB"))
    }

    @Test fun summariesUseCurrentStageAndKeepErrorsOnOneLine() {
        val task = Task("local", TaskType.GIF, "动画", status = TaskStatus.RUNNING, stage = TaskStage.ENCODING)
        assertEquals("编码中", TaskUi.statusLabel(task))
        assertEquals("失败 · 编码失败", TaskUi.summary(task.copy(status = TaskStatus.FAILED, errorMessage = "编码失败\n日志")))
        assertEquals("已暂停 · 100%", TaskUi.summary(task.copy(status = TaskStatus.PAUSED, progress = 1.2f)))
    }
}
