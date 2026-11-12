package cn.secret.downloader

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.ceil

/** Demo-only engine: no network, conversion, or output file is produced. */
class FakeTaskSource(private val repository: TaskRepository) {
    private val handler = Handler(Looper.getMainLooper())
    private val parsingSince = mutableMapOf<String, Long>()
    private var started = false
    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val elapsed = SystemClock.elapsedRealtime()
            val activeKeys = repository.tasks.map { "${it.id}:${it.attempt}" }.toSet()
            parsingSince.keys.retainAll(activeKeys)
            repository.updateFromFake(repository.tasks.map { task ->
                if (!task.id.startsWith("demo-") && !org.json.JSONObject(task.params).optBoolean("fakeTask")) return@map task
                val key = "${task.id}:${task.attempt}"
                val since = parsingSince.getOrPut(key) { elapsed }
                advance(task, now, elapsed - since)
            })
            handler.postDelayed(this, 200)
        }
    }

    fun start() {
        if (started) return
        started = true
        handler.post(tick)
    }

    companion object {
        internal fun advance(task: Task, now: Long, parsingElapsedMs: Long): Task {
            if (task.status == TaskStatus.QUEUED) {
                if (task.id == "demo-queued" && parsingElapsedMs < 15_000) return task
                return task.copy(status = TaskStatus.PARSING, progress = null,
                    stage = if (task.type == TaskType.DOWNLOAD) TaskStage.PARSING else TaskStage.READING)
            }
            if (task.status == TaskStatus.PARSING) {
                val wait = if (task.id == "demo-parse") 8_000 else 1_200
                if (parsingElapsedMs < wait) return task
                return task.copy(status = TaskStatus.RUNNING, progress = task.progress ?: 0f,
                    stage = if (task.type == TaskType.DOWNLOAD) TaskStage.DOWNLOADING else TaskStage.ENCODING)
            }
            if (task.status != TaskStatus.RUNNING) return task
            val progress = ((task.progress ?: 0f) + .004f).coerceAtMost(1f)
            val total = task.bytesTotal ?: 24_000_000L
            if (progress >= 1f) return task.copy(status = TaskStatus.COMPLETED, stage = TaskStage.SAVING,
                progress = 1f, bytesDone = total, bytesTotal = total, speedBps = null, etaSeconds = null,
                finishedAt = now, errorMessage = null)
            val stage = when {
                progress >= .96f -> TaskStage.SAVING
                task.type == TaskType.DOWNLOAD && progress >= .86f -> TaskStage.MERGING
                task.type == TaskType.DOWNLOAD -> TaskStage.DOWNLOADING
                progress < .06f -> TaskStage.READING
                else -> TaskStage.ENCODING
            }
            return task.copy(progress = progress, stage = stage, bytesTotal = total,
                bytesDone = (total * progress).toLong(), speedBps = (total * .004f * 5).toLong(),
                etaSeconds = ceil((1 - progress) / .004 * .2).toLong())
        }

        internal fun initialTasks(now: Long): List<Task> = listOf(
            Task("demo-queued", TaskType.DOWNLOAD, "示例 · 等待下载的森林清晨", createdAt = now - 5_000),
            Task("demo-download", TaskType.DOWNLOAD, "示例 · 山间日落", status = TaskStatus.RUNNING,
                stage = TaskStage.DOWNLOADING, progress = .28f, bytesDone = 22_400_000,
                bytesTotal = 80_000_000, speedBps = 1_600_000, etaSeconds = 36, createdAt = now - 10_000),
            Task("demo-parse", TaskType.DOWNLOAD, "示例 · 正在解析分享链接", status = TaskStatus.PARSING,
                stage = TaskStage.PARSING, createdAt = now - 20_000),
            Task("demo-paused", TaskType.LIVE_PHOTO, "示例 · 窗边的猫咪", status = TaskStatus.PAUSED,
                stage = TaskStage.ENCODING, progress = .46f, bytesDone = 7_360_000,
                bytesTotal = 16_000_000, createdAt = now - 30_000, params = "{\"cover\":\"视频第 2 秒\"}"),
            Task("demo-failed", TaskType.GIF, "示例 · 海浪循环动图", status = TaskStatus.FAILED,
                stage = TaskStage.ENCODING, progress = .63f, bytesDone = 5_040_000,
                bytesTotal = 8_000_000, createdAt = now - 40_000, finishedAt = now - 35_000,
                errorMessage = "示例编码失败，可以重试体验完整流程。", params = "{\"fps\":15,\"width\":480}"),
            Task("demo-completed", TaskType.DOWNLOAD, "示例 · 城市夜景", status = TaskStatus.COMPLETED,
                stage = TaskStage.SAVING, progress = 1f, bytesDone = 18_000_000, bytesTotal = 18_000_000,
                createdAt = now - 60_000, finishedAt = now - 45_000),
            Task("demo-interrupted", TaskType.GIF, "示例 · 被中断的车窗风景", status = TaskStatus.INTERRUPTED,
                stage = TaskStage.ENCODING, progress = .37f, bytesDone = 3_700_000, bytesTotal = 10_000_000,
                createdAt = now - 50_000, errorMessage = "示例任务已中断，可以继续或重新尝试。"),
            Task("demo-canceled", TaskType.DOWNLOAD, "示例 · 已取消的街头片段", status = TaskStatus.CANCELED,
                stage = TaskStage.DOWNLOADING, progress = .21f, bytesDone = 4_200_000, bytesTotal = 20_000_000,
                createdAt = now - 80_000, finishedAt = now - 70_000),
            Task("demo-gif-completed", TaskType.GIF, "示例 · 完成的云朵动图", status = TaskStatus.COMPLETED,
                stage = TaskStage.SAVING, progress = 1f, bytesDone = 3_000_000, bytesTotal = 3_000_000,
                createdAt = now - 90_000, finishedAt = now - 85_000, params = "{\"fps\":15,\"width\":480}"),
            Task("demo-live-completed", TaskType.LIVE_PHOTO, "示例 · 完成的花园实况", status = TaskStatus.COMPLETED,
                stage = TaskStage.SAVING, progress = 1f, bytesDone = 12_000_000, bytesTotal = 12_000_000,
                createdAt = now - 100_000, finishedAt = now - 95_000, params = "{\"cover\":\"视频第 1 秒\"}"),
        )
    }
}
