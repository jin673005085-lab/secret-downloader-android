package cn.secret.downloader

import android.app.ActivityManager
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

/** Exercises real FFmpeg cancellation and new service starts using only this run's records/files. */
object ServiceCommandsSmokeTest {
    fun run(instrumentation: Instrumentation): String {
        check(Build.VERSION.SDK_INT >= 29) { "service_commands requires MediaStore scoped-storage APIs (Android 10+)" }
        val fixture = P6TaskSmokeSupport(instrumentation)
        val context = fixture.context
        val prefix = "ServiceCommandsSmoke${System.nanoTime().toString(36)}"
        val directory = File(context.cacheDir, prefix).apply { check(mkdirs()) }
        val input = File(directory, "source-9s.mp4")
        val sourceId = "$prefix-source"
        val owned = linkedSetOf<String>()
        val baselines = linkedMapOf<String, Set<String>>()
        val trace = mutableListOf<Task>()
        val observedMedia = linkedSetOf<String>()
        var phase = "INITIAL_ENCODING"
        var pausedProgress = 0f
        var observerFailure: Throwable? = null
        var taskObserver: Closeable? = null
        var mediaObserver: Closeable? = null
        var failure: Throwable? = null
        val notifications = context.getSystemService(NotificationManager::class.java)

        // Snapshot before launch and restrict to our app's generated UUID filename; existing rows stay untouched.
        fun publicRows(id: String): Map<String, Int> {
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val name = Regex("gif_${id.take(8)}(?: ?\\(\\d+\\))?\\.gif")
            return buildMap {
                context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.IS_PENDING),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND " +
                        "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
                    arrayOf("${MediaPublisher.GIF_DISPLAY_PATH}/", "gif_${id.take(8)}%.gif", context.packageName), null).use { cursor ->
                    checkNotNull(cursor) { "Cannot inspect owned MediaStore GIF outputs" }
                    while (cursor.moveToNext()) if (name.matches(cursor.getString(1)))
                        put(ContentUris.withAppendedId(collection, cursor.getLong(0)).toString(), cursor.getInt(2))
                }
            }
        }
        fun newPublicRows(id: String) = publicRows(id).filterKeys { it !in baselines.getValue(id) }
        fun notificationId(id: String) = (id.hashCode() and 0x7fffffff) or 0x100
        fun hasNotification(id: String) = notifications.activeNotifications.any { it.id == notificationId(id) }
        fun healthy(task: Task) { observerFailure?.let { throw it }; fixture.requireHealthy(task) }
        fun assertOutput(id: String, attempt: Int): MediaItem = fixture.main {
            val task = checkNotNull(fixture.tasks.get(id))
            check(task.attempt == attempt && task.status == TaskStatus.COMPLETED && task.progress == 1f)
            fixture.media.items.single { it.taskId == id }.also { item ->
                check(item.id == "$id-$attempt-0" && item.type == MediaType.GIF && item.localUri == task.outputUri)
                check(item.sourceMediaId == sourceId && item.fps == 20 && (item.frameCount ?: 0) > 1)
            }
        }
        fun assertUniquePublicOutput(item: MediaItem) {
            val rows = newPublicRows(checkNotNull(item.taskId))
            check(rows.keys == setOf(item.localUri) && rows.values.single() == 0) {
                "Duplicate, pending or orphan output for ${item.taskId}: $rows; expected ${item.localUri}"
            }
            context.contentResolver.openInputStream(Uri.parse(item.localUri)).use { stream ->
                val header = ByteArray(6); checkNotNull(stream); check(stream.read(header) == header.size)
                check(String(header, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a"))
            }
        }

        try {
            instrumentation.context.assets.open("h264-aac-9s.mp4").use { source -> input.outputStream().use { source.copyTo(it) } }
            val before = digest(input)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", input)
            fixture.main {
                fixture.media.add(MediaItem(id = sourceId, title = "$prefix source", platform = "服务命令验收",
                    localUri = uri.toString(), durationMs = 9_000, width = 320, height = 240, sizeBytes = input.length()))
                taskObserver = fixture.tasks.observeAll { entries ->
                    runCatching {
                        entries.filter { it.title.startsWith(prefix) }.forEach { task ->
                            if (owned.add(task.id)) {
                                baselines[task.id] = emptySet()
                                baselines[task.id] = publicRows(task.id).keys.toSet()
                            }
                            if (task.title != "$prefix race") return@forEach
                            if (trace.lastOrNull() != task) trace += task
                            val partial = task.status == TaskStatus.RUNNING && task.stage == TaskStage.ENCODING &&
                                task.progress?.let { it > 0f && it < .95f } == true
                            when {
                                phase == "INITIAL_ENCODING" && partial -> {
                                    pausedProgress = checkNotNull(task.progress)
                                    phase = "PAUSED"; fixture.tasks.pause(task.id)
                                }
                                phase == "RESUME_READING" && task.status == TaskStatus.RUNNING && task.stage == TaskStage.READING ->
                                    phase = "RESUME_ENCODING"
                                phase == "RESUME_ENCODING" && partial && checkNotNull(task.progress) > pausedProgress -> {
                                    phase = "RETRIED"
                                    fixture.tasks.cancel(task.id)
                                    fixture.tasks.retry(task.id)
                                }
                            }
                        }
                    }.onFailure { observerFailure = it }
                }
                mediaObserver = fixture.media.observeAll { items ->
                    items.filter { it.taskId in owned }.forEach { observedMedia += it.id }
                }
            }
            fun params(short: Boolean = false) = JSONObject().apply {
                put("sourceUri", uri.toString()); put("mediaId", sourceId); put("sourceTitle", "$prefix source")
                put("durationMs", 9_000); put("sourceWidth", 320); put("sourceHeight", 240)
                put("startMs", 0); put("endMs", if (short) 1_000 else 9_000)
                put("fps", 20); put("width", if (short) 320 else 720)
                put("quality", if (short) "standard" else "high"); put("speed", if (short) 1.0 else .5)
                put("loop", "infinite")
            }.toString()
            fixture.status("Service commands: waiting for real high-quality GIF progress before pause/resume/cancel/retry")
            val race = fixture.main { fixture.tasks.create(TaskType.GIF, "$prefix race", params = params()) }
            fixture.await("real partial encoding paused", 180_000) {
                val current = checkNotNull(fixture.tasks.get(race.id)); healthy(current)
                check(current.status != TaskStatus.COMPLETED) { "No partial FFmpeg progress was available for pause" }
                phase == "PAUSED" && current.status == TaskStatus.PAUSED
            }
            fixture.main { phase = "RESUME_READING"; fixture.tasks.resume(race.id) }
            fixture.await("resumed encoder is canceled and the new attempt completes", 240_000) {
                val current = checkNotNull(fixture.tasks.get(race.id)); healthy(current)
                phase == "RETRIED" && current.attempt == 1 && current.status == TaskStatus.COMPLETED &&
                    fixture.media.items.any { it.taskId == race.id }
            }
            val result = assertOutput(race.id, 1)
            fixture.main {
                check(trace.any { it.status == TaskStatus.PAUSED } && trace.any { it.status == TaskStatus.CANCELED })
                check(trace.zipWithNext().all { (left, right) -> right.attempt >= left.attempt })
                check(trace.none { it.attempt == 0 && it.status == TaskStatus.COMPLETED })
                check(trace.filter { it.attempt == 1 }.none { it.status in setOf(TaskStatus.CANCELED, TaskStatus.FAILED, TaskStatus.INTERRUPTED) })
                check(observedMedia == setOf(result.id)) { "A stale attempt entered the library: $observedMedia" }
            }
            assertUniquePublicOutput(result)

            val canNotify = notifications.areNotificationsEnabled() &&
                notifications.getNotificationChannel("media_downloads")?.importance != NotificationManager.IMPORTANCE_NONE
            val otherJobs = DownloadService.activeDownloadCount + DownloadService.activeConversionCount
            val notificationReport = if (canNotify && otherJobs == 0) {
                fixture.await("completed notification posted") { hasNotification(race.id) }
                notifications.cancel(notificationId(race.id))
                fixture.await("completed notification dismissed") { !hasNotification(race.id) }
                fixture.await("idle DownloadService actually stops") { !serviceRunning(fixture) }
                fixture.status("Service commands: starting a fresh service after dismissing its earlier completion notification")
                val next = fixture.main { fixture.tasks.create(TaskType.GIF, "$prefix notification", params = params(short = true)) }
                fixture.await("new service completes without reviving its dismissed notification", 180_000) {
                    check(!hasNotification(race.id)) { "Dismissed completion notification was replayed by the new service" }
                    val current = checkNotNull(fixture.tasks.get(next.id)); healthy(current)
                    current.status == TaskStatus.COMPLETED && fixture.media.items.any { it.taskId == next.id }
                }
                assertUniquePublicOutput(assertOutput(next.id, 0))
                "Dismissed completion notification stays dismissed after a fresh service start: PASS"
            } else "Notification restart check: SKIP (${if (!canNotify) "notifications disabled" else "other jobs still active"})"
            repeat(20) {
                SystemClock.sleep(50)
                fixture.main {
                    val current = checkNotNull(fixture.tasks.get(race.id)); healthy(current)
                    check(current.attempt == 1 && current.status == TaskStatus.COMPLETED && current.outputUri == result.localUri)
                    check(fixture.media.items.filter { it.taskId == race.id }.map { it.id } == listOf(result.id))
                    if (canNotify && otherJobs == 0) check(!hasNotification(race.id))
                }
            }
            assertUniquePublicOutput(result)
            check(before.contentEquals(digest(input))) { "Service commands changed the source video" }
            return "Real high-quality GIF pause/resume then immediate cancel/retry: new attempt completes, stale callbacks cannot replace it, one library/public output, source SHA-256 unchanged: PASS\n$notificationReport"
        } catch (error: Throwable) {
            error.addSuppressed(IllegalStateException(fixture.main {
                "phase=$phase, pausedProgress=$pausedProgress; ${trace.takeLast(16).joinToString { "${it.attempt}:${it.status}/${it.stage}@${it.progress}" }}\n" +
                    owned.joinToString("\n") { id -> "$id: ${fixture.tasks.get(id)}\n${fixture.tasks.logs(id).joinToString("\n")}" }
            }))
            failure = error; throw error
        } finally {
            val cleanup = runCatching {
                fixture.main { taskObserver?.close(); mediaObserver?.close() }
                fixture.cleanup(owned, sourceId)
                owned.forEach { id ->
                    newPublicRows(id).keys.forEach { value -> check(context.contentResolver.delete(Uri.parse(value), null, null) > 0) }
                    notifications.cancel(notificationId(id))
                    val jobs = File(context.cacheDir, "jobs").canonicalFile
                    val own = DownloadService.jobDirectory(context, id).canonicalFile
                    check(own.parentFile == jobs)
                    if (own.exists()) check(own.deleteRecursively())
                }
                check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                check(directory.deleteRecursively()); fixture.finish()
            }.exceptionOrNull()
            if (cleanup != null) failure?.addSuppressed(cleanup) ?: throw cleanup
        }
    }

    @Suppress("DEPRECATION")
    private fun serviceRunning(fixture: P6TaskSmokeSupport): Boolean = fixture.context.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE).any { it.service.className == DownloadService::class.java.name }

    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
}
