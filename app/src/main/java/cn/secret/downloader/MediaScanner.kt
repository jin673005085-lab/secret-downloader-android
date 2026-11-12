package cn.secret.downloader

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** Read-only import of this application's public save folders. Never requests storage permissions. */
object MediaScanner {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private val callbacks = mutableListOf<(String) -> Unit>()
    private const val AUTHORIZATION = "部分文件无法访问，请通过系统目录选择器授权对应保存目录；无需开启所有文件访问权限。"
    private data class Row(val uri: Uri, val aliases: MutableSet<String>, val type: MediaType,
                           val name: String, val size: Long, val created: Long, val modified: Long)
    private data class Scanned(val previousId: String?, val item: MediaItem)
    private enum class Presence { PRESENT, MISSING, INACCESSIBLE }

    fun scan(context: Context, repository: MediaRepository, onResult: (String) -> Unit) {
        val app = context.applicationContext
        main.post {
            callbacks += onResult
            if (running) return@post
            running = true
            val launched = AtomicBoolean()
            var waiting: Closeable? = null
            fun start() {
                if (!repository.isLoaded || !launched.compareAndSet(false, true)) return
                main.post { waiting?.close() }
                val existing = repository.items
                worker.execute { perform(app, repository, existing) { message ->
                    val listeners = callbacks.toList(); callbacks.clear(); running = false
                    listeners.forEach { it(message) }
                } }
            }
            if (repository.isLoaded) start() else waiting = repository.observeAll { start() }
        }
    }

