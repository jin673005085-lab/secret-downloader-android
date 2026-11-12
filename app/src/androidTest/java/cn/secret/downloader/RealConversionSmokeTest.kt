package cn.secret.downloader

import android.app.Instrumentation
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.FileProvider
import androidx.media3.common.Player
import org.json.JSONObject
import java.io.File
import java.io.Closeable
import java.security.MessageDigest
import kotlin.math.abs

/** Real service/MediaStore tests. Only this run's tasks, records and published files are removed. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object RealConversionSmokeTest {
    fun run(instrumentation: Instrumentation): String {
        fun <T> main(action: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(action) }
            return checkNotNull(result).getOrThrow()
        }
        fun await(label: String, timeoutMs: Long = 10_000, diagnostic: (() -> String)? = null, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            do {
                if (main(condition)) return
                SystemClock.sleep(40)
            } while (SystemClock.uptimeMillis() < deadline)
            error("Timed out: $label${diagnostic?.let { "\n${main(it)}" }.orEmpty()}")
        }
        fun status(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })

        val context = instrumentation.targetContext
        val media = MediaRepository.get(context)
        val tasks = TaskRepository.get(context)
        val prefix = "RealConversionSmoke${System.nanoTime().toString(36)}"
        val directory = File(context.cacheDir, prefix).apply { check(mkdirs()) }
        val sourceFile = File(directory, "source-9s.mp4")
        instrumentation.context.assets.open("h264-aac-9s.mp4").use { input ->
            sourceFile.outputStream().use { output -> input.copyTo(output) }
        }
        val sourceDigest = MessageDigest.getInstance("SHA-256").digest(sourceFile.readBytes())
        val sourceUri = FileProvider.getUriForFile(context, "${context.packageName}.files", sourceFile)
        val retriever = MediaMetadataRetriever()
        val source = try {
            retriever.setDataSource(sourceFile.absolutePath)
            MediaItem(id = "$prefix-source", title = "$prefix source", platform = "转换验收",
                localUri = sourceUri.toString(),
                durationMs = checkNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()),
                width = checkNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()),
                height = checkNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()),
                sizeBytes = sourceFile.length())
        } finally { retriever.release() }
        check(source.durationMs in 8_800L..9_200L) { "Expected the controlled 9-second fixture" }
        val sourceTracks = tracks(context, sourceUri)
        check(sourceTracks.keys.containsAll(listOf("video", "audio")))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val original = main { activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) }
        val ownedIds = linkedSetOf<String>()
        val traces = linkedMapOf<String, MutableList<Task>>()
        var observer: Closeable? = null
        var viewer: WorkViewerView? = null
        var failure: Throwable? = null

        fun show(item: MediaItem): WorkViewerView = main {
            viewer?.release()
            WorkViewerView(activity, UiTheme(activity), media, item, listOf(item), {}, {}, { _, _ -> }).also {
                viewer = it; activity.setContentView(it)
            }
        }
        fun cover() = checkNotNull(viewer).findViewWithTag<View>("work_viewer_cover")
        fun click(tag: String) = main {
            check(checkNotNull(checkNotNull(viewer).findViewWithTag<View>(tag)) { "Missing $tag" }.performClick())
        }
        fun create(type: TaskType, quality: String): MediaItem {
            status("Real $type ($quality): starting service with the local 9-second fixture")
            val params = JSONObject().apply {
                put("mediaId", source.id); put("sourceUri", source.localUri); put("sourceTitle", source.title)
                put("durationMs", source.durationMs); put("sourceWidth", source.width); put("sourceHeight", source.height)
                put("startMs", 0); put("endMs", source.durationMs)
                put("fps", 15); put("width", if (type == TaskType.GIF) 480 else 0)
                put("quality", quality); put("loop", "infinite"); put("speed", 1.0)
                // Match Creator's video-frame cover path, including its explicit JSON null.
                put("coverUri", JSONObject.NULL); put("coverTimestampMs", 3_000)
                put("retainFullDuration", type == TaskType.LIVE_PHOTO)
            }
            val task = main { tasks.create(type, "$prefix $type $quality", params = params.toString()).also { ownedIds += it.id } }
            await("$type/$quality real service completion", 180_000) {
                val current = checkNotNull(tasks.get(task.id))
                check(current.status !in setOf(TaskStatus.FAILED, TaskStatus.INTERRUPTED, TaskStatus.CANCELED)) {
                    "$type/$quality failed: ${current.errorMessage}\n${tasks.logs(task.id).joinToString("\n")}"
                }
                current.status == TaskStatus.COMPLETED && media.items.any { it.taskId == task.id }
            }
            return main {
                val completed = checkNotNull(tasks.get(task.id))
                val item = media.items.single { it.taskId == task.id }
                check(completed.progress == 1f && completed.finishedAt != null)
                check(item.type == if (type == TaskType.GIF) MediaType.GIF else MediaType.LIVE_PHOTO)
                check(item.localUri == completed.outputUri && !item.localUri.isNullOrBlank())
                if (Build.VERSION.SDK_INT >= 29) check(Uri.parse(item.localUri).scheme == "content")
                check(item.sourceMediaId == source.id && item.sourceStartMs == 0L && item.sourceEndMs == source.durationMs)
                check(item.width > 0 && item.height > 0 && item.sizeBytes > 0 && item.exists)
                check(abs(item.durationMs - source.durationMs) <= 100) { "Output duration was trimmed: ${item.durationMs}" }
                val savedParams = JSONObject(item.params)
                check(savedParams.getString("quality") == quality && !savedParams.optBoolean("demoOutput"))
                check(!savedParams.optBoolean("fakeTask"))
                val trace = traces[task.id].orEmpty()
                check(trace.any { it.status == TaskStatus.RUNNING && it.stage == TaskStage.ENCODING }) { "No real encoding stage was observed" }
                val encoding = trace.filter { it.stage == TaskStage.ENCODING }.mapNotNull { it.progress }
                check(encoding.all { it.isFinite() && it in 0f..1f })
                check(encoding.zipWithNext().all { (left, right) -> right >= left }) { "Encoding progress moved backwards" }
                item
            }
        }

        try {
            await("task/media repositories load") { tasks.isLoaded && media.isLoaded }
            main {
                media.add(source)
                observer = tasks.observeAll { entries ->
                    entries.filter { it.title.startsWith(prefix) }.forEach { task ->
                        val trace = traces.getOrPut(task.id) { mutableListOf() }
                        if (trace.lastOrNull() != task) trace += task
                    }
                }
            }
            for (quality in listOf("standard", "high")) {
                val item = create(TaskType.GIF, quality)
                val uri = Uri.parse(item.localUri)
                val bytes = context.contentResolver.openInputStream(uri).use { checkNotNull(it).readBytes() }
                check(bytes.size.toLong() == item.sizeBytes)
                check(bytes.take(6).toByteArray().toString(Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")) { "Output is not a GIF file" }
                check(item.width == 480 && item.height % 2 == 0 && item.fps == 15 && (item.frameCount ?: 0) >= 120)
                if (Build.VERSION.SDK_INT >= 29) {
                    context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.DISPLAY_NAME), null, null, null).use { cursor ->
                        checkNotNull(cursor); check(cursor.moveToFirst())
                        check(cursor.getString(0) == "image/gif" && cursor.getString(1).endsWith(".gif", true))
                    }
                }
                if (Build.VERSION.SDK_INT >= 28) {
                    val decoded = ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, uri))
                    check(decoded is AnimatedImageDrawable) { "The system decoder did not recognize an animated GIF" }
                    main { decoded.stop() }
                } else {
                    @Suppress("DEPRECATION")
                    val decoded = checkNotNull(Movie.decodeByteArray(bytes, 0, bytes.size))
                    check(decoded.duration() >= 8_000)
                }
                val current = show(item)
                await("$quality GIF starts animating in the work viewer") {
                    current.isAnimating && current.findViewWithTag<View>("work_error").visibility != View.VISIBLE &&
                        (Build.VERSION.SDK_INT < 28 || descendants(current).filterIsInstance<ImageView>()
                            .map { it.drawable }.filterIsInstance<AnimatedImageDrawable>().any { it.isRunning })
                }
                click("work_toggle"); await("GIF pauses") { !current.isAnimating }
                click("work_toggle"); await("GIF resumes") { current.isAnimating }
                main { current.stopForBackground(); check(!current.isAnimating && current.motionEngine == null) }
                status("$quality GIF: real service, MediaStore GIF header/MIME, library fields, system decoder and viewer pause/resume PASS")
            }

            val live = create(TaskType.LIVE_PHOTO, "standard")
            val motion = Uri.parse(checkNotNull(live.motionVideoUri))
            check(motion.scheme == "content") { "The real motion sidecar must be a viewer-readable content URI" }
            val motionReader = MediaMetadataRetriever()
            val motionDuration = try {
                motionReader.setDataSource(context, motion)
                checkNotNull(motionReader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull())
            } finally { motionReader.release() }
            check(abs(motionDuration - source.durationMs) <= 100) { "The full 9-second live track was not retained" }
            val motionTracks = tracks(context, motion)
            sourceTracks.forEach { (type, lastSampleUs) ->
                check((motionTracks[type] ?: -1) >= lastSampleUs - 200_000) { "$type was shortened in the motion sidecar" }
            }
            val jpegHeader = context.contentResolver.openInputStream(Uri.parse(live.localUri)).use { input ->
                checkNotNull(input); val buffer = ByteArray(65_536)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                check(count > 2 && buffer[0] == 0xff.toByte() && buffer[1] == 0xd8.toByte())
                String(buffer, 0, count, Charsets.ISO_8859_1)
            }
            check(jpegHeader.contains("GCamera:MotionPhotoPresentationTimestampUs=\"3000000\""))
            val coverImage = context.contentResolver.openInputStream(Uri.parse(checkNotNull(live.coverUri))).use { BitmapFactory.decodeStream(it) }
            checkNotNull(coverImage).recycle()
            val current = show(live)
            await("real live track autoplays and renders") {
                current.motionEngine?.let { it.isPlaying && it.currentPosition >= 150 } == true && cover().visibility == View.GONE
            }
            main { check(current.motionEngine?.repeatMode == Player.REPEAT_MODE_OFF) }
            await("live plays once and returns to its cover", 18_000, diagnostic = {
                current.playbackDiagnostics()
            }) {
                current.motionEngine?.isPlaying == false && cover().visibility == View.VISIBLE
            }
            // Seeking back can deliver another onRenderedFirstFrame. It must not hide the restored cover.
            SystemClock.sleep(500)
            main {
                check(!current.isAnimating && (current.motionEngine?.currentPosition ?: Long.MAX_VALUE) <= 100)
                check(cover().visibility == View.VISIBLE && !current.keepScreenOn) { "A late first-frame event hid the restored cover" }
                val replay = checkNotNull(current.findViewWithTag<View>("work_replay"))
                check(replay.visibility == View.VISIBLE && replay.height >= (48 * current.resources.displayMetrics.density).toInt())
                check(!replay.contentDescription.isNullOrBlank())
                val minimum = (48 * current.resources.displayMetrics.density).toInt()
                listOf("route_back", "work_info", "work_more", "work_replay", "work_sound", "work_share", "work_favorite",
                    "work_edit", "work_source", "work_delete").forEach { tag ->
                    val button = checkNotNull(current.findViewWithTag<View>(tag))
                    check(button.width >= minimum && button.height >= minimum) { "$tag is smaller than a visible 48dp button: ${button.width}x${button.height}" }
                    check(!button.contentDescription.isNullOrBlank()) { "$tag has no readable action label" }
                }
            }
            click("work_replay")
            await("explicit replay button restarts the live track", diagnostic = { current.playbackDiagnostics() }) {
                current.motionEngine?.let { it.isPlaying && it.currentPosition >= 150 } == true && cover().visibility == View.GONE
            }
            click("work_toggle")
            await("return to cover after explicit replay", diagnostic = { current.playbackDiagnostics() }) {
                !current.isAnimating && cover().visibility == View.VISIBLE
            }
            val gestureArea = main { current.findViewWithTag<View>("work_gesture_area") }
            val pressedAt = SystemClock.uptimeMillis()
            fun touch(action: Int) = main {
                val event = MotionEvent.obtain(pressedAt, SystemClock.uptimeMillis(), action, gestureArea.width / 2f, gestureArea.height / 2f, 0)
                try { check(gestureArea.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
            touch(MotionEvent.ACTION_DOWN)
            await("holding a live photo restarts its real track", diagnostic = { current.playbackDiagnostics() }) {
                current.motionEngine?.let { it.isPlaying && it.currentPosition >= 150 } == true && cover().visibility == View.GONE
            }
            touch(MotionEvent.ACTION_UP)
            await("releasing the live photo returns to cover", diagnostic = { current.playbackDiagnostics() }) {
                !current.isAnimating && cover().visibility == View.VISIBLE
            }
            main {
                check((current.motionEngine?.currentPosition ?: Long.MAX_VALUE) <= 100)
                current.release(); check(current.motionEngine == null && !current.keepScreenOn)
                check(media.items.filter { it.taskId in ownedIds }.size == 3)
                check(media.items.filter { it.taskId in ownedIds }.none { it.type == MediaType.VIDEO })
            }
            check(sourceDigest.contentEquals(MessageDigest.getInstance("SHA-256").digest(sourceFile.readBytes()))) { "The source video was modified" }
            return "Real standard/high GIF service tasks publish animated system GIFs and complete library metadata; viewer pause/resume: PASS\n" +
                "Real Live Photo retains the complete 9-second video/audio, frame cover and readable sidecar; viewer auto-once/explicit replay/hold/release: PASS\n" +
                "Source bytes preserved; only the run's three task outputs/records are cleaned up."
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val cleanup = runCatching {
                val entries = main {
                    observer?.close(); viewer?.release(); activity.setContentView(original)
                    val entries = media.items.filter { it.taskId in ownedIds }
                    ownedIds.forEach { id -> tasks.get(id)?.takeIf { it.isOngoing }?.let { tasks.cancel(id) }; tasks.delete(id) }
                    entries.forEach { media.delete(it.id) }; media.delete(source.id)
                    if (!activity.isFinishing) activity.finish()
                    entries
                }
                entries.mapNotNull { it.localUri }.distinct().forEach { address ->
                    val uri = Uri.parse(address)
                    check(uri != sourceUri) { "Refusing to remove the input fixture as an output" }
                    if (uri.scheme == "content") check(context.contentResolver.delete(uri, null, null) > 0) { "Could not remove the test output $uri" }
                    else if (uri.scheme == "file") check(File(checkNotNull(uri.path)).delete())
                }
                val works = File(context.filesDir, "works").canonicalFile
                entries.forEach { item ->
                    check(ownedIds.any { item.id.startsWith("$it-") })
                    val own = File(works, item.id).canonicalFile
                    check(own.parentFile == works)
                    if (own.exists()) check(own.deleteRecursively())
                }
                check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                check(directory.deleteRecursively())
            }.exceptionOrNull()
            if (cleanup != null) {
                val originalFailure = failure
                if (originalFailure != null) originalFailure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }

    private fun tracks(context: android.content.Context, uri: Uri): Map<String, Long> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            return buildMap {
                for (index in 0 until extractor.trackCount) {
                    val mime = extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                    val type = mime.substringBefore('/')
                    if (type != "video" && type != "audio") continue
                    extractor.selectTrack(index); extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    var lastSampleUs = -1L
                    while (extractor.sampleTime >= 0) { lastSampleUs = extractor.sampleTime; if (!extractor.advance()) break }
                    extractor.unselectTrack(index)
                    put(type, lastSampleUs)
                }
            }
        } finally { extractor.release() }
    }
}
