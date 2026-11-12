package cn.secret.downloader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import org.json.JSONObject
import java.io.File

/** Called on the service worker before temporary media is removed. */
internal object MediaOutputIndexer {
    private fun uri(context: Context, file: File): String = androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.files", file).toString()
    fun create(context: Context, task: Task, file: File, saved: MediaPublisher.Published, info: EngineOutput?, index: Int): MediaItem {
        val data = JSONObject(task.params)
        val type = when {
            file.extension.equals("gif", true) -> MediaType.GIF
            file.name.endsWith("_MP.jpg", true) -> MediaType.LIVE_PHOTO
            else -> MediaType.VIDEO
        }
        val id = "${task.id}-${task.attempt}-$index"
        val owned = File(context.filesDir, "works/$id").apply { check(isDirectory || mkdirs()) }
        val motion = info?.motionVideo?.takeIf { it.isFile }?.let { source ->
            File(owned, "motion.mp4").also { source.copyTo(it, overwrite = true) }
        }
        var width = 0; var height = 0; var duration = 0L; var fps: Int? = null; var frameCount: Int? = null
        var cover: String? = info?.cover?.takeIf { it.isFile }?.let { source ->
            uri(context, File(owned, "cover.jpg").also { source.copyTo(it, overwrite = true) })
        }
        if (type == MediaType.GIF || type == MediaType.LIVE_PHOTO) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            width = options.outWidth.coerceAtLeast(0); height = options.outHeight.coerceAtLeast(0)
        }
        val metadataFile = motion ?: file
        if (type != MediaType.GIF) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(metadataFile.absolutePath)
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                if (type == MediaType.VIDEO) {
                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: width
                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: height
                    val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rotation % 180 != 0) { val swap = width; width = height; height = swap }
                }
                fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()?.toInt()
                if (cover == null) {
                    val scale = minOf(1f, 480f / maxOf(width, height).coerceAtLeast(1))
                    val frame = if (android.os.Build.VERSION.SDK_INT >= 27)
                        retriever.getScaledFrameAtTime(minOf(1_000_000L, duration * 1000), MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
                    else retriever.getFrameAtTime(minOf(1_000_000L, duration * 1000), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    frame?.let { bitmap ->
                        val destination = File(owned, "cover.jpg")
                        destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                        bitmap.recycle(); cover = uri(context, destination)
                    }
                }
            } catch (_: RuntimeException) {
                // Images from a download remain saved; only videos are shown in the video library.
            } finally { retriever.release() }
        } else {
            fps = data.optInt("fps", 15)
            val start = data.optLong("startMs")
            val end = data.optLong("endMs", start + 15_000)
            duration = ((end - start).coerceIn(0, 15_000) / data.optDouble("speed", 1.0)).toLong()
            file.inputStream().buffered().use(ImportedGifMetadata::read)?.let { actual ->
                duration = actual.durationMs; width = actual.width; height = actual.height
                frameCount = actual.frames
            }
            cover = saved.uri.toString()
        }
        val params = JSONObject(task.params).put("savePath", saved.path)
        val platform = if (task.type == TaskType.DOWNLOAD) runCatching { InputParser.parse(task.sourceUrl.orEmpty()).platform.name }.getOrDefault("下载") else "本机创作"
        return MediaItem(id = id, taskId = task.id, type = type,
            title = if (task.type == TaskType.DOWNLOAD) file.nameWithoutExtension else task.title,
            platform = platform, sourceUrl = task.sourceUrl, localUri = saved.uri.toString(),
            coverUri = cover ?: saved.uri.toString().takeIf { type == MediaType.LIVE_PHOTO },
            durationMs = duration, width = width, height = height, sizeBytes = file.length(),
            sourceMediaId = data.takeUnless { it.isNull("mediaId") }?.optString("mediaId")?.takeIf { it.isNotBlank() },
            sourceStartMs = data.optLong("startMs"), sourceEndMs = if (task.type == TaskType.GIF)
                minOf(data.optLong("endMs", data.optLong("startMs") + 15_000), data.optLong("startMs") + 15_000)
                else data.optLong("endMs", duration),
            fps = fps?.takeIf { it > 0 }, frameCount = frameCount,
            params = params.toString(), motionVideoUri = motion?.let { uri(context, it) })
    }
}
