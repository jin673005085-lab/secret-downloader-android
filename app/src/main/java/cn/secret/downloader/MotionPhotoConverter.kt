package cn.secret.downloader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import kotlin.concurrent.thread
import kotlin.math.roundToInt

object MotionPhotoConverter {
    fun convert(
        context: Context,
        video: File,
        output: File,
        cover: File? = null,
        frameTimestampUs: Long = 0,
        onStatus: (String) -> Unit = {},
        onPrepared: (cover: File, motion: File) -> Unit = { _, _ -> },
    ): File {
        require(video.isFile && video.length() > 0) { "视频文件为空" }
        require(cover == null || (cover.isFile && cover.length() > 0)) { "所选封面图片不存在或为空" }
        require(frameTimestampUs >= 0) { "封面时间无效" }
        require(!output.exists() && output.canonicalFile != video.canonicalFile) { "实况输出文件已存在" }
        YoutubeDL.getInstance().init(context.applicationContext)
        FFmpeg.getInstance().init(context.applicationContext)
        onStatus("正在读取视频…")
        val info = JSONObject(run(context, "libffprobe.so", listOf(
            "-v", "error", "-show_entries",
            "stream=index,codec_type,codec_name,channels,sample_rate:stream_disposition=attached_pic:format=duration",
            "-of", "json", video.absolutePath,
        )))
        val streams = info.getJSONArray("streams")
        val tracks = (0 until streams.length()).map { streams.getJSONObject(it) }
        val videoTrack = tracks.firstOrNull {
            it.optString("codec_type") == "video" && it.optJSONObject("disposition")?.optInt("attached_pic") != 1
        } ?: throw IOException("所选文件没有视频画面")
        val audioTrack = tracks.firstOrNull { it.optString("codec_type") == "audio" }
        val copyVideo = videoTrack.optString("codec_name") in setOf("h264", "hevc")
        val copyAudio = audioTrack == null || (audioTrack.optString("codec_name") == "aac" &&
            audioTrack.optInt("channels") in 1..2 && audioTrack.optInt("sample_rate") in setOf(44100, 48000, 96000))
        val durationUs = (info.optJSONObject("format")?.optString("duration")?.toDoubleOrNull()
            ?.times(1_000_000))?.toLong() ?: 0L
        val movie = File.createTempFile("motion-", ".mp4", output.parentFile)
        val image = File.createTempFile("motion-", ".jpg", output.parentFile)
        try {
            val command = mutableListOf("-i", video.absolutePath, "-map", "0:${videoTrack.getInt("index")}")
            if (audioTrack != null) command += listOf("-map", "0:${audioTrack.getInt("index")}")
            command += listOf("-c:v", if (copyVideo) "copy" else "libx264")
            if (!copyVideo) command += listOf("-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p", "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2")
            if (copyVideo && videoTrack.optString("codec_name") == "hevc") command += listOf("-tag:v", "hvc1")
            command += listOf("-c:a", if (copyAudio) "copy" else "aac")
            if (!copyAudio) command += listOf("-b:a", "192k", "-ac", "2", "-ar", "48000")
            // No trim, frame-count, shortest-stream or duration option: the entire input is retained.
            command += listOf("-movflags", "+faststart", "-progress", "pipe:1", movie.absolutePath)
            val stage = if (copyVideo) "正在封装完整视频" else "正在转换完整视频"
            onStatus("$stage…")
            var lastPercent = -1L
            ffmpeg(context, command) { line ->
                if (line.startsWith("out_time_us=") && durationUs > 0) {
                    val time = line.substringAfter('=').toLongOrNull() ?: return@ffmpeg
                    val percent = (time.toDouble() / durationUs * 100).toLong().coerceIn(0, 100)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onStatus("$stage $percent%")
                    }
                }
            }
            check(movie.length() > 0) { "未生成完整视频" }
            onStatus("正在生成实况封面…")
            if (cover != null) {
                val (width, height) = displaySize(context, movie)
                ffmpeg(context, listOf("-i", cover.absolutePath, "-vf", MotionPhotoCover.filter(width, height),
                    "-frames:v", "1", "-q:v", "2", image.absolutePath))
            } else {
                val bitmap = try {
                    frame(video, frameTimestampUs)
                } catch (_: RuntimeException) {
                    // A transcoded MP4 can be decoded even when the source codec is unsupported by Android.
                    frame(movie, frameTimestampUs)
                }
                try {
                    image.outputStream().use { stream ->
                        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) { "无法写入所选封面" }
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            check(image.length() > 0) { "所选时间没有可读取的画面，请选择稍早的视频帧" }
            onStatus("正在写入实况图片…")
            val result = MotionPhotoWriter.write(image, movie, output, if (cover != null) -1 else frameTimestampUs)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("转换已停止")
            onPrepared(image, movie)
            return result
        } finally {
            movie.delete()
            image.delete()
        }
    }

    private fun displaySize(context: Context, movie: File): Pair<Int, Int> {
        val info = JSONObject(run(context, "libffprobe.so", listOf("-v", "error", "-show_entries",
            "stream=codec_type,width,height:stream_tags=rotate:stream_side_data=rotation", "-of", "json", movie.absolutePath)))
        val streams = info.getJSONArray("streams")
        val video = (0 until streams.length()).map { streams.getJSONObject(it) }.first { it.optString("codec_type") == "video" }
        val width = video.getInt("width"); val height = video.getInt("height")
        require(width > 0 && height > 0) { "无法读取实况视频的画面比例" }
        val sideData = video.optJSONArray("side_data_list")
        val rotation = (0 until (sideData?.length() ?: 0)).firstNotNullOfOrNull { index ->
            sideData?.getJSONObject(index)?.takeIf { it.has("rotation") }?.optInt("rotation")
        } ?: video.optJSONObject("tags")?.optString("rotate")?.toIntOrNull() ?: 0
        return if (rotation % 180 == 0) width to height else height to width
    }

    private fun frame(video: File, timestampUs: Long): Bitmap {
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(video.absolutePath)
            return checkNotNull(reader.getFrameAtTime(timestampUs, MediaMetadataRetriever.OPTION_CLOSEST)) {
                "无法提取所选视频画面"
            }
        } finally {
            runCatching { reader.release() }
        }
    }

