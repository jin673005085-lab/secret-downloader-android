package cn.secret.downloader

import java.util.Locale
import kotlin.math.roundToInt

internal enum class TaskTypeFilter { ALL, DOWNLOAD, CONVERSION }

internal object TaskUi {
    fun typeLabel(type: TaskType) = when (type) {
        TaskType.DOWNLOAD -> "下载"
        TaskType.LIVE_PHOTO -> "实况"
        TaskType.GIF -> "GIF"
    }

    fun statusLabel(task: Task): String = when (task.status) {
        TaskStatus.QUEUED -> "排队中"
        TaskStatus.PARSING -> "解析中"
        TaskStatus.RUNNING -> when (task.stage) {
            TaskStage.PARSING -> "解析中"
            TaskStage.DOWNLOADING -> "下载中"
            TaskStage.MERGING -> "合并中"
            TaskStage.SAVING -> "保存中"
            TaskStage.READING -> "读取中"
            TaskStage.ENCODING -> "编码中"
        }
        TaskStatus.PAUSED -> "已暂停"
        TaskStatus.COMPLETED -> "已完成"
        TaskStatus.FAILED -> "失败"
        TaskStatus.CANCELED -> "已取消"
        TaskStatus.INTERRUPTED -> "已中断"
    }

    fun bytes(value: Long): String {
        val safe = value.coerceAtLeast(0)
        val divisor = when {
            safe >= 1_073_741_824 -> 1_073_741_824L to "GB"
            safe >= 1_048_576 -> 1_048_576L to "MB"
            safe >= 1_024 -> 1_024L to "KB"
            else -> return "$safe B"
        }
        return String.format(Locale.getDefault(), "%.1f %s", safe.toDouble() / divisor.first, divisor.second)
    }

    fun speed(value: Long?) = value?.let { "${bytes(it)}/s" } ?: "速度 --"

    fun eta(value: Long?): String = when {
        value == null || value < 0 -> "剩余 --"
        value == 0L -> "即将完成"
        value >= 3_600 -> "剩余 ${value / 3_600}小时${value % 3_600 / 60}分"
        value >= 60 -> "剩余 ${value / 60}分${value % 60}秒"
        else -> "剩余 ${value}秒"
    }

    fun summary(task: Task): String {
        val state = statusLabel(task)
        return when (task.status) {
            TaskStatus.RUNNING -> "$state · ${speed(task.speedBps)} · ${eta(task.etaSeconds)}"
            TaskStatus.PAUSED -> "$state · ${task.progress?.let { "${(it.coerceIn(0f, 1f) * 100).roundToInt()}%" } ?: "等待继续"}"
            TaskStatus.COMPLETED -> "$state · ${bytes(task.bytesTotal ?: task.bytesDone)}"
            TaskStatus.FAILED -> "$state · ${task.errorMessage?.lineSequence()?.firstOrNull().orEmpty().ifBlank { "点击重试" }}"
            TaskStatus.INTERRUPTED -> "$state · 可继续或重试"
            else -> state
        }
    }

    fun color(task: Task, theme: UiTheme) = when (task.status) {
        TaskStatus.PAUSED, TaskStatus.INTERRUPTED -> theme.warning
        TaskStatus.FAILED -> theme.error
        TaskStatus.COMPLETED -> theme.success
        TaskStatus.CANCELED -> theme.secondary
        else -> theme.primaryText
    }

    fun filter(tasks: List<Task>, completed: Boolean, type: TaskTypeFilter): List<Task> = tasks.filter {
        it.isOngoing != completed && when (type) {
            TaskTypeFilter.ALL -> true
            TaskTypeFilter.DOWNLOAD -> it.type == TaskType.DOWNLOAD
            TaskTypeFilter.CONVERSION -> it.type != TaskType.DOWNLOAD
        }
    }
}
