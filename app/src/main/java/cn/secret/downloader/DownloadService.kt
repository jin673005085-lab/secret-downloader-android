package cn.secret.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

/** Executes repository commands; notifications observe the same task snapshots as the UI. */
class DownloadService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var repository: TaskRepository
    private var subscription: java.io.Closeable? = null
    private val notified = mutableSetOf<String>()
    private var lastNotification = 0L
    private var latestStartId = 0

    override fun onCreate() {
        super.onCreate()
        activeService = this
        repository = TaskRepository.get(this)
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "下载与转换任务", NotificationManager.IMPORTANCE_LOW))
        subscription = repository.observeAll { all ->
            val manager = getSystemService(NotificationManager::class.java)
            all.filter { it.id in submitted && it.status in setOf(TaskStatus.COMPLETED, TaskStatus.FAILED) }.forEach { task ->
                if (notified.add("${task.id}:${task.attempt}:${task.status}")) {
                    manager.notify((task.id.hashCode() and 0x7fffffff) or 0x100, notification(task, false))
                    submitted.remove(task.id)
                }
            }
            val current = all.firstOrNull { jobs.containsKey(it.id) && it.status in setOf(TaskStatus.PARSING, TaskStatus.RUNNING) }
                ?: all.firstOrNull { jobs.containsKey(it.id) }
            if (current != null && System.currentTimeMillis() - lastNotification >= 700) {
                lastNotification = System.currentTimeMillis()
                status = TaskUi.summary(current)
                manager.notify(NOTIFICATION_ID, notification(current, true))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        pendingStarts.updateAndGet { (it - 1).coerceAtLeast(0) }
        val id = intent?.getStringExtra(EXTRA_TASK) ?: return START_NOT_STICKY.also { stopSelf(startId) }
        val task = repository.get(id) ?: return START_NOT_STICKY.also { stopSelf(startId) }
        val initial = notification(task, true)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION_ID, initial)
        if (jobs.containsKey(id)) return START_NOT_STICKY
        submitted += id
        jobTypes[id] = task.type
        lateinit var future: FutureTask<Unit>
        future = FutureTask {
            try { executeTask(task) }
            finally {
                if (jobs.remove(id, future)) jobTypes.remove(id)
                main.post { stopIfIdle() }
            }
        }
        jobs[id] = future
        executor.execute(future)
        return START_NOT_STICKY
    }

    private fun executeTask(task: Task) {
        val directory = File(jobDirectory(this, task.id), "attempt-${task.attempt}")
        var keepFiles = false
        try {
            check(directory.isDirectory || directory.mkdirs()) { "无法创建任务临时目录" }
            check(cacheDir.usableSpace > 16 * 1024 * 1024) { "存储空间不足，请清理空间后重试" }
            val params = JSONObject(task.params)
            val outputs = mutableMapOf<String, EngineOutput>()
            val files = if (task.type == TaskType.DOWNLOAD) {
                val selection = params.optJSONArray("mediaSelection")?.let { array ->
                    (0 until array.length()).map { array.getString(it) }
                }
                DownloadEngine.downloadJob(this, checkNotNull(task.sourceUrl), directory, DownloadSettings.load(this), task.id,
                    onProgress = { reportProgress(task, it) }, onOutput = { outputs[it.file.absolutePath] = it },
                    onStatus = { Log.i("DownloadService", it) }, selection = selection)
            } else {
                reportProgress(task, EngineProgress(TaskStage.READING))
                val input = File(directory, "source-video")
                if (!input.isFile || input.length() == 0L) copyInput(params.getString("sourceUri"), input)
                checkRunning(task)
                val output = if (task.type == TaskType.GIF) {
                    GifConverter.convert(this, input, File(directory, "gif_${task.id.take(8)}.gif").apply { delete() }, task.params,
                        onProgress = { reportProgress(task, EngineProgress(TaskStage.ENCODING, it)) })
                } else {
                    val cover = params.takeUnless { it.isNull("coverUri") }?.optString("coverUri")
                        ?.takeIf { it.isNotBlank() && it != "null" }?.let { address ->
                        File(directory, "selected-cover").also { copyInput(address, it) }
                    }
                    val output = File(directory, "live_${task.id.take(8)}_MP.jpg").apply { delete() }
                    MotionPhotoConverter.convert(this, input, output, cover,
                        frameTimestampUs = params.optLong("coverTimestampMs").coerceAtLeast(0) * 1000,
                        onStatus = { line -> reportProgress(task, EngineProgress(
                            if (line.contains("读取")) TaskStage.READING else TaskStage.ENCODING,
                            Regex("(\\d+)%").find(line)?.groupValues?.get(1)?.toFloatOrNull()?.div(100f))) },
                        onPrepared = { image, motion ->
                            val savedCover = File(directory, "work-cover.jpg").also { image.copyTo(it, overwrite = true) }
                            val savedMotion = File(directory, "work-motion.mp4").also { motion.copyTo(it, overwrite = true) }
                            outputs[output.absolutePath] = EngineOutput(output, motionVideo = savedMotion, cover = savedCover)
                        })
                }
                listOf(output)
            }
            check(files.isNotEmpty()) { "没有找到可保存的媒体文件" }
            val published = mutableListOf<TaskOutput>()
            var publishedBytes = 0L
            files.forEachIndexed { index, original ->
                checkRunning(task)
                val info = outputs[original.absolutePath]
                val file = if (info?.isGif == true && !original.extension.equals("gif", true)) {
                    GifConverter.convert(this, original, File(directory, "${original.nameWithoutExtension}.gif").apply { delete() },
                        JSONObject().put("fps", 15).put("width", 480).put("endMs", 15_000).toString(),
                        onProgress = { reportProgress(task, EngineProgress(TaskStage.ENCODING, it)) })
                } else original
                reportProgress(task, EngineProgress(TaskStage.SAVING))
                // Journal saved attachments so resuming a multi-file download cannot publish them twice.
                val journal = File(directory, "published-$index.json")
                val item = if (journal.isFile) MediaCodec.decode(journal.readText()) else {
                    val result = MediaPublisher.publishResult(this, file)
                    try {
                        MediaOutputIndexer.create(this, task, file, result, info, index).also {
                            val pending = File(directory, "published-$index.tmp")
                            pending.writeText(MediaCodec.encode(it)); check(pending.renameTo(journal)) { "无法保存文件入库记录" }
                        }
                    } catch (error: Exception) {
                        // An unjournaled file would otherwise be duplicated on retry.
                        runCatching { contentResolver.delete(result.uri, null, null) }
                        File(filesDir, "works/${task.id}-${task.attempt}-$index").deleteRecursively()
                        throw error
                    }
                }
                try { checkRunning(task) } catch (stopped: InterruptedException) {
                    val latest = repository.get(task.id)
                    if (latest == null || latest.attempt != task.attempt || latest.status == TaskStatus.CANCELED) {
                        runCatching { contentResolver.delete(Uri.parse(item.localUri), null, null) }
                        File(filesDir, "works/${item.id}").deleteRecursively()
                    }
                    throw stopped
                }
                val fromFile = TaskOutputKind.fromMime(MediaPublisher.mimeType(file.extension))
                val kind = if (item.type == MediaType.LIVE_PHOTO) TaskOutputKind.LIVE_PHOTO else fromFile
                val indexed = kind != TaskOutputKind.IMAGE
                if (indexed) {
                    val media = MediaRepository.get(this)
                    main.post {
                        val latest = repository.get(task.id)
                        if (latest?.attempt == task.attempt && latest.status in setOf(TaskStatus.QUEUED, TaskStatus.PARSING, TaskStatus.RUNNING, TaskStatus.COMPLETED)) media.add(item)
                        else if (latest == null || latest.attempt != task.attempt || latest.status == TaskStatus.CANCELED) cleanup.execute {
                            runCatching { contentResolver.delete(Uri.parse(item.localUri), null, null) }
                            File(filesDir, "works/${item.id}").deleteRecursively()
                        }
                    }
                }
                published += TaskOutput(checkNotNull(item.localUri), file.name, kind, item.coverUri, item.id.takeIf { indexed })
                publishedBytes += item.sizeBytes
            }
            val first = published.first()
            repository.report(task.id, task.attempt) { it.copy(status = TaskStatus.COMPLETED, stage = TaskStage.SAVING,
                progress = 1f, bytesDone = publishedBytes, bytesTotal = publishedBytes,
                speedBps = null, etaSeconds = null, finishedAt = System.currentTimeMillis(), outputUri = first.uri, coverUri = first.coverUri, outputs = published.toList()) }
        } catch (error: Throwable) {
            val current = repository.get(task.id)
            keepFiles = current?.status != TaskStatus.CANCELED && (current?.status in setOf(TaskStatus.PAUSED, TaskStatus.INTERRUPTED) ||
                Thread.currentThread().isInterrupted || error is InterruptedException || error is DownloadCanceledException)
            if (!Thread.currentThread().isInterrupted && error !is InterruptedException && error !is DownloadCanceledException &&
                current?.status !in setOf(TaskStatus.PAUSED, TaskStatus.CANCELED, TaskStatus.INTERRUPTED)) {
                Log.e("DownloadService", "Task ${task.id} failed", error)
                repository.report(task.id, task.attempt) { TaskTransitions.fail(it, error, System.currentTimeMillis()) }
                keepFiles = task.type == TaskType.DOWNLOAD
            }
            if (error is VirtualMachineError || error is ThreadDeath) throw error
        } finally { if (!keepFiles) directory.deleteRecursively() }
    }

    private var lastProgressAt = 0L
    private var lastStage: TaskStage? = null
    private fun reportProgress(task: Task, event: EngineProgress) {
        checkRunning(task)
        val now = android.os.SystemClock.elapsedRealtime()
        if (event.progress != 1f && event.stage == lastStage && now - lastProgressAt < 200) return
        lastStage = event.stage; lastProgressAt = now
        repository.report(task.id, task.attempt) { it.copy(
            status = if (event.stage == TaskStage.PARSING) TaskStatus.PARSING else TaskStatus.RUNNING,
            stage = event.stage, progress = event.progress, bytesDone = event.bytesDone,
            bytesTotal = event.bytesTotal, speedBps = event.speedBps, etaSeconds = event.etaSeconds) }
    }

    private fun checkRunning(task: Task) {
        if (Thread.currentThread().isInterrupted || repository.get(task.id)?.let {
                it.attempt != task.attempt || it.status in setOf(TaskStatus.PAUSED, TaskStatus.CANCELED, TaskStatus.INTERRUPTED)
            } != false) throw InterruptedException("任务已停止")
    }

    private fun copyInput(address: String, destination: File) {
        val uri = Uri.parse(address)
        val input = when (uri.scheme) {
            "content", "android.resource" -> contentResolver.openInputStream(uri)
            "file" -> File(checkNotNull(uri.path)).takeIf { file ->
                listOf(cacheDir, filesDir, getExternalFilesDir(null)).filterNotNull().any { root -> file.canonicalPath.startsWith(root.canonicalPath + File.separator) }
            }?.inputStream()
            else -> null
        } ?: error("无法读取所选视频或封面，请重新选择文件")
        try { input.use { source -> destination.outputStream().use { target ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("读取已取消")
                val count = source.read(buffer); if (count < 0) break
                target.write(buffer, 0, count)
            }
        } } } catch (error: Exception) { destination.delete(); throw error }
    }

    private fun notification(task: Task, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(this, task.id.hashCode(), Intent(this, MainActivity::class.java)
            .setData(Uri.parse("secret-downloader://task/${task.id}"))
            .putExtra("task_id", task.id).putExtra("open_output", task.status == TaskStatus.COMPLETED),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        builder.setSmallIcon(if (ongoing) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setContentTitle(task.title).setContentText(TaskUi.summary(task)).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(ongoing).setAutoCancel(!ongoing)
        if (ongoing) builder.setProgress(100, ((task.progress ?: 0f) * 100).toInt(), task.progress == null)
        return builder.build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        jobs.keys.toList().forEach { id ->
            repository.get(id)?.let { task -> repository.report(id, task.attempt) { it.copy(status = TaskStatus.INTERRUPTED,
                errorMessage = "系统已暂停长时间后台任务，请继续或重试") } }
            stopTask(this, id, false)
        }
        stopSelf()
    }
    override fun onDestroy() {
        if (activeService === this) activeService = null
        subscription?.close()
        jobs.keys.toList().forEach { id ->
            repository.get(id)?.let { task -> repository.report(id, task.attempt) {
                it.copy(status = TaskStatus.INTERRUPTED, speedBps = null, etaSeconds = null,
                    errorMessage = "后台任务已停止，请继续或重试。")
            } }
            stopTask(this, id, false)
        }
        executor.shutdownNow()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopIfIdle() {
        if (jobs.isEmpty() && pendingStarts.get() == 0 && stopSelfResult(latestStartId)) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    companion object {
        private const val CHANNEL = "media_downloads"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_TASK = "task_id"
        private val jobs = ConcurrentHashMap<String, FutureTask<Unit>>()
        private val jobTypes = ConcurrentHashMap<String, TaskType>()
        @Volatile private var activeService: DownloadService? = null
        private val submitted = ConcurrentHashMap.newKeySet<String>()
        private val pendingStarts = java.util.concurrent.atomic.AtomicInteger()
        private val cleanup = Executors.newSingleThreadExecutor()
        val activeDownloadCount: Int get() = jobTypes.values.count { it == TaskType.DOWNLOAD }
        val activeConversionCount: Int get() = jobTypes.values.count { it != TaskType.DOWNLOAD }
        @Volatile var status = "粘贴链接或分享文字，即可下载。"
            private set

        internal fun jobDirectory(context: Context, id: String): File {
            require(id.matches(Regex("[A-Za-z0-9-]+"))) { "任务编号无效" }
            return File(context.cacheDir, "jobs/$id")
        }
        fun enqueueTask(context: Context, id: String) {
            val intent = Intent(context, DownloadService::class.java).putExtra(EXTRA_TASK, id)
            pendingStarts.incrementAndGet()
            try { if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent) }
            catch (error: Exception) { pendingStarts.decrementAndGet(); throw error }
        }
        fun stopTask(context: Context, id: String, deleteFiles: Boolean) {
            DownloadEngine.cancel(id)
            jobs.remove(id)?.cancel(true)
            jobTypes.remove(id)
            activeService?.let { service -> service.main.post { service.stopIfIdle() } }
            if (deleteFiles) {
                val attempt = TaskRepository.get(context).get(id)?.attempt ?: 0
                cleanup.execute { File(jobDirectory(context, id), "attempt-$attempt").deleteRecursively() }
            }
        }
        fun enqueue(context: Context, text: String) {
            val parsed = InputParser.parse(text)
            TaskRepository.get(context).create(TaskType.DOWNLOAD, "${parsed.platform.name} 链接下载", parsed.url)
        }
        fun enqueueVideo(context: Context, uri: Uri, cover: Uri? = null, coverTimeUs: Long = 0L) {
            require(uri.scheme == "content" && (cover == null || cover.scheme == "content")) { "请通过系统选择器选择视频和封面" }
            TaskRepository.get(context).create(TaskType.LIVE_PHOTO, "视频转实况", params = JSONObject()
                .put("sourceUri", uri.toString()).put("coverUri", cover?.toString())
                .put("coverTimestampMs", coverTimeUs.coerceAtLeast(0) / 1000).toString())
        }
    }
}