    private fun ffmpeg(context: Context, arguments: List<String>, onLine: (String) -> Unit = {}) {
        run(context, "libffmpeg.so", listOf("-hide_banner", "-loglevel", "error", "-nostdin", "-y") + arguments, onLine)
    }

    internal fun run(context: Context, binary: String, arguments: List<String>, onLine: (String) -> Unit = {}): String {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("转换已停止")
        val root = File(context.noBackupFilesDir, "youtubedl-android/packages")
        val process = ProcessBuilder(listOf(File(context.applicationInfo.nativeLibraryDir, binary).absolutePath) + arguments)
            .redirectErrorStream(true).apply {
                environment()["LD_LIBRARY_PATH"] = listOf("python", "ffmpeg", "aria2c").joinToString(":") {
                    File(root, "$it/usr/lib").absolutePath
                }
                environment()["TMPDIR"] = context.cacheDir.absolutePath
            }.start()
        val reader = FutureTask(Callable {
            val log = StringBuilder()
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    onLine(line)
                    log.append(line).append('\n')
                    if (log.length > 262_144) log.delete(0, log.length - 262_144)
                }
            }
            log.toString()
        })
        thread(name = "motion-photo-process", isDaemon = true) { reader.run() }
        try {
            val code = process.waitFor()
            val result = reader.get()
            if (code != 0) throw IOException("实况转换失败：${result.trim().takeLast(1000)}")
            return result
        } finally {
            process.destroy()
            reader.cancel(true)
        }
    }
}

/** A custom cover keeps all its content inside the video's display aspect ratio. */
internal object MotionPhotoCover {
    fun filter(width: Int, height: Int): String {
        require(width > 0 && height > 0)
        return "scale=$width:$height:force_original_aspect_ratio=decrease," +
            "pad=$width:$height:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1"
    }

    fun preview(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        require(width > 0 && height > 0)
        val sample = minOf(1.0, 1280.0 / maxOf(width, height))
        val targetWidth = (width * sample).roundToInt().coerceAtLeast(1)
        val targetHeight = (height * sample).roundToInt().coerceAtLeast(1)
        val scale = minOf(targetWidth.toFloat() / bitmap.width, targetHeight.toFloat() / bitmap.height)
        val left = (targetWidth - bitmap.width * scale) / 2
        val top = (targetHeight - bitmap.height * scale) / 2
        return Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).apply {
                drawColor(Color.BLACK)
                drawBitmap(bitmap, null, RectF(left, top, left + bitmap.width * scale, top + bitmap.height * scale), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }
        }
    }
}
