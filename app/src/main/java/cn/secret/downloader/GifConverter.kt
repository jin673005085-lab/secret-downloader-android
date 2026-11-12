package cn.secret.downloader

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

internal data class GifPlan(
    val startMs: Long,
    val clipMs: Long,
    val fps: Int,
    val width: Int,
    val height: Int,
    val speed: Double,
    val high: Boolean,
    val loop: Int,
    val trimmed: Boolean,
    val streamIndex: Int,
) {
    val outputDurationUs get() = clipMs * 1_000.0 / speed
    private fun seconds(milliseconds: Long) = String.format(Locale.US, "%.6f", milliseconds / 1_000.0)
    private val filter get() = "trim=duration=${seconds(clipMs)},setpts=(PTS-STARTPTS)/$speed,fps=$fps,scale=$width:$height:flags=lanczos"
    private fun inputArguments(path: String) = listOf("-ss", seconds(startMs), "-t", seconds(clipMs), "-i", path)

    fun paletteCommand(input: String, palette: String): List<String> = inputArguments(input) + listOf(
        "-map", "0:$streamIndex", "-an", "-vf", "$filter,palettegen=stats_mode=full", "-frames:v", "1", "-update", "1",
        "-progress", "pipe:1", palette)

    fun outputCommand(input: String, output: String, palette: String? = null): List<String> {
        val command = inputArguments(input).toMutableList()
        if (palette != null) command += listOf("-i", palette, "-filter_complex",
            "[0:$streamIndex]$filter[v];[v][1:v]paletteuse=dither=sierra2_4a:diff_mode=rectangle[gif]", "-map", "[gif]")
        else command += listOf("-map", "0:$streamIndex", "-vf", "$filter,format=rgb8")
        return command + listOf("-an", "-loop", loop.toString(), "-progress", "pipe:1", output)
    }
}

internal object GifParameters {
    fun parse(value: String, durationMs: Long, sourceWidth: Int, sourceHeight: Int, streamIndex: Int = 0): GifPlan {
        require(durationMs > 0 && sourceWidth > 0 && sourceHeight > 0 && streamIndex >= 0) { "视频参数无效" }
        val data = JSONObject(value)
        val start = data.optLong("startMs", 0)
        val end = data.optLong("endMs", durationMs).coerceAtMost(durationMs)
        require(start >= 0 && start < durationMs && end > start) { "GIF 片段范围无效" }
        val fps = data.optInt("fps", 15)
        require(fps in listOf(10, 15, 20)) { "GIF 帧率需为 10、15 或 20" }
        val requestedWidth = data.optInt("width", 480)
        require(requestedWidth in listOf(0, 320, 480, 720)) { "GIF 宽度无效" }
        val width = ((if (requestedWidth == 0) sourceWidth else requestedWidth) / 2 * 2).coerceAtLeast(2)
        val height = ((width.toDouble() * sourceHeight / sourceWidth).toInt() / 2 * 2).coerceAtLeast(2)
        require(width <= 65_534 && height <= 65_534) { "GIF 尺寸过大" }
        val speed = data.optDouble("speed", 1.0)
        require(speed in listOf(.5, 1.0, 2.0)) { "GIF 速度需为 0.5、1 或 2" }
        val quality = data.optString("quality", "standard").trim().lowercase(Locale.ROOT)
        require(quality in listOf("standard", "normal", "标准", "普通", "high", "高", "高清")) { "GIF 画质无效" }
        val loop = when (data.optString("loop", "infinite").trim().lowercase(Locale.ROOT)) {
            "infinite", "无限", "无限循环", "0" -> 0
            "once", "一次", "播放一次", "-1" -> -1
            else -> throw IllegalArgumentException("GIF 循环方式无效")
        }
        return GifPlan(start, (end - start).coerceAtMost(15_000), fps, width, height, speed,
            quality in listOf("high", "高", "高清"), loop, end - start > 15_000, streamIndex)
    }
}

/** FFmpeg may report an earlier timestamp when a pass starts; published progress stays monotonic. */
internal class GifProgress(private val outputDurationUs: Double, private val callback: (Float) -> Unit) {
    init { require(outputDurationUs.isFinite() && outputDurationUs > 0) }
    private var value = -1f
    @Synchronized fun emit(next: Float) {
        val bounded = next.coerceIn(0f, 1f)
        if (bounded > value) { value = bounded; callback(bounded) }
    }
    fun line(line: String, offset: Float, weight: Float) {
        if (!line.startsWith("out_time_us=")) return
        val time = line.substringAfter('=').toLongOrNull() ?: return
        emit(offset + (time / outputDurationUs).coerceIn(0.0, 1.0).toFloat() * weight)
    }
}

