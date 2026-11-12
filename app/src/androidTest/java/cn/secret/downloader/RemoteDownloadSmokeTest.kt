package cn.secret.downloader

import android.app.Instrumentation
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.MediaItem as PlaybackItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Uses a public sample and the production repository/service. A network failure is a real failure. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object RemoteDownloadSmokeTest {
    fun run(instrumentation: Instrumentation): String {
        val fixture = P6TaskSmokeSupport(instrumentation)
        val prefix = "RemoteDownloadSmoke${System.nanoTime().toString(36)}"
        val owned = linkedSetOf<String>()
        val trace = mutableListOf<Task>()
        val subscription = fixture.main { fixture.tasks.observeAll { entries ->
            entries.filter { it.title.startsWith(prefix) }.forEach { if (trace.lastOrNull() != it) trace += it }
        } }
        try {
            fixture.status("Real public Douyin download: starting production service")
            val task = fixture.main { fixture.tasks.create(TaskType.DOWNLOAD, prefix,
                "https://www.douyin.com/video/7705772725423459610").also { owned += it.id } }
            fixture.await("public Douyin download; network errors are not replaced with demo output", 240_000) {
                val current = checkNotNull(fixture.tasks.get(task.id))
                fixture.requireHealthy(current)
                current.status == TaskStatus.COMPLETED && fixture.media.items.any { it.taskId == task.id }
            }
            val item = fixture.main {
                val completed = checkNotNull(fixture.tasks.get(task.id))
                val result = fixture.media.items.single { it.taskId == task.id }
                check(trace.any { it.status == TaskStatus.PARSING && it.stage == TaskStage.PARSING && it.progress == null }) { "No real parsing event" }
                check(trace.any { it.stage == TaskStage.DOWNLOADING && it.bytesDone > 0 }) { "No actual downloaded-byte event" }
                check(trace.any { it.stage == TaskStage.SAVING }) { "No saving event" }
                check(trace.mapNotNull { it.progress }.all { it.isFinite() && it in 0f..1f })
                check(completed.progress == 1f && completed.finishedAt != null)
                check(completed.outputUri == result.localUri && result.type == MediaType.VIDEO)
                check(result.sourceUrl == task.sourceUrl && result.platform == "DOUYIN")
                check(result.width > 0 && result.height > 0 && result.durationMs > 0 && result.sizeBytes > 0)
                check(result.coverUri != null && result.exists && result.title.isNotBlank())
                check(!org.json.JSONObject(result.params).optBoolean("demoOutput"))
                result
            }
            val local = Uri.parse(checkNotNull(item.localUri))
            fixture.context.contentResolver.openInputStream(local).use { input ->
                checkNotNull(input); val header = ByteArray(12); check(input.read(header) == header.size)
                check(String(header, 4, 4, Charsets.US_ASCII) == "ftyp") { "Downloaded output is not an MP4" }
            }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(fixture.context, local)
                val duration = checkNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull())
                check(kotlin.math.abs(duration - item.durationMs) <= 100)
                checkNotNull(retriever.getFrameAtTime(0)).recycle()
            } finally { retriever.release() }
            assertPlayable(fixture, local)
            fixture.status("Reported looping Douyin photo: resolving full desktop data and converting its original animation")
            val animation = fixture.main { fixture.tasks.create(TaskType.DOWNLOAD, "$prefix animation",
                "https://www.douyin.com/note/7702728846361253007").also { owned += it.id } }
            fixture.await("reported looping photo downloads as a Live Photo", 240_000) {
                val current = checkNotNull(fixture.tasks.get(animation.id)); fixture.requireHealthy(current)
                current.status == TaskStatus.COMPLETED && fixture.media.items.any { it.taskId == current.id }
            }
            val live = fixture.main {
                val completed = checkNotNull(fixture.tasks.get(animation.id))
                val result = fixture.media.items.single { it.taskId == animation.id }
                check(result.type == MediaType.LIVE_PHOTO) { "Reported looping animation was saved as ${result.type}" }
                check(result.localUri == completed.outputUri && result.title.endsWith("_MP"))
                check(result.width > 0 && result.height > 0 && result.durationMs >= 2_000)
                check(trace.any { it.id == animation.id && it.stage == TaskStage.DOWNLOADING && it.bytesDone > 0 })
                check(trace.any { it.id == animation.id && it.stage == TaskStage.MERGING }) { "Animation did not pass through real Live Photo packaging" }
                check(result.motionVideoUri != null && result.coverUri != null)
                result
            }
            val liveUri = Uri.parse(checkNotNull(live.localUri))
            check(fixture.context.contentResolver.getType(liveUri) == "image/jpeg")
            fixture.context.contentResolver.query(liveUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null).use { cursor ->
                checkNotNull(cursor); check(cursor.moveToFirst() && cursor.getString(0).endsWith("_MP.jpg"))
            }
            fixture.context.contentResolver.openInputStream(liveUri).use { input ->
                checkNotNull(input)
                val header = ByteArray(65_536); var length = 0
                while (length < header.size) { val read = input.read(header, length, header.size - length); if (read < 0) break; length += read }
                check(length > 2 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte())
                check(String(header, 0, length, Charsets.ISO_8859_1).contains("GCamera:MotionPhoto=\"1\""))
            }
            fixture.context.contentResolver.openInputStream(Uri.parse(checkNotNull(live.coverUri))).use { input ->
                checkNotNull(BitmapFactory.decodeStream(input)).recycle()
            }
            val motion = Uri.parse(checkNotNull(live.motionVideoUri))
            check(motion.scheme == "content") { "The original animation sidecar must remain viewer-readable" }
            val motionReader = MediaMetadataRetriever()
            val duration = try {
                motionReader.setDataSource(fixture.context, motion)
                checkNotNull(motionReader.getFrameAtTime(1_000_000)).recycle()
                checkNotNull(motionReader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull())
            } finally { motionReader.release() }
            check(duration in 2_900L..3_100L && kotlin.math.abs(duration - live.durationMs) <= 100) { "Original 3-second animation was shortened" }
            assertPlayable(fixture, motion)
            return "Public Douyin video: real task stages, downloaded bytes, metadata, MP4 decode and Media3 first frame: PASS\n" +
                "Reported Douyin 7702728846361253007: full desktop animation, real _MP.jpg/Live Photo metadata, original cover and playable ${duration}ms motion sidecar: PASS"
        } finally {
            subscription.close()
            fixture.cleanup(owned)
            fixture.finish()
        }
    }

    private fun assertPlayable(fixture: P6TaskSmokeSupport, uri: Uri) {
        val surface = fixture.main { PlayerView(fixture.activity).apply { useController = false } }
        val firstFrame = AtomicBoolean()
        val player = fixture.main {
            fixture.activity.findViewById<ViewGroup>(android.R.id.content).addView(surface,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            ExoPlayer.Builder(fixture.context).build().also {
                it.volume = 0f
                it.addListener(object : Player.Listener { override fun onRenderedFirstFrame() { firstFrame.set(true) } })
                surface.player = it; it.setMediaItem(PlaybackItem.fromUri(uri)); it.prepare(); it.play()
            }
        }
        try {
            fixture.await("downloaded motion renders its first frame and plays", 20_000) {
                check(player.playerError == null) { "Downloaded motion cannot play: ${player.playerError}" }
                firstFrame.get() && player.isPlaying && player.currentPosition >= 100
            }
        } finally { fixture.main { surface.player = null; player.release(); (surface.parent as? ViewGroup)?.removeView(surface) } }
    }
}

