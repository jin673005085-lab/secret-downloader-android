package cn.secret.downloader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.Closeable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class TaskRepository private constructor(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val database = TaskDatabase(context.applicationContext)
    private val records = linkedMapOf<String, Task>()
    private val taskLogs = mutableMapOf<String, List<String>>()
    private val observers = linkedSetOf<(List<Task>) -> Unit>()
    private val dirty = linkedSetOf<String>()
    private val fake = FakeTaskSource(this)
    @Volatile private var loaded = false
    private var fakeRequested = false
    private var flushScheduled = false
    @Volatile private var snapshot = emptyList<Task>()
    @Volatile var persistenceError: String? = null
        private set

    val tasks: List<Task> get() = snapshot
    val isLoaded: Boolean get() = loaded
    fun get(id: String): Task? = snapshot.firstOrNull { it.id == id }
    fun logs(id: String): List<String> = synchronized(taskLogs) { taskLogs[id].orEmpty().toList() }

    private val flush = Runnable { flushPersistence() }

    init {
        worker.execute {
            val restored = try { database.loadAndRestore() } catch (error: Exception) {
                reportPersistenceError(error)
                emptyList()
            }
            onMain {
                restored.forEach { stored ->
                    if (stored.task.id !in records) {
                        records[stored.task.id] = stored.task
                        synchronized(taskLogs) { taskLogs[stored.task.id] = stored.logs }
                    }
                }
                loaded = true
                publish()
                if (fakeRequested) fake.start()
            }
        }
    }

    fun observeAll(observer: (List<Task>) -> Unit): Closeable {
        val closed = AtomicBoolean()
        val listener: (List<Task>) -> Unit = { if (!closed.get()) observer(it) }
        onMain {
            if (!closed.get()) { observers += listener; listener(snapshot) }
        }
        return Closeable {
            closed.set(true)
            onMain { observers -= listener }
        }
    }

    fun observeTask(id: String, observer: (Task?) -> Unit): Closeable {
        var delivered = false
        var previous: Task? = null
        return observeAll { tasks ->
            val task = tasks.firstOrNull { it.id == id }
            if (task == null && !loaded) return@observeAll
            if (!delivered || task != previous) {
                previous = task; delivered = true
                observer(task)
            }
        }
    }

    fun create(type: TaskType, title: String, sourceUrl: String? = null, params: String = "{}"): Task {
        require(title.isNotBlank()) { "任务标题不能为空" }
        JSONObject(params)
        val task = Task(UUID.randomUUID().toString(), type, title.trim(), sourceUrl = sourceUrl, params = params)
        onMain {
            update(listOf(task), "已创建任务，等待执行。")
            launch(task)
        }
        return task
    }

    fun pause(id: String) = command(id, "任务已暂停。", false, TaskTransitions::pause)
    fun resume(id: String) = command(id, "继续任务。", true, TaskTransitions::resume)
    fun retry(id: String) = command(id, "重新尝试，进度已重置。", true, TaskTransitions::retry)
    fun cancel(id: String) = command(id, "任务已取消。", false) { TaskTransitions.cancel(it, System.currentTimeMillis()) }

    private fun command(id: String, message: String, start: Boolean, transform: (Task) -> Task) = onMain {
        records[id]?.let { old ->
            val next = transform(old)
            if (next == old) return@let
            update(listOf(next), message)
            if (!start) DownloadService.stopTask(app, id, deleteFiles = next.status == TaskStatus.CANCELED)
            if (start) launch(next)
        }
    }

    private fun launch(task: Task) {
        if (task.id.startsWith("demo-") || JSONObject(task.params).optBoolean("fakeTask")) return
        try { DownloadService.enqueueTask(app, task.id) }
        catch (error: Exception) { report(task.id, task.attempt) {
            it.copy(status = TaskStatus.FAILED, errorMessage = "任务无法启动：${error.message}", finishedAt = System.currentTimeMillis())
        } }
    }

    /** Worker callbacks are serialized on main; stale attempts and stopped jobs cannot revive a task. */
    internal fun report(id: String, attempt: Int, transform: (Task) -> Task) = onMain {
        records[id]?.takeIf { it.attempt == attempt && it.status in setOf(TaskStatus.QUEUED, TaskStatus.PARSING, TaskStatus.RUNNING) }
            ?.let { update(listOf(transform(it))) }
    }

    fun delete(id: String) {
        onMain {
            DownloadService.stopTask(app, id, deleteFiles = true)
            if (records.remove(id) == null) return@onMain
            dirty -= id
            synchronized(taskLogs) { taskLogs.remove(id) }
            persist { database.delete(id) }
            publish()
        }
    }

    fun startFakeSource() {
        onMain {
            fakeRequested = true
            if (loaded) fake.start()
        }
    }

    internal fun updateFromFake(tasks: List<Task>) = update(tasks)

    private fun update(updates: List<Task>, message: String? = null) {
        var changed = false
        var immediate = false
        updates.forEach { proposed ->
            val old = records[proposed.id]
            if (old == proposed) return@forEach
            val task = if (old?.progress != null && proposed.progress != null && old.attempt == proposed.attempt) {
                proposed.copy(progress = maxOf(old.progress, proposed.progress))
            } else proposed
            records[task.id] = task
            dirty += task.id
            changed = true
            if (old == null || old.status != task.status || old.stage != task.stage) {
                immediate = true
                val text = message ?: describe(task)
                val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                synchronized(taskLogs) { taskLogs[task.id] = (taskLogs[task.id].orEmpty() + "$stamp  $text").takeLast(100) }
            }
        }
        if (!changed) return
        publish()
        if (immediate) flushPersistence() else if (!flushScheduled) {
            flushScheduled = true
            main.postDelayed(flush, 1_500)
        }
    }

    private fun publish() {
        snapshot = records.values.sortedByDescending { it.createdAt }
        observers.toList().forEach { it(snapshot) }
    }

    private fun flushPersistence() {
        main.removeCallbacks(flush)
        flushScheduled = false
        val batch = dirty.mapNotNull { id -> records[id]?.let { StoredTask(it, logs(id)) } }
        dirty.clear()
        if (batch.isNotEmpty()) persist { database.save(batch) }
    }

    private fun persist(action: () -> Unit) {
        worker.execute {
            try { action() } catch (error: Exception) { reportPersistenceError(error) }
        }
    }

    private fun reportPersistenceError(error: Exception) {
        persistenceError = error.message ?: "任务记录保存失败"
        Log.e("TaskRepository", "任务数据库操作失败", error)
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    private fun describe(task: Task): String = when (task.status) {
        TaskStatus.QUEUED -> "等待执行。"
        TaskStatus.PARSING -> "正在解析，当前进度未知。"
        TaskStatus.RUNNING -> when (task.stage) {
            TaskStage.PARSING -> "正在解析。"
            TaskStage.DOWNLOADING -> "正在下载。"
            TaskStage.MERGING -> "正在合并。"
            TaskStage.SAVING -> "正在保存。"
            TaskStage.READING -> "正在读取视频。"
            TaskStage.ENCODING -> "正在编码。"
        }
        TaskStatus.PAUSED -> "任务已暂停。"
        TaskStatus.COMPLETED -> "任务已完成。"
        TaskStatus.FAILED -> task.errorMessage ?: "任务失败。"
        TaskStatus.CANCELED -> "任务已取消。"
        TaskStatus.INTERRUPTED -> "任务已中断，等待手动继续。"
    }

    companion object {
        @Volatile private var instance: TaskRepository? = null
        fun get(context: Context): TaskRepository = instance ?: synchronized(this) {
            instance ?: TaskRepository(context.applicationContext).also { instance = it }
        }
    }
}
