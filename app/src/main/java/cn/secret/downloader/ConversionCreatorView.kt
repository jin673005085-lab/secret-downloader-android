package cn.secret.downloader

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.util.SparseArray
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem as PlaybackItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

internal object ConversionEstimate {
    fun frames(durationMs: Long, fps: Int, speed: Double): Long {
        require(durationMs >= 0 && fps > 0 && speed.isFinite() && speed > 0)
        return ceil(durationMs / 1_000.0 / speed * fps).toLong()
    }
    fun bytes(frames: Long, width: Int, height: Int, high: Boolean): Long {
        require(frames >= 0 && width > 0 && height > 0)
        return (frames.toDouble() * width * height * if (high) .20 else .12).toLong()
    }
    fun range(duration: Long, start: Long, end: Long): Pair<Long, Long> {
        require(duration > 0)
        val left = start.coerceIn(0, duration - 1)
        return left to end.coerceIn(left + 1, duration)
    }
    fun gifRange(duration: Long, start: Long, end: Long): Pair<Long, Long> = range(duration, start, end).let { (left, right) ->
        left to if (right - left > 15_000) left + 15_000 else right
    }
}

/** Parameter editor only. Creating a task never starts the legacy converter. */
@androidx.annotation.OptIn(UnstableApi::class)
class ConversionCreatorView(
    private val activity: Activity,
    private val theme: UiTheme,
    private val mediaRepo: MediaRepository,
    private val tasks: TaskRepository,
    private val type: TaskType,
    initialMedia: MediaItem? = null,
    startMs: Long = 0,
    private val editWork: MediaItem? = null,
    private val onBack: () -> Unit,
    private val onCreated: (Task) -> Unit,
) : FrameLayout(activity) {
    private val live = type == TaskType.LIVE_PHOTO
    private var source = initialMedia
    private var durationMs = initialMedia?.durationMs?.coerceAtLeast(1) ?: 1
    private var start = if (live) 0 else startMs.coerceAtLeast(0)
    private var end = if (live) durationMs else minOf(durationMs, start + 5_000)
    private var fps = 15
    private var outputWidth = 480
    private var quality = "standard"
    private var loop = "infinite"
    private var speed = 1.0
    private var coverTimestampMs = startMs.coerceAtLeast(0)
    private var coverUri: Uri? = null
    private var coverBitmap: Bitmap? = null
    private var frames = emptyList<Bitmap>()
    private var worker: ExecutorService? = null
    private var loadJob: Future<*>? = null
    private var coverJob: Future<*>? = null
    private var generation = 0
    private var coverGeneration = 0
    private var sourceReady = false
    private var coverReady = true
    private var engine: ExoPlayer? = null
    private var previewPrepared = false
    private val main = Handler(Looper.getMainLooper())
    private val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(110)) }
    private val scroll = ScrollView(activity).apply { isFillViewport = true; addView(body); isVerticalScrollBarEnabled = false }
    private val settings = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val sourceLabel = text("", 15f, true)
    private val message = text("", 13f)
    /** Texture-backed, so the preview honours the page transition instead of punching a black hole. */
    private val preview = (LayoutInflater.from(activity).inflate(R.layout.view_creator_preview, this, false) as PlayerView).apply {
        tag = "creator_video_preview"
    }
    private val poster = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "视频或所选实况封面" }
    private val play = button("播放所选片段", "creator_play") { playPreview() }
    private val rangeLabel = text("", 15f, true)
    private val estimate = text("", 13f, true).apply { tag = "creator_estimate" }
    private val timeline = RangeTimeline()
    private val optionRows = mutableListOf<Pair<LinearLayout, () -> String>>()
    private val coverLabel = text("", 14f)
    private val coverSeek = SeekBar(activity).apply { max = 10_000; minimumHeight = dp(48); contentDescription = "选择视频封面画面，不裁剪实况时长" }
    private val create = button(if (live) "创建实况任务" else "创建 GIF 任务", "creator_create", true) { createTask() }
    private val pendingCover = Runnable { loadCover() }
    private val previewPlaying: Boolean get() = engine?.isPlaying == true
    private val previewPosition: Long get() = engine?.currentPosition?.coerceAtLeast(0) ?: start
    private val previewTick = object : Runnable {
        override fun run() {
            if (previewPlaying && !live && previewPosition >= end) {
                if (loop == "infinite") seekPreview(start)
                else { pausePreview(); play.text = "播放所选片段" }
            }
            if (isAttachedToWindow && isShown && play.text == "暂停预览") main.postDelayed(this, 100)
        }
    }

    init {
        require(type == TaskType.GIF || type == TaskType.LIVE_PHOTO)
        id = R.id.conversion_creator
        tag = if (live) "route_CREATE_LIVE" else "route_CREATE_GIF"
        setBackgroundColor(theme.background)
        val params = (editWork?.params?.takeIf { it != "{}" } ?: editWork?.taskId?.let(tasks::get)?.params)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (params != null) restoreParams(params)
        body.addView(row().apply {
            addView(button("‹", "route_back") { leavePage() }.apply { contentDescription = "返回" }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(text(if (live) "视频转实况" else "视频转 GIF", 24f, true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        })
        body.addView(button("＋ 选择本地视频", "creator_pick_video") { pickVideo() }.apply {
            background = theme.shape(theme.surface, 20).apply { setStroke(dp(1), theme.primary, dp(7).toFloat(), dp(5).toFloat()) }
            minHeight = dp(88)
        }, gap(18))
        body.addView(sourceLabel, gap(12))
        settings.addView(FrameLayout(activity).apply {
            background = theme.shape(theme.muted, 20); clipToOutline = true
            addView(preview, LayoutParams(-1, -1, Gravity.CENTER))
            addView(poster, LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(-1, dp(230)).apply { topMargin = dp(14) })
        settings.addView(play, gap(10))
        if (!live) buildGifControls() else buildLiveControls()
        settings.addView(message, gap(14))
        body.addView(settings)
        addView(scroll, LayoutParams(-1, -1))
        addView(LinearLayout(activity).apply {
            setPadding(dp(20), dp(12), dp(20), dp(12)); setBackgroundColor(theme.surface)
            addView(create, LinearLayout.LayoutParams(-1, dp(52)))
        }, LayoutParams(-1, -2, Gravity.BOTTOM))
        refresh()
    }

    /** ExoPlayer replaces VideoView here: MediaPlayer offers no surface we can animate or clip. */
    private val previewListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    if (!previewPrepared) {
                        previewPrepared = true
                        engine?.seekTo(start)
                        applySpeed()
                    }
                    if (play.text == "暂停预览") { main.removeCallbacks(previewTick); main.postDelayed(previewTick, 100) }
                }
                Player.STATE_ENDED -> {
                    if (!live && loop == "infinite") { engine?.seekTo(start); engine?.play() }
                    else { play.text = "播放所选片段"; main.removeCallbacks(previewTick) }
                }
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            poster.visibility = VISIBLE; play.text = "播放所选片段"; main.removeCallbacks(previewTick)
            message.text = if (sourceReady) "此视频无法在本应用内预览，任务参数仍然可用。"
                else "此视频暂时无法预览，仍可保存演示任务参数。"
        }
    }

    private fun preparePreview(uri: Uri) {
        val player = engine ?: ExoPlayer.Builder(activity).build().also {
            engine = it
            it.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            it.setHandleAudioBecomingNoisy(true)
            it.addListener(previewListener)
            it.volume = 1f
            preview.player = it
        }
        previewPrepared = false
        player.setMediaItem(PlaybackItem.fromUri(uri))
        player.prepare()
    }

    private fun startPreview() { engine?.play() }
    private fun pausePreview() { engine?.pause() }
    private fun seekPreview(position: Long) { engine?.seekTo(position.coerceAtLeast(0)) }

    /** Stops playback and drops the media so no stale frame survives a page transition. */
    private fun stopPreview() {
        previewPrepared = false
        main.removeCallbacks(previewTick)
        engine?.let { it.pause(); it.clearMediaItems() }
        poster.visibility = VISIBLE; play.text = "播放所选片段"
    }

    private fun releasePreview() {
        previewPrepared = false
        main.removeCallbacks(previewTick)
        val player = engine
        engine = null
        preview.player = null
        player?.removeListener(previewListener)
        player?.release()
    }

    private fun buildGifControls() {
        settings.addView(text("选择片段", 18f, true), gap(22))
        settings.addView(timeline, LinearLayout.LayoutParams(-1, dp(78)).apply { topMargin = dp(12) })
        settings.addView(rangeLabel, gap(8))
        settings.addView(text("拖动左右把手选择范围；GIF 最长 15 秒，超出后自动截取。", 12f), gap(6))
        options("帧率", listOf("10 fps" to "10", "15 fps" to "15", "20 fps" to "20"), { fps.toString() }) { fps = it.toInt() }
        options("输出宽度", listOf("320" to "320", "480" to "480", "720" to "720", "原始" to "0"), { outputWidth.toString() }) { outputWidth = it.toInt() }
        options("画质", listOf("标准" to "standard", "高" to "high"), { quality }) { quality = it }
        options("循环", listOf("无限循环" to "infinite", "播放一次" to "once"), { loop }) { loop = it }
        options("速度", listOf("0.5×" to "0.5", "1×" to "1.0", "2×" to "2.0"), { speed.toString() }) { speed = it.toDouble(); applySpeed() }
        settings.addView(estimate, gap(18))
    }

    private fun buildLiveControls() {
        settings.addView(text("选择实况封面", 18f, true), gap(20))
        settings.addView(text("保留完整视频和声音，不限制实况时长。相册封面会按视频比例补黑边，保留完整图片。", 14f), gap(8))
        settings.addView(coverLabel, gap(14))
        settings.addView(coverSeek, gap(8))
        coverSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                coverTimestampMs = (durationMs.toDouble() * progress / 10_000).toLong().coerceIn(0, (durationMs - 1).coerceAtLeast(0))
                coverUri = null; coverGeneration++
                updateCoverLabel(); main.removeCallbacks(pendingCover); main.postDelayed(pendingCover, 150)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) { pausePreview() }
            override fun onStopTrackingTouch(bar: SeekBar?) { main.removeCallbacks(pendingCover); loadCover() }
        })
        settings.addView(row().apply {
            addView(button("从视频选帧", "creator_cover_frame") { coverUri = null; updateCoverLabel(); loadCover() }, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(button("从相册选图", "creator_cover_album") { pickImage() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        }, gap(10))
    }

    private fun options(label: String, values: List<Pair<String, String>>, selected: () -> String, changed: (String) -> Unit) {
        settings.addView(text(label, 14f, true), gap(18))
        val group = row()
        val key = when (label) { "帧率" -> "fps"; "输出宽度" -> "width"; "画质" -> "quality"; "循环" -> "loop"; else -> "speed" }
        values.forEachIndexed { index, (title, value) ->
            group.addView(button(title, "creator_${key}_$value") { changed(value); refresh() },
                LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(6) })
        }
        optionRows += group to selected
        settings.addView(group, gap(8))
    }

    private fun refresh() {
        val item = source
        settings.visibility = if (item == null) GONE else VISIBLE
        create.isEnabled = item != null && sourceReady && (coverUri == null || coverReady)
        create.alpha = if (create.isEnabled) 1f else .5f
        sourceLabel.text = item?.let { "已选择：${it.title}" } ?: "选择一个视频，开始创作。"
        if (item == null) return
        if (live) { start = 0; end = durationMs } else {
            val bounded = ConversionEstimate.range(durationMs, start, end); start = bounded.first; end = bounded.second
        }
        timeline.setRange(start.toDouble() / durationMs, end.toDouble() / durationMs)
        val selectedMs = end - start
        rangeLabel.text = "${time(start)} — ${time(end)} · ${String.format(Locale.getDefault(), "%.1f", selectedMs / 1_000.0)} 秒"
        rangeLabel.setTextColor(if (selectedMs > 15_000) theme.warning else theme.text)
        val width = outputWidth.takeIf { it > 0 } ?: item.width.takeIf { it > 0 } ?: 480
        val height = (width.toDouble() * (item.height.takeIf { it > 0 } ?: 1080) / (item.width.takeIf { it > 0 } ?: 1920)).roundToInt().coerceAtLeast(1)
        val frameCount = ConversionEstimate.frames(selectedMs, fps, speed)
        val size = ConversionEstimate.bytes(frameCount, width, height, quality == "high")
        estimate.text = "约 $frameCount 帧 · 估算 ${TaskUi.bytes(size)}\n实际大小取决于画面内容。"
        estimate.setTextColor(when { size > 30_000_000 -> theme.error; size > 10_000_000 -> theme.warning; else -> theme.secondary })
        optionRows.forEach { (group, selected) ->
            for (index in 0 until group.childCount) {
                val button = group.getChildAt(index) as TextView
                val active = button.tag.toString().substringAfterLast('_') == selected()
                button.isSelected = active; button.setTextColor(if (active) theme.onPrimary else theme.primaryText)
                button.background = theme.control(if (active) theme.primary else theme.primarySoft, 12)
            }
        }
        updateCoverLabel()
    }

    private fun updateCoverLabel() {
        coverTimestampMs = coverTimestampMs.coerceIn(0, (durationMs - 1).coerceAtLeast(0))
        coverLabel.text = if (coverUri != null) "相册图片封面 · 按视频比例补边 · 完整视频 ${time(durationMs)}" else "视频封面 ${time(coverTimestampMs)} / ${time(durationMs)}"
        coverSeek.progress = (coverTimestampMs.toDouble() / durationMs * 10_000).roundToInt().coerceIn(0, 10_000)
    }

    @Suppress("DEPRECATION")
    private fun pickVideo() {
        pausePreview()
        val intent = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("video/*") else documentIntent("video/*")
        runCatching { activity.startActivityForResult(intent, PICK_VIDEO) }.onFailure {
            runCatching { activity.startActivityForResult(documentIntent("video/*"), PICK_VIDEO) }.onFailure { toast("无法打开系统视频选择器") }
        }
    }

    @Suppress("DEPRECATION")
    private fun pickImage() {
        pausePreview()
        runCatching { activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, PICK_IMAGE) }.onFailure { toast("无法打开系统图片选择器") }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != PICK_VIDEO && requestCode != PICK_IMAGE) return false
        if (resultCode != Activity.RESULT_OK) return true
        val uri = data?.data?.takeIf { it.scheme == "content" } ?: return true
        val readFlags = ((data?.flags ?: 0) and Intent.FLAG_GRANT_READ_URI_PERMISSION).takeIf { it != 0 } ?: Intent.FLAG_GRANT_READ_URI_PERMISSION
        runCatching { activity.contentResolver.takePersistableUriPermission(uri, readFlags) }
        if (requestCode == PICK_IMAGE) {
            coverUri = uri; coverReady = false; pausePreview(); refresh(); loadCover()
        } else {
            source = MediaItem("picked-${uri.toString().hashCode()}", title = displayName(uri) ?: "所选视频", localUri = uri.toString())
            coverUri = null; start = 0; end = 5_000; coverTimestampMs = 0
            prepareSource(resetRange = true)
        }
        return true
    }

    private fun prepareSource(resetRange: Boolean = false) {
        val item = source ?: return
        generation++; coverGeneration++
        val ticket = generation
        loadJob?.cancel(true); coverJob?.cancel(true)
        stopPreview()
        main.removeCallbacks(previewTick); coverReady = coverUri == null
        clearBitmaps(); poster.setImageDrawable(VideoCoverDrawable(item, theme))
        sourceReady = item.localUri == null && item.exists
        if (!item.exists) { message.text = "视频文件不存在，请重新选择。"; refresh(); return }
        if (item.localUri == null) {
            durationMs = item.durationMs.coerceAtLeast(1)
            if (resetRange) { start = 0; end = if (live) durationMs else minOf(5_000, durationMs) }
            message.text = "演示记录没有本地视频，创建后仅模拟任务进度。"
            refresh(); if (coverUri != null) loadCover(); return
        }
        sourceReady = false; message.text = "正在读取视频和时间轴…"; refresh()
        val uri = Uri.parse(item.localUri)
        loadJob = worker().submit {
            if (!safeSource(uri)) {
                main.post { if (isAttachedToWindow && generation == ticket) {
                    message.text = "无法读取这个视频地址，请用系统选择器重新选择。"; refresh()
                } }
                return@submit
            }
            main.post { if (isAttachedToWindow && generation == ticket) preparePreview(uri) }
            loadSource(item, uri, resetRange, ticket)
        }
    }

    /**
     * Reads duration, size, title and timeline frames off the UI thread. Very short or fragmented
     * recordings can omit metadata that [MediaMetadataRetriever] reports, so the container is probed
     * as a fallback, and one failed frame no longer discards the duration and title already read.
     */
    private fun loadSource(item: MediaItem, uri: Uri, resetRange: Boolean, ticket: Int) {
        val reader = MediaMetadataRetriever()
        val thumbnails = mutableListOf<Bitmap>()
        var handedOff = false
        try {
            runCatching { reader.setDataSource(activity, uri) }
            val container = probeContainer(uri)
            val length = runCatching { reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() }
                .getOrNull()?.takeIf { it > 0 } ?: container?.durationMs?.takeIf { it > 0 }
                ?: probeDurationWithPlayer(uri) ?: throw IllegalArgumentException("无法读取视频时长")
            val rawWidth = runCatching { reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() }
                .getOrNull()?.takeIf { it > 0 } ?: container?.width?.takeIf { it > 0 } ?: 1_920
            val rawHeight = runCatching { reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() }
                .getOrNull()?.takeIf { it > 0 } ?: container?.height?.takeIf { it > 0 } ?: 1_080
            val rotation = runCatching { reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() }
                .getOrNull() ?: container?.rotation ?: 0
            val width = if (rotation % 180 == 0) rawWidth else rawHeight
            val height = if (rotation % 180 == 0) rawHeight else rawWidth
            val title = if (item.id.startsWith("picked-")) displayName(uri) ?: item.title else item.title
            val cache = File(activity.cacheDir, "creator-thumbnails").apply { mkdirs() }
            val digest = MessageDigest.getInstance("SHA-256").digest("$uri:$length:160".toByteArray()).take(12).joinToString("") { "%02x".format(it) }
            for (index in 0..5) {
                if (Thread.currentThread().isInterrupted) return
                val file = File(cache, "$digest-$index.jpg")
                val cached = if (file.isFile) BitmapFactory.decodeFile(file.path) else null
                val at = ((length - 1) * (index / 5.0)).toLong().coerceAtLeast(0)
                // Three extractors in a row: retriever by URI, retriever by descriptor, platform thumbnailer.
                val frame = cached
                    ?: runCatching { scaledFrame(reader, at, width, height, 160) }.getOrNull()
                    ?: frameFromDescriptor(uri, at, width, height, 160)
                    ?: (if (index == 0) systemThumbnail(uri, 160) else null)
                    ?: continue
                thumbnails += frame
                if (cached == null) runCatching { file.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
            }
            val updated = item.copy(title = title, durationMs = length, width = width, height = height)
            main.post {
                if (!isAttachedToWindow || generation != ticket) thumbnails.forEach(Bitmap::recycle) else {
                    source = updated; durationMs = length; frames = thumbnails; timeline.invalidate()
                    if (resetRange) { start = 0; end = if (live) length else minOf(5_000, length) }
                    start = start.coerceIn(0, (length - 1).coerceAtLeast(0))
                    end = end.coerceIn(start + 1, length)
                    sourceReady = true
                    message.text = when {
                        live -> "选择封面后创建实况任务，视频不会裁剪。"
                        thumbnails.isEmpty() -> "已读取视频时长，但这台设备无法抽取画面缩略图，仍可创建 GIF。"
                        else -> "参数已准备好，可以预览所选片段。"
                    }
                    refresh(); loadCover()
                }
            }
            handedOff = true
        } catch (error: Exception) {
            main.post { if (isAttachedToWindow && generation == ticket) { sourceReady = false; message.text = error.message ?: "无法读取所选视频"; refresh() } }
        } finally { if (!handedOff) thumbnails.forEach(Bitmap::recycle); runCatching { reader.release() } }
    }

    private class ContainerProbe(val durationMs: Long, val width: Int, val height: Int, val rotation: Int)

    /**
     * The platform player reads files whose header omits the duration (the same engine the gallery
     * preview uses), so it is the last and most permissive duration source.
     */
    private fun probeDurationWithPlayer(uri: Uri): Long? {
        val player = MediaPlayer()
        return try {
            player.setDataSource(activity, uri)
            player.prepare()
            player.duration.toLong().takeIf { it > 0 }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { player.reset() }
            runCatching { player.release() }
        }
    }

    /** Some photo-picker URIs stay unreadable for the URI-based retriever but decode from a descriptor. */
    private fun frameFromDescriptor(uri: Uri, atMs: Long, width: Int, height: Int, limit: Int): Bitmap? = runCatching {
        activity.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(descriptor.fileDescriptor)
                scaledFrame(reader, atMs, width, height, limit)
            } finally { runCatching { reader.release() } }
        }
    }.getOrNull()

    /** Last resort: the same thumbnail generator the system gallery grid uses. */
    private fun systemThumbnail(uri: Uri, limit: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 29) activity.contentResolver.loadThumbnail(uri, android.util.Size(limit, limit), null) else null
    }.getOrNull()

    /** Leaving the page must blank the texture first, otherwise the last frame rides the pop animation. */
    private fun leavePage() {
        stopPreview()
        onBack()
    }

    /** Photo-picker and document URIs expose the file name through different columns; try them all. */
    private fun displayName(uri: Uri): String? = runCatching {
        activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            listOf(OpenableColumns.DISPLAY_NAME, MediaStore.MediaColumns.DISPLAY_NAME, "title", "_data")
                .firstNotNullOfOrNull { column ->
                    cursor.getColumnIndex(column).takeIf { it >= 0 && !cursor.isNull(it) }?.let { cursor.getString(it) }
                }
        }
    }.getOrNull()?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    /** MediaMetadataRetriever returns nothing for some short/fragmented files; the container still knows. */
    private fun probeContainer(uri: Uri): ContainerProbe? = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(activity, uri, null)
            var durationUs = 0L; var width = 0; var height = 0; var rotation = 0; var videoTrack = -1
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (!format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")) continue
                if (videoTrack < 0) videoTrack = index
                if (format.containsKey(MediaFormat.KEY_DURATION)) durationUs = maxOf(durationUs, format.getLong(MediaFormat.KEY_DURATION))
                if (width == 0 && format.containsKey(MediaFormat.KEY_WIDTH)) width = format.getInteger(MediaFormat.KEY_WIDTH)
                if (height == 0 && format.containsKey(MediaFormat.KEY_HEIGHT)) height = format.getInteger(MediaFormat.KEY_HEIGHT)
                if (format.containsKey(MediaFormat.KEY_ROTATION)) rotation = format.getInteger(MediaFormat.KEY_ROTATION)
            }
            if (durationUs <= 0 && videoTrack >= 0) {
                // Fragmented containers only report the length through their last sample timestamp.
                runCatching {
                    extractor.selectTrack(videoTrack)
                    extractor.seekTo(Long.MAX_VALUE, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    if (extractor.sampleTime > 0) durationUs = extractor.sampleTime
                }
            }
            ContainerProbe(durationUs / 1_000, width, height, rotation)
        } finally { runCatching { extractor.release() } }
    }.getOrNull()

    private fun loadCover() {
        val item = source ?: return
        val selectedPhoto = coverUri
        if (item.localUri == null && selectedPhoto == null) return
        val time = if (live) coverTimestampMs else start
        val ticket = ++coverGeneration
        if (selectedPhoto != null) { coverReady = false; refresh() }
        coverJob?.cancel(true)
        coverJob = worker().submit {
            var result: Bitmap? = null
            try {
                result = if (selectedPhoto != null) {
                    val decoded = decodeImage(selectedPhoto)
                    try {
                        MotionPhotoCover.preview(decoded, item.width.takeIf { it > 0 } ?: 1_920, item.height.takeIf { it > 0 } ?: 1_080)
                    } finally { decoded.recycle() }
                } else {
                    val frameWidth = item.width.takeIf { it > 0 } ?: 1_920
                    val frameHeight = item.height.takeIf { it > 0 } ?: 1_080
                    val uri = Uri.parse(item.localUri)
                    val reader = MediaMetadataRetriever()
                    try {
                        runCatching { reader.setDataSource(activity, uri) }
                        scaledFrame(reader, time, frameWidth, frameHeight, 960)
                    } catch (error: Exception) {
                        frameFromDescriptor(uri, time, frameWidth, frameHeight, 960)
                            ?: systemThumbnail(uri, 960)
                            ?: throw error
                    } finally { runCatching { reader.release() } }
                }
                val bitmap = result
                main.post {
                    if (!isAttachedToWindow || coverGeneration != ticket) bitmap?.recycle() else {
                        poster.setImageBitmap(bitmap); poster.visibility = VISIBLE
                        coverBitmap?.takeIf { it !== bitmap }?.recycle(); coverBitmap = bitmap
                        coverReady = true; refresh()
                    }
                }
            } catch (error: Exception) {
                result?.recycle()
                main.post { if (isAttachedToWindow && coverGeneration == ticket) {
                    coverReady = false
                    // A video frame that cannot be decoded only costs the preview; a picked photo must be reported.
                    if (selectedPhoto != null) message.text = error.message ?: "无法读取封面，请换一帧或另一张图片。"
                    refresh()
                } }
            }
        }
    }

    private fun scaledFrame(reader: MediaMetadataRetriever, atMs: Long, width: Int, height: Int, limit: Int): Bitmap {
        val scale = minOf(1.0, limit.toDouble() / maxOf(width, height).coerceAtLeast(1))
        val w = (width * scale).roundToInt().coerceAtLeast(1)
        val h = (height * scale).roundToInt().coerceAtLeast(1)
        val us = atMs.coerceAtMost(Long.MAX_VALUE / 1_000) * 1_000
        // Decoders disagree about which extraction option succeeds, especially on very short clips;
        // try each one and fall back to an unscaled frame instead of giving up after the first miss.
        for (option in intArrayOf(MediaMetadataRetriever.OPTION_CLOSEST, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)) {
            if (Build.VERSION.SDK_INT >= 27) {
                runCatching { reader.getScaledFrameAtTime(us, option, w, h) }.getOrNull()?.let { return it }
            }
            runCatching { reader.getFrameAtTime(us, option) }.getOrNull()?.let { original ->
                return Bitmap.createScaledBitmap(original, w, h, true).also { if (it !== original) original.recycle() }
            }
        }
        throw IllegalArgumentException("无法提取该画面")
    }

    private fun decodeImage(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取所选图片" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1280) sample *= 2
        val decoded = activity.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException("无法解码所选图片")
        val orientation = runCatching { activity.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
        }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
    }

    private fun playPreview() {
        val item = source ?: return
        if (item.localUri == null) { toast("演示视频暂无本地预览"); return }
        if (previewPlaying) { pausePreview(); play.text = "播放所选片段"; main.removeCallbacks(previewTick); return }
        // shortcut: VideoView preview seeking uses Int milliseconds; task parameters retain the full Long duration.
        seekPreview(start)
        poster.visibility = GONE; applySpeed(); startPreview(); play.text = "暂停预览"
        main.removeCallbacks(previewTick); main.post(previewTick)
    }

    private fun applySpeed() {
        if (previewPlaying || play.text == "暂停预览") runCatching { engine?.setPlaybackSpeed(speed.toFloat()) }
    }

    private fun createTask() {
        val item = source ?: return
        if (!sourceReady || (coverUri != null && !coverReady)) return
        trimGif()
        val params = JSONObject().apply {
            put("mediaId", item.id); put("sourceUri", item.localUri ?: JSONObject.NULL); put("sourceTitle", item.title)
            put("durationMs", durationMs); put("sourceWidth", item.width); put("sourceHeight", item.height)
            put("startMs", if (live) 0 else start); put("endMs", if (live) durationMs else end)
            put("fps", fps); put("width", if (live) 0 else outputWidth); put("quality", quality)
            put("loop", loop); put("speed", if (live) 1.0 else speed)
            put("coverUri", coverUri?.toString() ?: JSONObject.NULL); put("coverTimestampMs", coverTimestampMs)
            put("retainFullDuration", live)
        }
        pausePreview()
        val task = tasks.create(type, "${if (live) "实况" else "GIF"} · ${item.title}", item.sourceUrl, params.toString())
        onCreated(task)
    }

    private fun restoreParams(params: JSONObject) {
        val mediaId = params.optString("mediaId")
        source = mediaRepo.get(mediaId) ?: source ?: params.optString("sourceUri").takeIf { it.isNotBlank() && it != "null" }?.let {
            MediaItem(mediaId.ifBlank { "edited-source" }, title = params.optString("sourceTitle", editWork?.title ?: "所选视频"), localUri = it,
                durationMs = params.optLong("durationMs", 1), width = params.optInt("sourceWidth", 1920), height = params.optInt("sourceHeight", 1080))
        }
        durationMs = source?.durationMs?.coerceAtLeast(1) ?: params.optLong("durationMs", 1).coerceAtLeast(1)
        start = params.optLong("startMs", 0); end = params.optLong("endMs", durationMs)
        fps = params.optInt("fps", 15).takeIf { it in listOf(10, 15, 20) } ?: 15
        outputWidth = params.optInt("width", 480).takeIf { it in listOf(0, 320, 480, 720) } ?: 480
        quality = params.optString("quality", "standard").takeIf { it == "high" } ?: "standard"
        loop = params.optString("loop", "infinite").takeIf { it == "once" } ?: "infinite"
        speed = params.optDouble("speed", 1.0).takeIf { it in listOf(.5, 1.0, 2.0) } ?: 1.0
        coverTimestampMs = params.optLong("coverTimestampMs", 0)
        coverUri = params.optString("coverUri").takeIf { it.isNotBlank() && it != "null" }?.let(Uri::parse)?.takeIf { it.scheme == "content" }
    }

    private fun trimGif() {
        if (!live && end - start > 15_000) {
            val trimmed = ConversionEstimate.gifRange(durationMs, start, end)
            start = trimmed.first; end = trimmed.second; refresh()
            toast("GIF 最长 15 秒，已自动截取前 15 秒")
        }
    }

    private fun documentIntent(mime: String) = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        type = mime; addCategory(Intent.CATEGORY_OPENABLE)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    private fun safeSource(uri: Uri): Boolean {
        if (uri.scheme in listOf("content", "android.resource")) return true
        if (uri.scheme != "file" && uri.scheme != null) return false
        return runCatching {
            val candidate = File(uri.path ?: return false).canonicalPath
            listOfNotNull(activity.cacheDir, activity.filesDir, activity.externalCacheDir, activity.getExternalFilesDir(null))
                .any { candidate.startsWith(it.canonicalPath + File.separator) }
        }.getOrDefault(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        worker()
        prepareSource()
    }

    /** A cancelled reader is recreated on demand: a null worker used to leave the screen unreadable. */
    private fun worker(): ExecutorService = worker ?: Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "conversion-preview")
    }.also { worker = it }

    override fun onDetachedFromWindow() {
        generation++; coverGeneration++
        main.removeCallbacks(pendingCover); main.removeCallbacks(previewTick)
        loadJob?.cancel(true); coverJob?.cancel(true); worker?.shutdownNow(); worker = null
        releasePreview(); timeline.cancelMotion(); clearBitmaps()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) { pausePreview(); play.text = "播放所选片段"; main.removeCallbacks(previewTick); timeline.cancelMotion() }
    }

    private fun clearBitmaps() {
        poster.setImageDrawable(null); coverBitmap?.recycle(); coverBitmap = null
        frames.forEach(Bitmap::recycle); frames = emptyList(); timeline.invalidate()
    }

    override fun dispatchSaveInstanceState(container: SparseArray<Parcelable>) = dispatchFreezeSelfOnly(container)
    override fun dispatchRestoreInstanceState(container: SparseArray<Parcelable>) = dispatchThawSelfOnly(container)
    override fun onSaveInstanceState(): Parcelable = Bundle().apply {
        putParcelable("super", super.onSaveInstanceState()); putString("source", source?.let(MediaCodec::encode))
        putLong("start", start); putLong("end", end); putLong("cover_time", coverTimestampMs); putString("cover_uri", coverUri?.toString())
        putInt("fps", fps); putInt("width", outputWidth); putString("quality", quality); putString("loop", loop); putDouble("speed", speed)
        putInt("scroll", scroll.scrollY)
    }
    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is Bundle) { super.onRestoreInstanceState(state); return }
        @Suppress("DEPRECATION") super.onRestoreInstanceState(state.getParcelable("super"))
        source = state.getString("source")?.let { runCatching { MediaCodec.decode(it) }.getOrNull() }
        durationMs = source?.durationMs?.coerceAtLeast(1) ?: 1
        start = state.getLong("start"); end = state.getLong("end", durationMs)
        coverTimestampMs = state.getLong("cover_time"); coverUri = state.getString("cover_uri")?.let(Uri::parse)
        fps = state.getInt("fps", 15); outputWidth = state.getInt("width", 480)
        quality = state.getString("quality", "standard"); loop = state.getString("loop", "infinite"); speed = state.getDouble("speed", 1.0)
        refresh()
        val y = state.getInt("scroll")
        scroll.addOnLayoutChangeListener(object : OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                if (isShown && scroll.height > 0) { scroll.removeOnLayoutChangeListener(this); scroll.scrollTo(0, y) }
            }
        })
        if (isAttachedToWindow) prepareSource()
    }

    private inner class RangeTimeline : View(activity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var leftValue = 0f
        private var rightValue = 1f
        private var handle = 1
        private val leftSpring = spring { leftValue = it; invalidate() }
        private val rightSpring = spring { rightValue = it; invalidate() }
        init { tag = "creator_timeline"; isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
        private fun spring(update: (Float) -> Unit) = SpringAnimation(FloatValueHolder()).apply {
            spring = SpringForce(0f).apply { dampingRatio = .8f; stiffness = 700f }
            setMinValue(0f); setMaxValue(1f); minimumVisibleChange = .001f
            addUpdateListener { _, value, _ -> update(value) }
        }
        fun cancelMotion() { leftSpring.cancel(); rightSpring.cancel() }
        fun setRange(left: Double, right: Double) {
            if (isAttachedToWindow && animationsEnabled()) {
                if (!leftSpring.isRunning) leftSpring.setStartValue(leftValue)
                if (!rightSpring.isRunning) rightSpring.setStartValue(rightValue)
                leftSpring.animateToFinalPosition(left.toFloat()); rightSpring.animateToFinalPosition(right.toFloat())
            } else { cancelMotion(); leftValue = left.toFloat(); rightValue = right.toFloat(); invalidate() }
            contentDescription = "片段起点 ${time(start)}，终点 ${time(end)}。点击切换把手，滑动调整。"
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = dp(9).toFloat(); val span = (width - inset * 2).coerceAtLeast(1f)
            rect.set(inset, dp(8).toFloat(), width - inset, height - dp(8).toFloat())
            paint.color = theme.muted; canvas.drawRoundRect(rect, dp(12).toFloat(), dp(12).toFloat(), paint)
            val save = canvas.save(); canvas.clipRect(rect)
            frames.forEachIndexed { index, bitmap ->
                rect.set(inset + span * index / 6, dp(8).toFloat(), inset + span * (index + 1) / 6, height - dp(8).toFloat())
                canvas.drawBitmap(bitmap, null, rect, paint)
            }
            paint.color = 0x77000000
            canvas.drawRect(inset, dp(8).toFloat(), inset + span * leftValue, height - dp(8).toFloat(), paint)
            canvas.drawRect(inset + span * rightValue, dp(8).toFloat(), width - inset, height - dp(8).toFloat(), paint)
            canvas.restoreToCount(save)
            paint.color = theme.primary; paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(3).toFloat()
            rect.set(inset + span * leftValue, dp(7).toFloat(), inset + span * rightValue, height - dp(7).toFloat())
            canvas.drawRoundRect(rect, dp(9).toFloat(), dp(9).toFloat(), paint); paint.style = Paint.Style.FILL
            for (fraction in listOf(leftValue, rightValue)) {
                val x = inset + span * fraction
                rect.set(x - dp(8), dp(4).toFloat(), x + dp(8), height - dp(4).toFloat())
                canvas.drawRoundRect(rect, dp(8).toFloat(), dp(8).toFloat(), paint)
                paint.color = theme.onPrimary; rect.set(x - dp(1), height / 2f - dp(8), x + dp(1), height / 2f + dp(8))
                canvas.drawRoundRect(rect, dp(1).toFloat(), dp(1).toFloat(), paint); paint.color = theme.primary
            }
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (source == null || !sourceReady) return false
            val fraction = ((event.x - dp(9)) / (width - dp(18)).coerceAtLeast(1)).coerceIn(0f, 1f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handle = if (abs(fraction - leftValue) < abs(fraction - rightValue)) 0 else 1
                    parent.requestDisallowInterceptTouchEvent(true); pausePreview(); change(fraction)
                }
                MotionEvent.ACTION_MOVE -> change(fraction)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent.requestDisallowInterceptTouchEvent(false); trimGif(); main.removeCallbacks(pendingCover); loadCover(); performClick()
                }
                else -> return false
            }
            return true
        }
        private fun change(fraction: Float) {
            val at = (fraction.toDouble() * durationMs).toLong()
            if (handle == 0) start = at.coerceIn(0, end - 1) else end = at.coerceIn(start + 1, durationMs)
            refresh()
        }
        override fun performClick(): Boolean { super.performClick(); return true }
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info); info.className = "android.widget.SeekBar"
            info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, 1f,
                (if (handle == 0) start else end).toFloat() / durationMs)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        }
        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when (action) {
            AccessibilityNodeInfo.ACTION_CLICK -> { handle = 1 - handle; announceForAccessibility(if (handle == 0) "调整起点" else "调整终点"); true }
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> {
                val step = (1_000.0 / durationMs).toFloat()
                change(((if (handle == 0) start else end).toFloat() / durationMs + if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) step else -step).coerceIn(0f, 1f)); trimGif(); true
            }
            else -> super.performAccessibilityAction(action, arguments)
        }
        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { handle = 1 - handle; return true }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                return performAccessibilityAction(if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null)
            return super.onKeyDown(keyCode, event)
        }
    }

    private fun button(value: String, tagValue: String, primary: Boolean = false, action: () -> Unit) = text(value, 14f, true).apply {
        tag = tagValue; gravity = Gravity.CENTER; minHeight = dp(48); minWidth = dp(48); isFocusable = true
        setPadding(dp(10), dp(8), dp(10), dp(8)); setTextColor(if (primary) theme.onPrimary else theme.primaryText)
        background = theme.control(if (primary) theme.primary else theme.primarySoft, 16)
        setOnClickListener { UiMotion.haptic(this); action() }; UiMotion.press(this)
    }
    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(if (bold) theme.text else theme.secondary)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    private fun row() = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun gap(value: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(value) }
    private fun time(value: Long) = String.format(Locale.ROOT, "%d:%02d.%01d", value / 60_000, value / 1_000 % 60, value / 100 % 10)
    private fun toast(value: String) = Toast.makeText(activity, value, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = theme.dp(value)
    private fun animationsEnabled() = if (Build.VERSION.SDK_INT >= 26) android.animation.ValueAnimator.areAnimatorsEnabled()
        else android.provider.Settings.Global.getFloat(activity.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    companion object { const val PICK_VIDEO = 6201; const val PICK_IMAGE = 6202 }
}