object GifConverter {
    fun convert(
        context: Context,
        input: File,
        output: File,
        params: String,
        onProgress: (Float) -> Unit,
        onStatus: (String) -> Unit = {},
    ): File {
        require(input.isFile && input.length() > 0) { "视频文件为空" }
        require(!output.exists() && output.canonicalFile != input.canonicalFile) { "GIF 输出文件已存在" }
        checkInterrupted()
        YoutubeDL.getInstance().init(context.applicationContext)
        FFmpeg.getInstance().init(context.applicationContext)
        onStatus("正在读取视频…")
        onProgress(0f)
        val info = JSONObject(MotionPhotoConverter.run(context, "libffprobe.so", listOf("-v", "error",
            "-show_entries", "stream=index,codec_type,width,height,duration:stream_disposition=attached_pic:stream_tags=rotate:stream_side_data=rotation:format=duration",
            "-of", "json", input.absolutePath)))
        val streams = info.optJSONArray("streams") ?: throw IOException("所选文件没有视频画面")
        val video = (0 until streams.length()).map { streams.getJSONObject(it) }.firstOrNull {
            it.optString("codec_type") == "video" && it.optJSONObject("disposition")?.optInt("attached_pic") != 1
        } ?: throw IOException("所选文件没有视频画面")
        val durationSeconds = info.optJSONObject("format")?.optString("duration")?.toDoubleOrNull()
            ?: video.optString("duration").toDoubleOrNull()
        require(durationSeconds != null && durationSeconds.isFinite() && durationSeconds > 0) { "无法读取视频时长" }
        var width = video.optInt("width")
        var height = video.optInt("height")
        val sideData = video.optJSONArray("side_data_list")
        val rotation = (0 until (sideData?.length() ?: 0)).firstNotNullOfOrNull { index ->
            sideData?.getJSONObject(index)?.takeIf { it.has("rotation") }?.optInt("rotation")
        } ?: video.optJSONObject("tags")?.optString("rotate")?.toIntOrNull() ?: 0
        if (rotation % 180 in listOf(-90, 90)) { val previous = width; width = height; height = previous }
        val plan = GifParameters.parse(params, (durationSeconds * 1_000).toLong().coerceAtLeast(1), width, height, video.getInt("index"))
        val progress = GifProgress(plan.outputDurationUs, onProgress)
        progress.emit(0f)
        if (plan.trimmed) onStatus("所选片段超过 15 秒，已自动截取前 15 秒。")
        var palette: File? = null
        var success = false
        try {
            if (plan.high) {
                palette = File.createTempFile("gif-palette-", ".png", output.parentFile)
                onStatus("正在生成 GIF 调色板…")
                ffmpeg(context, plan.paletteCommand(input.absolutePath, palette.absolutePath)) { progress.line(it, 0f, .5f) }
                checkInterrupted()
                check(palette.length() > 0) { "未生成 GIF 调色板" }
                progress.emit(.5f)
            }
            onStatus("正在编码 GIF…")
            ffmpeg(context, plan.outputCommand(input.absolutePath, output.absolutePath, palette?.absolutePath)) {
                progress.line(it, if (plan.high) .5f else 0f, if (plan.high) .5f else 1f)
            }
            checkInterrupted()
            val header = ByteArray(6)
            val count = if (output.isFile) output.inputStream().use { it.read(header) } else -1
            check(count == 6 && String(header, Charsets.US_ASCII) in listOf("GIF87a", "GIF89a") && output.length() > 6) { "未生成有效 GIF 文件" }
            progress.emit(1f)
            onStatus("GIF 编码完成")
            success = true
            return output
        } catch (error: IOException) {
            throw IOException("GIF 转换失败：${error.message?.removePrefix("实况转换失败：") ?: "编码器错误"}", error)
        } finally {
            palette?.delete()
            if (!success) output.delete()
        }
    }

    private fun ffmpeg(context: Context, arguments: List<String>, onLine: (String) -> Unit) = MotionPhotoConverter.run(context,
        "libffmpeg.so", listOf("-hide_banner", "-loglevel", "error", "-nostdin", "-y") + arguments, onLine)
    private fun checkInterrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedException("转换已停止") }
}
