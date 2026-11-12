package cn.secret.downloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException

object MediaPublisher {
    const val FOLDER = "不能说的秘密"
    const val DISPLAY_PATH = "Download/$FOLDER"
    const val LIVE_DISPLAY_PATH = "Pictures/$FOLDER"
    const val GIF_DISPLAY_PATH = "$LIVE_DISPLAY_PATH/GIF"
    data class Published(val uri: android.net.Uri, val path: String)

    internal fun displayPath(source: File): String =
        if (source.extension.equals("gif", true)) GIF_DISPLAY_PATH
        else if (source.name.endsWith("_MP.jpg", ignoreCase = true)) LIVE_DISPLAY_PATH else DISPLAY_PATH

    fun publish(context: Context, source: File): String = publishResult(context, source).path

    fun publishResult(context: Context, source: File): Published {
        require(source.isFile && source.length() > 0) { "下载文件为空" }
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(source.extension.lowercase())
            ?: "application/octet-stream"
        val path = displayPath(source)
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, source.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "$path/")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val collection = if (path == LIVE_DISPLAY_PATH || path == GIF_DISPLAY_PATH) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, values)
                ?: throw IOException("无法创建 $path 中的文件")
            try {
                val output = resolver.openOutputStream(uri) ?: throw IOException("无法写入下载目录")
                output.use { stream -> source.inputStream().use { input -> copy(input, stream) } }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                check(resolver.update(uri, values, null, null) > 0) { "无法完成文件保存" }
                return Published(uri, "$path/${source.name}")
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
        } else {
            @Suppress("DEPRECATION")
            val folder = File(Environment.getExternalStoragePublicDirectory(
                if (path == LIVE_DISPLAY_PATH || path == GIF_DISPLAY_PATH) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS),
                if (path == GIF_DISPLAY_PATH) "$FOLDER/GIF" else FOLDER)
            check(folder.isDirectory || folder.mkdirs()) { "无法创建下载目录，请授予存储权限" }
            var destination: File
            do {
                destination = unusedFile(folder, source.name)
            } while (!destination.createNewFile())
            try {
                source.inputStream().use { input -> destination.outputStream().use { copy(input, it) } }
            } catch (error: Exception) {
                destination.delete()
                throw error
            }
            MediaScannerConnection.scanFile(context, arrayOf(destination.absolutePath), arrayOf(mime), null)
            return Published(android.net.Uri.fromFile(destination), "$path/${destination.name}")
        }
    }

    internal fun unusedFile(folder: File, name: String): File {
        require(name.isNotBlank() && File(name).name == name && name != "." && name != "..")
        val requested = File(folder, name)
        if (!requested.exists()) return requested
        val extension = requested.extension.let { if (it.isEmpty()) "" else ".$it" }
        var sequence = 1
        while (true) {
            val stem = requested.nameWithoutExtension
            val suffix = " (${sequence++})"
            val candidate = File(folder, if (name.endsWith("_MP.jpg"))
                "${stem.removeSuffix("_MP")}${suffix}_MP$extension" else "$stem$suffix$extension")
            if (!candidate.exists()) return candidate
        }
    }

    private fun copy(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("保存已取消")
            val read = input.read(buffer); if (read < 0) return
            output.write(buffer, 0, read)
        }
    }
}
