package cn.secret.downloader

enum class TaskType { DOWNLOAD, LIVE_PHOTO, GIF }
enum class TaskStatus { QUEUED, PARSING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELED, INTERRUPTED }
enum class TaskStage { PARSING, DOWNLOADING, MERGING, SAVING, READING, ENCODING }

data class Task(
    val id: String,
    val type: TaskType,
    val title: String,
    val sourceUrl: String? = null,
    val coverUri: String? = null,
    val status: TaskStatus = TaskStatus.QUEUED,
    val stage: TaskStage = if (type == TaskType.DOWNLOAD) TaskStage.PARSING else TaskStage.READING,
    val progress: Float? = null,
    val bytesDone: Long = 0,
    val bytesTotal: Long? = null,
    val speedBps: Long? = null,
    val etaSeconds: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val outputUri: String? = null,
    val errorMessage: String? = null,
    val params: String = "{}",
    val attempt: Int = 0,
) {
    val isOngoing: Boolean get() = status != TaskStatus.COMPLETED && status != TaskStatus.CANCELED
}

internal object TaskTransitions {
    fun pause(task: Task): Task = if (task.status in setOf(TaskStatus.QUEUED, TaskStatus.PARSING, TaskStatus.RUNNING)) {
        task.copy(status = TaskStatus.PAUSED, speedBps = null, etaSeconds = null)
    } else task

    fun resume(task: Task): Task = if (task.status == TaskStatus.PAUSED || task.status == TaskStatus.INTERRUPTED) {
        task.copy(status = if (task.progress == null) TaskStatus.QUEUED else TaskStatus.RUNNING,
            finishedAt = null, errorMessage = null)
    } else task

    fun retry(task: Task): Task = if (task.status in setOf(TaskStatus.FAILED, TaskStatus.INTERRUPTED, TaskStatus.CANCELED)) {
        task.copy(status = TaskStatus.QUEUED,
            stage = if (task.type == TaskType.DOWNLOAD) TaskStage.PARSING else TaskStage.READING,
            progress = 0f, bytesDone = 0, speedBps = null, etaSeconds = null,
            finishedAt = null, outputUri = null, errorMessage = null, attempt = task.attempt + 1)
    } else task

    fun cancel(task: Task, now: Long): Task = if (task.isOngoing) {
        task.copy(status = TaskStatus.CANCELED, speedBps = null, etaSeconds = null, finishedAt = now)
    } else task

    fun interruptedOnRestart(task: Task): Task = if (task.status in setOf(
            TaskStatus.QUEUED, TaskStatus.PARSING, TaskStatus.RUNNING, TaskStatus.PAUSED)) {
        task.copy(status = TaskStatus.INTERRUPTED, speedBps = null, etaSeconds = null,
            errorMessage = "应用已重新启动，任务已中断，可继续或重试。")
    } else task
}
