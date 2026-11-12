package cn.secret.downloader

import android.app.Instrumentation
import android.net.Uri
import android.os.Process
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Two instrumentation runs separated by a real adb force-stop, never a simulated database restart. */
object ProcessRecoverySmokeTest {
    private const val DIRECTORY = "p6-process-recovery"
    private const val MARKER = "marker.json"

    fun prepare(instrumentation: Instrumentation): String {
        val fixture = P6TaskSmokeSupport(instrumentation)
        val directory = File(fixture.context.cacheDir, DIRECTORY)
        check(!directory.exists()) { "A recovery fixture already exists; run recovery verify before preparing another" }
        check(directory.mkdirs())
        val prefix = "ProcessRecoverySmoke${System.nanoTime().toString(36)}"
        val owned = linkedSetOf<String>()
        val sourceId = "$prefix-source"
        var subscription: java.io.Closeable? = null
        var prepared = false
        try {
            val source = File(directory, "source-9s.mp4")
            instrumentation.context.assets.open("h264-aac-9s.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
            val uri = FileProvider.getUriForFile(fixture.context, "${fixture.context.packageName}.files", source)
            fixture.main { fixture.media.add(MediaItem(id = sourceId, title = "$prefix source", platform = "恢复验收",
                localUri = uri.toString(), durationMs = 9_000, width = 320, height = 240, sizeBytes = source.length())) }
            fun params(high: Boolean) = JSONObject().apply {
                put("sourceUri", uri.toString()); put("mediaId", sourceId); put("sourceTitle", "$prefix source")
                put("durationMs", 9_000); put("sourceWidth", 320); put("sourceHeight", 240)
                put("startMs", 0); put("endMs", if (high) 9_000 else 1_500)
                put("fps", if (high) 20 else 10); put("width", if (high) 720 else 320)
                put("quality", if (high) "high" else "standard"); put("speed", if (high) .5 else 1.0)
                put("loop", "infinite")
            }.toString()
            fixture.status("Recovery prepare: completing the first real GIF through DownloadService")
            val completed = fixture.main { fixture.tasks.create(TaskType.GIF, "$prefix completed", params = params(false)).also { owned += it.id } }
            fixture.await("first real GIF complete", 180_000) {
                val current = checkNotNull(fixture.tasks.get(completed.id)); fixture.requireHealthy(current)
                current.status == TaskStatus.COMPLETED && fixture.media.items.any { it.taskId == current.id }
            }
            val firstTask = fixture.main { checkNotNull(fixture.tasks.get(completed.id)) }
            val firstMedia = fixture.main { fixture.media.items.single { it.taskId == completed.id } }
            assertGif(fixture, firstMedia)
            var pauseRequested = false
            var encodingProgress: Float? = null
            subscription = fixture.main { fixture.tasks.observeAll { entries ->
                entries.firstOrNull { it.title == "$prefix interrupted" }?.let { task ->
                    val progress = task.progress
                    if (!pauseRequested && task.status == TaskStatus.RUNNING && task.stage == TaskStage.ENCODING &&
                        progress != null && progress > 0f && progress < 1f) {
                        pauseRequested = true; encodingProgress = progress
                        fixture.tasks.pause(task.id)
                    }
                }
            } }
            fixture.status("Recovery prepare: starting high-quality encoding, then pausing after real FFmpeg progress")
            val pending = fixture.main { fixture.tasks.create(TaskType.GIF, "$prefix interrupted", params = params(true)).also { owned += it.id } }
            fixture.await("high-quality GIF really encodes before pause", 180_000) {
                val task = checkNotNull(fixture.tasks.get(pending.id)); fixture.requireHealthy(task)
                check(task.status != TaskStatus.COMPLETED) { "Encoding completed before a real partial progress event could be paused" }
                pauseRequested && task.status == TaskStatus.PAUSED
            }
            subscription.close(); subscription = null
            fixture.await("completed task/media and paused task durably reach SQLite") {
                fixture.savedTask(completed.id) == firstTask && fixture.savedMedia(firstMedia.id) == firstMedia &&
                    fixture.savedTask(pending.id)?.status == TaskStatus.PAUSED && fixture.savedMedia(sourceId) != null
            }
            val marker = JSONObject().apply {
                put("pid", Process.myPid()); put("prefix", prefix); put("sourceId", sourceId)
                put("sourceUri", uri.toString()); put("sourceSha256", digest(source))
                put("ids", JSONArray(owned.toList())); put("firstTask", TaskCodec.encode(firstTask))
                put("firstMedia", MediaCodec.encode(firstMedia)); put("pendingId", pending.id)
                put("encodingProgress", encodingProgress)
            }
            val temporary = File(directory, "marker.tmp").apply { outputStream().use { output ->
                output.write(marker.toString().toByteArray(Charsets.UTF_8)); output.fd.sync()
            } }
            check(temporary.renameTo(File(directory, MARKER)))
            prepared = true
            return "Recovery prepare: real completed GIF persisted; second high-quality GIF encoded to $encodingProgress and paused durably. Marker ready; adb force-stop the app, then run recovery verify."
        } finally {
            subscription?.close()
            if (!prepared) {
                try { fixture.cleanup(owned, sourceId) } finally { removeFixture(fixture, directory) }
            }
            fixture.finish()
        }
    }

    fun verify(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, DIRECTORY)
        val marker = JSONObject(File(directory, MARKER).readText())
        check(marker.getInt("pid") != Process.myPid()) { "No process restart occurred. adb force-stop the app between prepare and verify." }
        val owned = marker.getJSONArray("ids").let { data -> (0 until data.length()).map { data.getString(it) }.toSet() }
        check(owned.size == 2 && owned.all { it.matches(Regex("[a-fA-F0-9-]{36}")) }) { "Invalid recovery fixture task IDs" }
        val fixture = P6TaskSmokeSupport(instrumentation)
        val sourceId = marker.getString("sourceId")
        try {
            val firstTask = TaskCodec.decode(marker.getString("firstTask"))
            val firstMedia = MediaCodec.decode(marker.getString("firstMedia"))
            val pendingId = marker.getString("pendingId")
            fixture.main {
                check(fixture.tasks.get(firstTask.id) == firstTask) { "Completed task did not persist across process death" }
                check(fixture.media.get(firstMedia.id) == firstMedia) { "Completed GIF media metadata did not persist" }
                check(fixture.tasks.get(pendingId)?.status == TaskStatus.INTERRUPTED) { "Unfinished task did not restore as INTERRUPTED" }
                check(fixture.savedTask(pendingId)?.status == TaskStatus.INTERRUPTED) { "Restored interrupted state was not persisted" }
                check(fixture.media.get(sourceId) != null)
            }
            assertGif(fixture, firstMedia)
            check(digest(File(directory, "source-9s.mp4")) == marker.getString("sourceSha256")) { "The controlled source was modified" }
            fixture.context.contentResolver.openInputStream(Uri.parse(marker.getString("sourceUri"))).use { checkNotNull(it); check(it.read() >= 0) }
            fixture.status("Recovery verify: completed records survived the real process restart; resuming interrupted GIF")
            fixture.main { fixture.tasks.resume(pendingId) }
            fixture.await("interrupted GIF resumes to a real saved output", 240_000) {
                val task = checkNotNull(fixture.tasks.get(pendingId)); fixture.requireHealthy(task)
                task.status == TaskStatus.COMPLETED && fixture.media.items.any { it.taskId == pendingId }
            }
            val resumed = fixture.main { fixture.media.items.single { it.taskId == pendingId } }
            assertGif(fixture, resumed)
            check(resumed.width == 720 && resumed.fps == 20 && resumed.durationMs in 17_000L..18_200L)
            check(JSONObject(resumed.params).getString("quality") == "high")
            fixture.await("resumed completion is durable") {
                fixture.savedTask(pendingId)?.status == TaskStatus.COMPLETED && fixture.savedMedia(resumed.id) == resumed
            }
            return "Recovery verify: a real force-stop preserved completed task/GIF records, restored the paused task as INTERRUPTED, and resume produced one real high-quality animated GIF: PASS"
        } finally {
            try { fixture.cleanup(owned, sourceId) } finally { removeFixture(fixture, directory); fixture.finish() }
        }
    }

    private fun assertGif(fixture: P6TaskSmokeSupport, item: MediaItem) {
        check(item.type == MediaType.GIF && item.width > 0 && item.height > 0 && item.sizeBytes > 6 && (item.frameCount ?: 0) > 1)
        fixture.context.contentResolver.openInputStream(Uri.parse(checkNotNull(item.localUri))).use { input ->
            val header = ByteArray(6); checkNotNull(input); check(input.read(header) == 6)
            check(String(header, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")) { "Restored output is not a real GIF" }
        }
    }
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun removeFixture(fixture: P6TaskSmokeSupport, directory: File) {
        val path = directory.canonicalFile
        check(path.parentFile == fixture.context.cacheDir.canonicalFile && path.name == DIRECTORY)
        if (path.exists()) check(path.deleteRecursively()) { "Cannot remove the owned recovery fixture" }
    }
}