/** Shared only by the two P6 instrumentation probes; never seeds or replaces production records. */
internal class P6TaskSmokeSupport(val instrumentation: Instrumentation) {
    val context = instrumentation.targetContext
    val tasks = TaskRepository.get(context)
    val media = MediaRepository.get(context)
    val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity

    init { await("repositories loaded") { tasks.isLoaded && media.isLoaded } }

    fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    fun status(text: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$text\n") })
    fun await(label: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do { if (main(condition)) return; SystemClock.sleep(40) } while (SystemClock.uptimeMillis() < deadline)
        error("Timed out: $label")
    }
    fun requireHealthy(task: Task) {
        check(task.status !in setOf(TaskStatus.FAILED, TaskStatus.CANCELED, TaskStatus.INTERRUPTED)) {
            "${task.title}: ${task.status}: ${task.errorMessage}\n${tasks.logs(task.id).joinToString("\n")}"
        }
    }
    fun savedTask(id: String): Task? = row("creative_tasks.db", "tasks", "task_json", id)?.let(TaskCodec::decode)
    fun savedMedia(id: String): MediaItem? = row("creative_media.db", "media_items", "item_json", id)?.let(MediaCodec::decode)
    private fun row(database: String, table: String, column: String, id: String): String? = runCatching {
        SQLiteDatabase.openDatabase(context.getDatabasePath(database).absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.query(table, arrayOf(column), "id = ?", arrayOf(id), null, null, null).use { if (it.moveToFirst()) it.getString(0) else null }
        }
    }.getOrNull()

    fun cleanup(ids: Set<String>, sourceId: String? = null) {
        if (ids.isEmpty() && sourceId == null) return
        val ownedMedia = main { media.items.filter { it.taskId in ids || it.id == sourceId } }
        val outputs = main { tasks.tasks.filter { it.id in ids }.mapNotNull { it.outputUri } }
        main { ids.forEach { tasks.cancel(it) } }
        // Only exact URIs published by these UUID tasks are eligible for removal.
        (ownedMedia.filter { it.taskId in ids }.mapNotNull { it.localUri } + outputs).distinct().forEach { value ->
            val uri = Uri.parse(value)
            if (uri.scheme == "content") context.contentResolver.delete(uri, null, null)
            else if (uri.scheme == "file") File(checkNotNull(uri.path)).delete()
        }
        val worksRoot = File(context.filesDir, "works").canonicalFile
        ownedMedia.filter { it.taskId in ids }.forEach { item ->
            check(item.taskId in ids && item.id.startsWith("${item.taskId}-"))
            val directory = File(worksRoot, item.id).canonicalFile
            check(directory.parentFile == worksRoot) { "Unsafe test sidecar path" }
            if (directory.exists()) check(directory.deleteRecursively()) { "Cannot remove owned test sidecars" }
        }
        main { ownedMedia.forEach { media.delete(it.id) }; ids.forEach { tasks.delete(it) } }
        await("owned test metadata removed") { ids.none { tasks.get(it) != null } && ownedMedia.none { media.get(it.id) != null } }
    }
    fun finish() = main { if (!activity.isFinishing) activity.finish() }
}
