package cn.secret.downloader

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException

internal object InstagramCookies {
    private const val HEADER = "# Netscape HTTP Cookie File"
    private const val MAX_BYTES = 256 * 1024
    private fun store(context: Context) = AtomicFile(File(context.noBackupFilesDir, "instagram-cookies.txt"))

    internal fun sanitize(text: String): String {
        require(text.lineSequence().firstOrNull()?.removePrefix("\uFEFF") in setOf(HEADER, "# HTTP Cookie File")) {
            "请选择 Netscape 格式的 Cookie 文件"
        }
        val rows = text.lineSequence().drop(1).mapNotNull { line ->
            val fields = line.split('\t')
            val domain = fields.first().removePrefix("#HttpOnly_")
            if (fields.size != 7 || fields[3] != "TRUE" || !fields[2].startsWith('/') ||
                fields[4].toLongOrNull()?.let { it >= 0 } != true ||
                !fields[5].matches(Regex("[A-Za-z0-9_]+")) || fields[6].isEmpty() ||
                line.any { it.code < 32 && it != '\t' || it.code == 127 }) return@mapNotNull null
            when (domain) {
                ".instagram.com" -> line.takeIf { fields[1] == "TRUE" }
                "www.instagram.com", "instagram.com" -> line.takeIf { fields[1] == "FALSE" }
                else -> null
            }
        }.toList()
        require(rows.any { it.split('\t')[5] == "sessionid" }) { "文件中缺少可用的 Instagram 登录 Cookie" }
        return (listOf(HEADER) + rows).joinToString("\n", postfix = "\n")
    }

    @Synchronized fun import(context: Context, uri: Uri) {
        val input = context.contentResolver.openInputStream(uri) ?: error("无法读取 Cookie 文件")
        val bytes = ByteArray(MAX_BYTES + 1)
        val count = input.use {
            var offset = 0
            while (offset < bytes.size) {
                val read = it.read(bytes, offset, bytes.size - offset)
                if (read == -1) break
                offset += read
            }
            offset
        }
        require(count <= MAX_BYTES) { "Cookie 文件过大" }
        val cleaned = sanitize(String(bytes, 0, count, Charsets.UTF_8))
        val file = store(context)
        val output = file.startWrite()
        try {
            output.write(cleaned.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    @Synchronized fun clear(context: Context) = store(context).delete()

    @Synchronized fun copyForExtractor(context: Context): File? {
        val bytes = try { store(context).readFully() } catch (_: FileNotFoundException) { return null }
        // yt-dlp writes the cookie jar back to its path after extraction.
        val copy = File.createTempFile("instagram-", ".txt", context.cacheDir)
        try { copy.writeBytes(bytes); return copy }
        catch (error: Exception) { copy.delete(); throw error }
    }
}
