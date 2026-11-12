package cn.secret.downloader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** File access and decoding stay off the scrolling/UI thread. */
object MediaThumbnails {
    private val worker = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val images = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private class Binding(val key: String) { val canceled = AtomicBoolean() }
    private val bindings = java.util.WeakHashMap<ImageView, Binding>()
    internal data class VideoInfo(val durationMs: Long, val width: Int, val height: Int, val coverUri: String?)

    /** Keeps the previous cover for the same binding and rejects results for recycled cells. */
    fun bind(view: ImageView, item: MediaItem, fallback: android.graphics.drawable.Drawable) {
        val key = "${item.id}|${item.coverUri}|${item.localUri}|${item.motionVideoUri}|${item.sizeBytes}|${item.exists}"
        if (bindings[view]?.key == key) return
        clear(view)
        val binding = Binding(key)
        bindings[view] = binding
        view.setImageDrawable(fallback)
        if (!item.exists) return
        // A saved cover can be missing even when the work itself is readable (older records, cleaned
        // caches), so every readable address is tried before the placeholder is kept.
        val addresses = listOfNotNull(item.coverUri, item.localUri, item.motionVideoUri)
            .filter { Uri.parse(it).scheme in setOf("content", "file", "android.resource") }
            .distinct()
        if (addresses.isEmpty()) return
        synchronized(images) { images.get(key) }?.let { view.setImageDrawable(BitmapDrawable(view.resources, it)); return }
        val context = view.context.applicationContext
        worker.execute {
            if (binding.canceled.get()) return@execute
            val bitmap = addresses.firstNotNullOfOrNull { address ->
                if (binding.canceled.get()) return@firstNotNullOfOrNull null
                val uri = Uri.parse(address)
                runCatching { decode(context, uri) }.getOrNull()
                    ?: if (item.type == MediaType.GIF) null else runCatching {
                        probeVideo(context, uri, null, "$key|$address")?.coverUri?.let { decode(context, Uri.parse(it)) }
                    }.getOrNull()
            } ?: return@execute
            if (binding.canceled.get()) { bitmap.recycle(); return@execute }
            synchronized(images) { images.put(key, bitmap) }
            main.post { if (bindings[view] === binding && !binding.canceled.get()) view.setImageDrawable(BitmapDrawable(view.resources, bitmap)) }
        }
    }

    fun clear(view: ImageView) { bindings.remove(view)?.canceled?.set(true) }

    internal fun probeVideo(context: Context, uri: Uri, existingCover: String?, key: String): VideoInfo? {
        if (uri.scheme !in setOf("content", "file", "android.resource")) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (width <= 0 || height <= 0) return null
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) { val swap = width; width = height; height = swap }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceAtLeast(0) ?: 0
            val cover = existingCover ?: runCatching {
                val directory = File(context.cacheDir, "media_thumbnails").apply { mkdirs() }
                val file = File(directory, "${cacheKey(key)}.jpg")
                if (!file.isFile) {
                    val scale = minOf(1f, 384f / maxOf(width, height))
                    val frame = if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(
                        1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1)
                    ) else retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    frame?.let { original ->
                        val small = scaled(original)
                        val temporary = File.createTempFile("thumb-", ".part", directory)
                        try {
                            temporary.outputStream().buffered().use { check(small.compress(Bitmap.CompressFormat.JPEG, 82, it)) }
                            check(temporary.renameTo(file) || file.isFile)
                        } finally { temporary.delete(); if (small !== original) small.recycle(); original.recycle() }
                    }
                }
                file.takeIf { it.isFile && it.length() > 0 }?.let { Uri.fromFile(it).toString() }
            }.getOrNull()
            VideoInfo(duration, width, height, cover)
        } catch (error: SecurityException) { throw error }
        catch (_: Exception) { null }
        finally { runCatching { retriever.release() } }
    }

    private fun scaled(source: Bitmap): Bitmap {
        val scale = minOf(1f, 384f / maxOf(source.width, source.height))
        return if (scale >= 1) source else Bitmap.createScaledBitmap(source,
            (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true)
    }

    private fun decode(context: Context, uri: Uri): Bitmap? {
        if (uri.scheme !in setOf("content", "file", "android.resource")) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 768) sample *= 2
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 })
        }
    }

    internal fun imageSize(context: Context, uri: Uri): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
    }

    internal fun cacheKey(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