    private fun perform(context: Context, repository: MediaRepository, existing: List<MediaItem>, onResult: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < 29) {
            main.post { onResult("当前 Android 版本需要通过系统目录选择器授权保存目录后导入，不会申请整个存储空间权限。") }
            return
        }
        val messages = linkedSetOf<String>()
        val rows = linkedMapOf<String, Row>()
        var skipped = 0
        try {
            for (volume in MediaStore.getExternalVolumeNames(context)) {
                val videos = listOf(MediaStore.Downloads.getContentUri(volume), MediaStore.Video.Media.getContentUri(volume))
                for (collection in videos) collect(context, collection, volume, MediaType.VIDEO, MediaImportRules.DOWNLOAD, rows, messages)
                val images = MediaStore.Images.Media.getContentUri(volume)
                collect(context, images, volume, MediaType.GIF, MediaImportRules.GIF, rows, messages)
                collect(context, images, volume, MediaType.LIVE_PHOTO, MediaImportRules.LIVE, rows, messages)
            }
        } catch (_: SecurityException) { messages += AUTHORIZATION }
        catch (_: Exception) { messages += "部分保存目录暂时不可用，请稍后下拉刷新。" }
        val byUri = existing.mapNotNull { item -> item.localUri?.let { it to item } }.toMap()
        val scanned = mutableListOf<Scanned>()
        for (row in rows.values) {
            val previous = row.aliases.firstNotNullOfOrNull(byUri::get)
            try {
                val uri = previous?.localUri?.let(Uri::parse) ?: row.uri
                val size = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
                    ?.takeIf { it >= 0 } ?: row.size
                val key = "$uri|$size|${row.modified}"
                var motion: String? = null
                var gifInfo: ImportedGifMetadata.Info? = null
                val info = when (row.type) {
                    MediaType.VIDEO -> MediaThumbnails.probeVideo(context, uri, previous?.coverUri, key)
                    MediaType.GIF -> context.contentResolver.openInputStream(uri)?.buffered()?.use(ImportedGifMetadata::read)?.let {
                        gifInfo = it
                        MediaThumbnails.VideoInfo(it.durationMs, it.width, it.height, previous?.coverUri ?: uri.toString())
                    }
                    MediaType.LIVE_PHOTO -> {
                        motion = previous?.motionVideoUri?.takeIf { presence(context, it) == Presence.PRESENT }?.let {
                            if (Uri.parse(it).scheme == "file") runCatching { FileProvider.getUriForFile(context,
                                "${context.packageName}.files", File(Uri.parse(it).path!!)).toString() }.getOrNull() else it
                        }
                            ?: extractMotion(context, uri, size, key)?.let {
                                FileProvider.getUriForFile(context, "${context.packageName}.files", it).toString()
                            }
                        motion?.let { MediaThumbnails.probeVideo(context, Uri.parse(it), previous?.coverUri ?: uri.toString(), key) }?.let { video ->
                            MediaThumbnails.imageSize(context, uri)?.let { video.copy(width = it.first, height = it.second) } ?: video
                        }
                    }
                }
                if (info == null) {
                    skipped++
                    if (row.type == MediaType.LIVE_PHOTO) messages += "已跳过无法确认标准动态片段的图片，普通 JPEG 和未知厂商私有格式不会作为实况导入。"
                    continue
                }
                val base = previous ?: MediaItem(id = "import-${MediaThumbnails.cacheKey(uri.toString())}",
                    type = row.type, title = row.name.substringBeforeLast('.').ifBlank { row.name.ifBlank { "本机作品" } },
                    platform = "本机导入", localUri = uri.toString(), downloadedAt = row.created)
                scanned += Scanned(previous?.id, base.copy(type = row.type, coverUri = info.coverUri,
                    durationMs = info.durationMs, width = info.width, height = info.height,
                    sizeBytes = size, exists = true, motionVideoUri = motion ?: base.motionVideoUri,
                    frameCount = base.frameCount ?: gifInfo?.frames,
                    fps = base.fps ?: gifInfo?.takeIf { it.durationMs > 0 }?.let { (it.frames * 1000.0 / it.durationMs).roundToInt().coerceAtLeast(1) }))
            } catch (_: SecurityException) { messages += AUTHORIZATION; skipped++ }
            catch (_: Exception) { skipped++ }
        }
        val presenceChecks = existing.mapNotNull { item ->
            item.localUri?.takeIf { Uri.parse(it).scheme in setOf("content", "file") }
                ?.let { item.id to presence(context, it) }
        }
        if (presenceChecks.any { it.second == Presence.INACCESSIBLE }) messages += AUTHORIZATION
        main.post {
            var added = 0; var updated = 0; var missing = 0
            for (entry in scanned) {
                val fresh = entry.item
                val current = repository.get(entry.previousId ?: fresh.id)
                    ?: repository.items.firstOrNull { it.localUri == fresh.localUri }
                // A record explicitly removed while this scan ran must not reappear.
                if (entry.previousId != null && current == null) continue
                if (current == null) { repository.add(fresh); added++ }
                else {
                    val merged = MediaImportRules.merge(current, fresh)
                    if (merged != current) { repository.update(merged); updated++ }
                }
            }
            for ((id, state) in presenceChecks) repository.get(id)?.let { current ->
                if (state == Presence.MISSING) { missing++; if (current.exists) repository.update(current.copy(exists = false)) }
                else if (state == Presence.PRESENT && !current.exists) repository.update(current.copy(exists = true))
            }
            onResult(buildString {
                append("扫描完成：导入 $added 项，更新 $updated 项")
                if (missing > 0) append("，$missing 项文件不存在")
                if (skipped > 0) append("，跳过 $skipped 项")
                append("。")
                if (messages.isNotEmpty()) append(messages.joinToString(" "))
            })
        }
    }

    private fun collect(context: Context, collection: Uri, volume: String, type: MediaType, prefix: String,
                        rows: MutableMap<String, Row>, messages: MutableSet<String>) {
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.MIME_TYPE)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0"
        try {
            context.contentResolver.query(collection, columns, selection, arrayOf("$prefix%", context.packageName), null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.string(MediaStore.MediaColumns.DISPLAY_NAME)
                    val path = cursor.string(MediaStore.MediaColumns.RELATIVE_PATH)
                    val mime = cursor.string(MediaStore.MediaColumns.MIME_TYPE)
                    if (!MediaImportRules.matches(type, path, name, mime)) continue
                    val id = cursor.number(MediaStore.MediaColumns._ID)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val key = "$volume:$id"
                    val aliases = mutableSetOf(uri.toString(), uri.buildUpon()
                        .path(uri.path!!.replaceFirst("/$volume/", "/external/")).build().toString())
                    rows[key]?.let { it.aliases += aliases } ?: run {
                        rows[key] = Row(uri, aliases, type, name,
                            cursor.number(MediaStore.MediaColumns.SIZE).coerceAtLeast(0),
                            cursor.number(MediaStore.MediaColumns.DATE_ADDED).coerceAtLeast(0) * 1000,
                            cursor.number(MediaStore.MediaColumns.DATE_MODIFIED))
                    }
                }
            }
        } catch (_: SecurityException) { messages += AUTHORIZATION }
        catch (_: Exception) { messages += "部分保存目录暂时不可用，请稍后下拉刷新。" }
    }

    private fun presence(context: Context, value: String): Presence = try {
        val uri = Uri.parse(value)
        when (uri.scheme) {
            "file" -> if (uri.path?.let { File(it).isFile } == true) Presence.PRESENT else Presence.MISSING
            "content" -> context.contentResolver.openFileDescriptor(uri, "r")?.use { Presence.PRESENT } ?: Presence.MISSING
            else -> Presence.INACCESSIBLE
        }
    } catch (_: SecurityException) { Presence.INACCESSIBLE }
    catch (_: FileNotFoundException) { Presence.MISSING }
    catch (_: Exception) { Presence.INACCESSIBLE }

    private fun extractMotion(context: Context, uri: Uri, size: Long, key: String): File? {
        val segment = context.contentResolver.openInputStream(uri)?.buffered()?.use { StandardMotionPhoto.readJpeg(it, size) } ?: return null
        val directory = File(context.filesDir, "works/imported_motion").apply { check(mkdirs() || isDirectory) }
        val target = File(directory, "${MediaThumbnails.cacheKey(key)}.mp4")
        if (target.isFile && target.length() == segment.length) return target
        val temporary = File.createTempFile("motion-", ".part", directory)
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { source ->
                    source.channel.position(segment.offset)
                    val header = ByteArray(12)
                    java.io.DataInputStream(source).readFully(header)
                    if (String(header, 4, 4, Charsets.US_ASCII) != "ftyp") return null
                    temporary.outputStream().buffered().use { output ->
                        output.write(header)
                        var remaining = segment.length - header.size
                        val bytes = ByteArray(64 * 1024)
                        while (remaining > 0) {
                            val count = source.read(bytes, 0, minOf(bytes.size.toLong(), remaining).toInt())
                            check(count > 0) { "实况动态片段不完整" }
                            output.write(bytes, 0, count); remaining -= count
                        }
                    }
                }
            } ?: return null
            check(temporary.length() == segment.length && temporary.renameTo(target))
            return target
        } finally { temporary.delete() }
    }

    private fun Cursor.string(column: String): String = getColumnIndex(column).let { if (it < 0 || isNull(it)) "" else getString(it) }
    private fun Cursor.number(column: String): Long = getColumnIndex(column).let { if (it < 0 || isNull(it)) 0 else getLong(it) }
}
