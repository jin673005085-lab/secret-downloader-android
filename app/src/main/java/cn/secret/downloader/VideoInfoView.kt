package cn.secret.downloader

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import java.io.Closeable
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal object VideoInfoQueries {
    fun related(items: List<MediaItem>, current: MediaItem, limit: Int = 6): List<MediaItem> = items.asSequence()
        .filter { it.type == MediaType.VIDEO && it.id != current.id }
        .sortedWith(compareByDescending<MediaItem> { !current.author.isNullOrBlank() && it.author == current.author }
            .thenByDescending { it.downloadedAt }.thenBy { it.id }).take(limit.coerceAtLeast(0)).toList()

    fun source(value: String?): String? = value?.trim()?.takeIf { text ->
        runCatching { URI(text).let { it.scheme?.lowercase(Locale.ROOT) in listOf("http", "https") &&
            !it.host.isNullOrBlank() && it.userInfo == null } }.getOrDefault(false)
    }
}

/** The player owns playback; this view only observes and edits the selected video's metadata. */
class VideoInfoView(
    context: Context,
    private val theme: UiTheme,
    private val mediaRepo: MediaRepository,
    initial: MediaItem,
    private val onPlayRelated: (MediaItem) -> Unit,
    private val onConvert: (MediaItem, Boolean, Long) -> Unit,
    private val onMore: (MediaItem) -> Unit,
    private val positionMs: () -> Long,
) : LinearLayout(context) {
    private var currentItem = initial
    private var observation: Closeable? = null
    private var executor: ExecutorService? = null
    private var metadataJob: Future<*>? = null
    private val main = Handler(Looper.getMainLooper())
    private var metadataGeneration = 0
    private var metadataKey: String? = null
    private var expandedTitle = false
    private var selectedSegment = 0
    private var activeDialog: AlertDialog? = null
    private var relatedItems = emptyList<MediaItem>()
    private data class FileMetadata(val codec: String? = null, val frameRate: String? = null,
                                    val bitrate: String? = null, val width: Int? = null, val height: Int? = null)
    private val metadataCache = object : LinkedHashMap<String, FileMetadata>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FileMetadata>?) = size > 8
    }
    private val metadataFields = linkedMapOf<String, TextView>()
    private val segments = mutableListOf<TextView>()
    private val segmentPill = View(context).apply { background = theme.shape(theme.surface, 14) }
    private val segmentSpring = SpringAnimation(segmentPill, DynamicAnimation.TRANSLATION_X).apply {
        spring = SpringForce(0f).apply { dampingRatio = .75f; stiffness = 400f }
    }
    private val intro = LinearLayout(context).apply { orientation = VERTICAL }
    private val files = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
    private val author = text("", 15f, true)
    private val avatar = text("", 23f, true).apply {
        gravity = Gravity.CENTER; setTextColor(theme.primaryText); background = theme.shape(theme.primarySoft, 100)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val platform = text("", 11f, true).apply {
        background = theme.shape(theme.primarySoft, 8); setTextColor(theme.primaryText)
        setPadding(dp(8), dp(4), dp(8), dp(4))
    }
    private val title = text("", 20f, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val titleToggle = text("展开标题 ⌄", 12f).apply {
        isFocusable = true; minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL
        setOnClickListener {
            expandedTitle = !expandedTitle
            title.maxLines = if (expandedTitle) Int.MAX_VALUE else 2
            text = if (expandedTitle) "收起标题 ⌃" else "展开标题 ⌄"
            UiMotion.enter(title, 0)
        }
        UiMotion.press(this)
    }
    private val summary = text("", 12f)
    private val related = LinearLayout(context).apply { orientation = VERTICAL }
    private val favorite = action("♡", "收藏", "favorite") {
        mediaRepo.setFavorite(currentItem.id, !currentItem.favorite)
    }
    private val later = action("◷", "稍后看", "watch_later") {
        mediaRepo.setWatchLater(currentItem.id, !currentItem.watchLater)
    }

    init {
        tag = "video_info"
        orientation = VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(24))
        val selector = FrameLayout(context).apply { background = theme.shape(theme.muted, 18) }
        selector.addView(segmentPill, FrameLayout.LayoutParams(0, dp(48)).apply { topMargin = dp(4) })
        selector.addView(row().apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            listOf("简介", "文件信息").forEachIndexed { index, label ->
                val button = text(label, 14f, true).apply {
                    tag = "video_info_segment_$index"; gravity = Gravity.CENTER; isFocusable = true
                    setOnClickListener { selectSegment(index) }; UiMotion.press(this)
                }
                segments += button
                addView(button, LayoutParams(0, dp(48), 1f))
            }
        }, FrameLayout.LayoutParams(-1, -2))
        selector.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionPill(false) }
        addView(selector, LayoutParams(-1, -2))

        intro.addView(row().apply {
            addView(avatar, LayoutParams(dp(46), dp(46)))
            addView(author, LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            addView(platform)
        }, gap(20))
        intro.addView(title, gap(16))
        intro.addView(titleToggle)
        intro.addView(summary, gap(3))
        intro.addView(row().apply {
            addView(favorite, LayoutParams(0, dp(76), 1f))
            addView(later, LayoutParams(0, dp(76), 1f))
            addView(action("↗", "分享", "share") { shareSource(currentItem) }.apply {
                setOnLongClickListener { shareFile(); true }
                contentDescription = "分享来源链接，长按分享视频文件"
            }, LayoutParams(0, dp(76), 1f))
            addView(action("GIF", "转 GIF", "gif") { convert(true) }, LayoutParams(0, dp(76), 1f))
            addView(action("◉", "转实况", "live") { convert(false) }, LayoutParams(0, dp(76), 1f))
            addView(action("⋯", "更多", "more") { onMore(currentItem) }, LayoutParams(0, dp(76), 1f))
        }, gap(12))
        intro.addView(text("相关视频", 18f, true), gap(12))
        intro.addView(related, gap(12))
        addView(intro)

        listOf("位置", "文件大小", "编码", "分辨率", "帧率", "码率", "来源", "下载时间").forEach { label ->
            val value = text("—", 14f).apply { setTextIsSelectable(true) }
            metadataFields[label] = value
            files.addView(LinearLayout(context).apply {
                orientation = VERTICAL
                background = theme.shape(theme.surface, 16)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(text(label, 12f))
                addView(value, gap(5))
                if (label == "来源") addView(row().apply {
                    addView(smallButton("复制链接", "video_source_copy") { copySource() })
                    addView(smallButton("浏览器打开", "video_source_browser") { openSource() }, LayoutParams(-2, dp(48)).apply { marginStart = dp(8) })
                }, gap(6))
            }, gap(10))
        }
        files.addView(smallButton("分享视频文件", "video_info_share_file") { shareFile() }, gap(14))
        addView(files, gap(10))
        bind(initial)
        selectSegment(0, false)
    }

    fun bind(item: MediaItem) {
        val changedVideo = currentItem.id != item.id
        currentItem = item
        if (changedVideo) {
            expandedTitle = false; title.maxLines = 2; titleToggle.text = "展开标题 ⌄"
            selectSegment(0, false)
        }
        title.setIfChanged(item.title)
        val authorName = item.author?.takeIf { it.isNotBlank() } ?: item.platform
        author.setIfChanged(authorName)
        avatar.setIfChanged(authorName.takeIf { it.isNotEmpty() }?.let { String(Character.toChars(it.codePointAt(0))) } ?: "▶")
        platform.setIfChanged(item.platform)
        summary.setIfChanged("▶ ${item.playCount} 次观看 · ${VideoLibraryUi.duration(item.durationMs)} · ${TaskUi.bytes(item.sizeBytes)}")
        updateAction(favorite, if (item.favorite) "♥" else "♡", if (item.favorite) "已收藏" else "收藏", item.favorite)
        updateAction(later, if (item.watchLater) "✓" else "◷", if (item.watchLater) "已添加" else "稍后看", item.watchLater)
        metadataFields.getValue("位置").setIfChanged(item.localUri ?: "演示记录，尚未关联本地文件")
        metadataFields.getValue("文件大小").setIfChanged(TaskUi.bytes(item.sizeBytes))
        metadataFields.getValue("来源").setIfChanged(item.sourceUrl ?: "没有来源链接")
        metadataFields.getValue("下载时间").setIfChanged(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.downloadedAt)))
        requestMetadata()
        renderRelated(mediaRepo.items)
    }

    private fun selectSegment(index: Int, animate: Boolean = true) {
        selectedSegment = index
        intro.visibility = if (index == 0) VISIBLE else GONE
        files.visibility = if (index == 1) VISIBLE else GONE
        segments.forEachIndexed { position, view ->
            view.isSelected = position == index
            view.setTextColor(if (position == index) theme.primaryText else theme.secondary)
        }
        positionPill(animate)
        if (animate) UiMotion.enter(if (index == 0) intro else files, 0)
    }

    private fun positionPill(animate: Boolean) {
        val selected = segments.getOrNull(selectedSegment) ?: return
        if (selected.width == 0) return
        if (segmentPill.layoutParams.width != selected.width) segmentPill.layoutParams = segmentPill.layoutParams.apply { width = selected.width }
        val destination = (selected.left + dp(4)).toFloat()
        if (animate && animationsEnabled()) segmentSpring.animateToFinalPosition(destination)
        else { segmentSpring.cancel(); segmentPill.translationX = destination }
    }

    private fun renderRelated(items: List<MediaItem>) {
        val next = VideoInfoQueries.related(items, currentItem)
        if (next == relatedItems && related.childCount > 0) return
        relatedItems = next
        related.removeAllViews()
        if (next.isEmpty()) {
            related.addView(text("暂无其他视频", 14f)); return
        }
        next.forEach { item ->
            related.addView(row().apply {
                tag = "related_video_${item.id}"
                isFocusable = true
                setOnClickListener { if (item.exists) onPlayRelated(item) else toast("文件不存在，请在视频库移除记录或重新下载") }
                UiMotion.press(this)
                addView(ImageView(context).apply {
                    tag = "related_cover_${item.id}"
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    MediaThumbnails.bind(this, item, VideoCoverDrawable(item, theme))
                    background = theme.shape(theme.muted, 12); clipToOutline = true
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                    alpha = if (item.exists) 1f else .55f
                }, LayoutParams(dp(128), dp(72)))
                addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(text(item.title, 14f, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                    addView(text("${item.author ?: item.platform} · ${VideoLibraryUi.duration(item.durationMs)}", 11f), gap(6))
                }, LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
                addView(smallButton("⋯", "related_menu_${item.id}") { relatedMenu(item) }.apply { contentDescription = "${item.title}的更多操作" }, LayoutParams(dp(48), dp(48)))
            }, gap(12))
        }
    }

    private fun relatedMenu(item: MediaItem) {
        val value = mediaRepo.get(item.id) ?: item
        show(AlertDialog.Builder(context).setTitle(value.title).setItems(arrayOf("播放", if (value.favorite) "取消收藏" else "收藏",
            if (value.watchLater) "移出稍后看" else "稍后看", "文件信息")) { _, index ->
            when (index) {
                0 -> if (value.exists) onPlayRelated(value) else toast("文件不存在")
                1 -> mediaRepo.setFavorite(value.id, !value.favorite)
                2 -> mediaRepo.setWatchLater(value.id, !value.watchLater)
                3 -> show(AlertDialog.Builder(context).setTitle("文件信息").setMessage(
                    "${value.title}\n${value.width} × ${value.height}\n${TaskUi.bytes(value.sizeBytes)}\n${value.localUri ?: "演示记录，尚未关联本地文件"}")
                    .setPositiveButton("关闭", null).create())
            }
        }.create())
    }

    private fun convert(gif: Boolean) {
        if (!currentItem.exists) { toast("文件不存在，无法转换"); return }
        onConvert(currentItem, gif, positionMs().coerceAtLeast(0))
    }

    private fun shareSource(item: MediaItem) {
        val source = VideoInfoQueries.source(item.sourceUrl)
        if (source == null) { toast("没有可分享的来源链接，可在文件信息中分享视频文件"); return }
        launchShare(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, source), "分享来源链接")
    }

    fun shareFile(item: MediaItem = currentItem) {
        if (!item.exists || item.localUri.isNullOrBlank()) { toast("演示记录没有可分享的视频文件"); return }
        show(AlertDialog.Builder(context).setTitle("分享视频文件").setMessage("请确认你有权分享该内容")
            .setNegativeButton("取消", null).setPositiveButton("继续分享") { _, _ ->
                prepareFileShare(item)
            }.create())
    }

    private fun prepareFileShare(item: MediaItem) {
        val displayedId = currentItem.id
        executor?.submit {
            val uri = runCatching {
                val original = Uri.parse(item.localUri)
                when (original.scheme) {
                    "content" -> original.takeIf { !it.authority.isNullOrBlank() }
                    "file", null -> localFile(original)?.takeIf { file ->
                        file.isFile && file.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator)
                    }?.let { FileProvider.getUriForFile(context, "${context.packageName}.files", it) }
                    else -> null
                }
            }.getOrNull()
            main.post {
                if (!isAttachedToWindow || currentItem.id != displayedId) return@post
                if (uri == null) toast("无法分享此本地文件") else {
                    val intent = Intent(Intent.ACTION_SEND).setType("video/*").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.clipData = ClipData.newRawUri(item.title, uri)
                    launchShare(intent, "分享视频文件")
                }
            }
        }
    }

    private fun launchShare(intent: Intent, label: String) {
        runCatching { context.startActivity(Intent.createChooser(intent, label)) }.onFailure { toast("无法打开分享面板") }
    }

    private fun copySource() {
        val source = VideoInfoQueries.source(currentItem.sourceUrl) ?: return toast("没有可复制的来源链接")
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("来源链接", source))
        toast("链接已复制")
    }

    private fun openSource() {
        val source = VideoInfoQueries.source(currentItem.sourceUrl) ?: return toast("没有可打开的来源链接")
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source))) }.onFailure { toast("无法打开浏览器") }
    }

    private fun requestMetadata() {
        val item = currentItem
        val key = "${item.id}:${item.localUri}:${item.exists}:${item.sizeBytes}:${item.downloadedAt}"
        if (metadataKey == key) return
        metadataKey = key
        metadataGeneration++
        val generation = metadataGeneration
        metadataJob?.cancel(true)
        val fallback = FileMetadata(width = item.width.takeIf { it > 0 }, height = item.height.takeIf { it > 0 })
        metadataCache[key]?.let { displayMetadata(it); return }
        displayMetadata(fallback)
        if (!isAttachedToWindow || !item.exists || item.localUri.isNullOrBlank()) return
        val raw = Uri.parse(item.localUri)
        if (raw.scheme !in listOf("content", "android.resource", "file", null)) return
        metadataJob = executor?.submit {
            val file = if (raw.scheme == "file" || raw.scheme == null) localFile(raw) else null
            if ((raw.scheme == "file" || raw.scheme == null) && file == null) return@submit
            val retriever = MediaMetadataRetriever()
            val extractor = MediaExtractor()
            try {
                if (file != null) retriever.setDataSource(file.path) else retriever.setDataSource(context, raw)
                if (Thread.currentThread().isInterrupted) return@submit
                var codec: String? = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                var frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                runCatching {
                    if (file != null) extractor.setDataSource(file.path) else extractor.setDataSource(context, raw, null)
                    for (index in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(index)
                        val mime = format.getString(MediaFormat.KEY_MIME)
                        if (mime?.startsWith("video/") == true) {
                            codec = mime
                            if (frameRate.isNullOrBlank() && format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                                frameRate = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toString() }
                                    .getOrElse { format.getFloat(MediaFormat.KEY_FRAME_RATE).toString() }
                            }
                            break
                        }
                    }
                }
                val result = FileMetadata(codec, frameRate,
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: fallback.width,
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: fallback.height)
                main.post {
                    if (isAttachedToWindow && metadataGeneration == generation) {
                        metadataCache[key] = result; displayMetadata(result)
                    }
                }
            } catch (_: Exception) {
                main.post { if (isAttachedToWindow && metadataGeneration == generation) displayMetadata(fallback) }
            } finally {
                runCatching { extractor.release() }; runCatching { retriever.release() }
            }
        }
    }

    private fun localFile(uri: Uri): File? = runCatching {
        if (!uri.authority.isNullOrBlank()) return null
        val file = File(uri.path ?: return null).canonicalFile
        val roots = listOfNotNull(context.cacheDir, context.filesDir, context.externalCacheDir, context.getExternalFilesDir(null))
        file.takeIf { candidate -> roots.any { candidate.path.startsWith(it.canonicalPath + File.separator) } }
    }.getOrNull()

    private fun displayMetadata(value: FileMetadata) {
        metadataFields.getValue("编码").setIfChanged(value.codec ?: "未知")
        metadataFields.getValue("分辨率").setIfChanged(if (value.width != null && value.height != null) "${value.width} × ${value.height}" else "未知")
        metadataFields.getValue("帧率").setIfChanged(value.frameRate?.toDoubleOrNull()?.takeIf { it > 0 }
            ?.let { "${String.format(Locale.getDefault(), "%.2f", it)} fps" } ?: "未知")
        metadataFields.getValue("码率").setIfChanged(value.bitrate?.toLongOrNull()?.takeIf { it > 0 }
            ?.let { if (it >= 1_000_000) String.format(Locale.getDefault(), "%.1f Mbps", it / 1_000_000.0)
                else String.format(Locale.getDefault(), "%.0f Kbps", it / 1_000.0) } ?: "未知")
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "video-file-metadata") }
        metadataKey = null
        observation = mediaRepo.observeAll { items ->
            items.firstOrNull { it.id == currentItem.id }?.let(::bind)
            renderRelated(items)
        }
        requestMetadata()
    }

    override fun onDetachedFromWindow() {
        observation?.close(); observation = null
        metadataGeneration++; metadataJob?.cancel(true); metadataJob = null
        executor?.shutdownNow(); executor = null
        activeDialog?.dismiss(); activeDialog = null
        segmentSpring.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) positionPill(false) else segmentSpring.cancel()
    }

    private fun show(dialog: AlertDialog) {
        activeDialog?.dismiss(); activeDialog = dialog
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
        dialog.show()
    }

    private fun action(symbol: String, label: String, tagValue: String, action: () -> Unit) = LinearLayout(context).apply {
        tag = "video_info_$tagValue"; orientation = VERTICAL; gravity = Gravity.CENTER; isFocusable = true
        addView(text(symbol, if (symbol == "GIF") 19f else 25f, true).apply { gravity = Gravity.CENTER }, LayoutParams(-1, dp(36)))
        addView(text(label, 11f).apply { gravity = Gravity.CENTER })
        contentDescription = label; setOnClickListener { UiMotion.haptic(this); action() }; UiMotion.press(this)
    }

    private fun updateAction(view: LinearLayout, symbol: String, label: String, selected: Boolean) {
        (view.getChildAt(0) as TextView).apply { setIfChanged(symbol); setTextColor(if (selected) theme.primary else theme.text) }
        (view.getChildAt(1) as TextView).apply { setIfChanged(label); setTextColor(if (selected) theme.primaryText else theme.secondary) }
        view.isSelected = selected; view.contentDescription = label
    }

    private fun smallButton(label: String, tagValue: String, action: () -> Unit) = text(label, 13f, true).apply {
        tag = tagValue; gravity = Gravity.CENTER; minHeight = dp(48); minWidth = dp(48); isFocusable = true
        setTextColor(theme.primaryText); background = theme.control(theme.primarySoft, 12)
        setPadding(dp(12), dp(6), dp(12), dp(6)); setOnClickListener { action() }; UiMotion.press(this)
    }
    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(if (bold) theme.text else theme.secondary)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    private fun row() = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun gap(value: Int) = LayoutParams(-1, -2).apply { topMargin = dp(value) }
    private fun TextView.setIfChanged(value: String) { if (text.toString() != value) text = value }
    private fun dp(value: Int) = theme.dp(value)
    private fun toast(value: String) = Toast.makeText(context, value, Toast.LENGTH_SHORT).show()
    private fun animationsEnabled() = if (android.os.Build.VERSION.SDK_INT >= 26) android.animation.ValueAnimator.areAnimatorsEnabled()
        else android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
}
