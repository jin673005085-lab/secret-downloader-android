package cn.secret.downloader

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem as PlaybackItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min

/** Plays only a work's recorded URI; no vendor-specific motion-photo inference happens here. */
@androidx.annotation.OptIn(UnstableApi::class)
class WorkViewerView(
    private val activity: Activity,
    private val theme: UiTheme,
    private val mediaRepo: MediaRepository,
    initial: MediaItem,
    playlist: List<MediaItem>,
    private val onBack: () -> Unit,
    private val onEdit: (MediaItem) -> Unit,
    private val onSource: (MediaItem, Long) -> Unit,
) : FrameLayout(activity) {
    var currentItem: MediaItem = initial
        private set
    internal var activeDialog: Dialog? = null
        private set
    internal val motionEngine: ExoPlayer? get() = engine
    internal val isAnimating: Boolean get() = if (currentItem.type == MediaType.GIF) gifPlaying && (animated != null || movie != null) else engine?.isPlaying == true
    private val works = playlist.filter { it.type == MediaType.GIF || it.type == MediaType.LIVE_PHOTO }.let {
        if (it.any { work -> work.id == initial.id }) it else listOf(initial) + it
    }.distinctBy { it.id }
    private var index = works.indexOfFirst { it.id == initial.id }.coerceAtLeast(0)
    private val handler = Handler(Looper.getMainLooper())
    private var worker: ExecutorService? = null
    private var generation = 0
    private var loadedId: String? = null
    private var released = false
    private var engine: ExoPlayer? = null
    private var animated: Drawable? = null
    private var movie: Movie? = null
    private var gifPlaying = true
    private var muted = true
    private var held = false
    /** True while the live clip layer is the visible surface; the cover must stay hidden only then. */
    private var liveActive = false
    private var liveFrameRendered = false
    private var controlsVisible = true
    private val animations = mutableMapOf<Pair<View, DynamicAnimation.ViewProperty>, SpringAnimation>()
    private val scene = FrameLayout(activity)
    private val motion = PlayerView(activity).apply { useController = false; setShutterBackgroundColor(Color.BLACK); visibility = GONE }
    private val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = GONE }
    private val legacyGif = MovieCanvas()
    private val cover = ImageView(activity).apply { tag = "work_viewer_cover"; scaleType = ImageView.ScaleType.FIT_CENTER }
    private val title = label(initial.title, 16f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val top = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val bottom = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private val notice = label("", 12f).apply { tag = "work_demo_notice"; gravity = Gravity.CENTER; setTextColor(0xffc7c7cf.toInt()) }
    private val toggle: TextView = button("暂停 GIF", "work_toggle") { togglePlayback() }
    private val replay: TextView = button("↻ 重新播放", "work_replay") { held = false; playMotion() }.apply {
        contentDescription = "重新播放完整实况，播放一次后返回封面"
    }
    private val sound: TextView = button("静音", "work_sound") {
        muted = !muted
        engine?.setAudioAttributes(liveAudio(), !muted)
        engine?.volume = if (muted) 0f else 1f
        updateButtons()
    }
    private val favorite: TextView = action("♡", "收藏", "work_favorite") {
        val next = !(mediaRepo.get(currentItem.id) ?: currentItem).favorite
        mediaRepo.setFavorite(currentItem.id, next); currentItem = currentItem.copy(favorite = next); updateButtons()
    }
    private val source: TextView = action("↗", "原视频", "work_source") {
        val item = currentItem.sourceMediaId?.let(mediaRepo::get)
        if (item == null) toast("原视频已不在视频库中") else { stopForBackground(); onSource(item, currentItem.sourceStartMs) }
    }
    private val error = LinearLayout(activity).apply {
        tag = "work_error"; orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        background = theme.shape(0xe62c2c33.toInt(), 20); setPadding(dp(20), dp(20), dp(20), dp(20)); visibility = GONE
    }
    private val gestures = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapConfirmed(event: MotionEvent): Boolean { showControls(!controlsVisible); return true }
        override fun onLongPress(event: MotionEvent) {
            if (currentItem.type == MediaType.LIVE_PHOTO && error.visibility != VISIBLE) { held = true; playMotion() }
        }
        override fun onFling(start: MotionEvent?, end: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            start ?: return false
            val dx = end.x - start.x; val dy = end.y - start.y
            if (abs(dx) > dp(70) && abs(dx) > abs(dy) * 1.5f) { changeItem(if (dx < 0) 1 else -1); return true }
            if (dy < -dp(70) && abs(dy) > abs(dx) * 1.5f) { showInfo(); return true }
            return false
        }
    })

    init {
        setBackgroundColor(0xff111114.toInt())
        scene.addView(motion, LayoutParams(-1, -1))
        scene.addView(image, LayoutParams(-1, -1))
        scene.addView(legacyGif, LayoutParams(-1, -1))
        scene.addView(cover, LayoutParams(-1, -1))
        scene.addView(View(activity).apply {
            tag = "work_gesture_area"
            contentDescription = "作品；左右滑动切换，上滑查看信息，长按播放实况"
            isFocusable = true
            setOnClickListener { showControls(!controlsVisible) }
            // TalkBack can invoke long-click without sending a physical hold/release sequence.
            setOnLongClickListener {
                if (currentItem.type == MediaType.LIVE_PHOTO && error.visibility != VISIBLE) { playMotion(); true } else false
            }
            setOnTouchListener { _, event ->
                gestures.onTouchEvent(event)
                if (held && (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)) {
                    held = false; returnToCover()
                }
                true
            }
        }, LayoutParams(-1, -1))
        addView(scene, LayoutParams(-1, -1))
        top.setPadding(dp(12), dp(12), dp(12), dp(12))
        top.addView(button("‹", "route_back") { stopForBackground(); onBack() }.apply { contentDescription = "返回"; textSize = 32f }, LinearLayout.LayoutParams(dp(52), dp(52)))
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12); marginEnd = dp(8) })
        top.addView(button("ⓘ", "work_info") { showInfo() }.apply { contentDescription = "作品信息"; textSize = 26f }, LinearLayout.LayoutParams(dp(52), dp(52)))
        top.addView(button("⋯", "work_more") { showMore() }.apply { contentDescription = "更多操作"; textSize = 28f }, LinearLayout.LayoutParams(dp(52), dp(52)))
        addView(top, LayoutParams(-1, -2, Gravity.TOP))
        bottom.setPadding(dp(12), dp(12), dp(12), dp(16))
        bottom.addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false; isFillViewport = true
            addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL
                addView(toggle)
                addView(replay, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                addView(sound, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            }, LayoutParams(-2, -2))
        })
        bottom.addView(notice, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(12) })
        bottom.addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf(action("↥", "分享", "work_share") { share() }, favorite,
                    action("✎", "重新编辑", "work_edit") { stopForBackground(); onEdit(currentItem) }, source,
                    action("⌫", "删除", "work_delete") { confirmDelete() }).forEachIndexed { index, item ->
                    addView(item, LinearLayout.LayoutParams(-2, -2).apply { if (index > 0) marginStart = dp(8) })
                }
            }, LayoutParams(-2, -2))
        }, LinearLayout.LayoutParams(-1, -2))
        addView(bottom, LayoutParams(-1, -2, Gravity.BOTTOM))
        addView(error, LayoutParams(-1, -2, Gravity.CENTER).apply { leftMargin = dp(24); rightMargin = dp(24) })
        renderIdentity()
    }

    private fun renderIdentity() {
        tag = if (currentItem.type == MediaType.GIF) "route_VIEW_GIF" else "route_VIEW_LIVE"
        title.text = currentItem.title
        MediaThumbnails.bind(cover, currentItem, VideoCoverDrawable(currentItem, theme)); cover.visibility = VISIBLE
        notice.text = if (isDemo()) "演示资源，未写入系统相册" else "已保存到系统相册"
        source.isEnabled = currentItem.sourceMediaId?.let(mediaRepo::get) != null
        source.alpha = if (source.isEnabled) 1f else .45f
        updateButtons()
    }

    private fun loadCurrent() {
        if (!isAttachedToWindow || !isShown || released) return
        if (loadedId == currentItem.id) return
        loadedId = currentItem.id
        generation++; val load = generation
        stopAnimation()
        liveActive = false; liveFrameRendered = false
        engine?.pause(); engine?.clearMediaItems()
        cover.visibility = VISIBLE; image.visibility = GONE; legacyGif.visibility = GONE; motion.visibility = GONE
        error.visibility = GONE; toggle.isEnabled = true; replay.isEnabled = true
        scene.translationX = 0f; scene.alpha = 1f
        renderIdentity()
        showControls(true)
        if (!currentItem.exists) { showError("文件不存在，可能已被删除或移动。"); return }
        if (currentItem.type == MediaType.LIVE_PHOTO) {
            if (currentItem.motionVideoUri.isNullOrBlank()) showError("这条记录没有可播放的动态片段。") else playMotion()
            return
        }
        engine?.removeListener(liveListener); engine?.release(); engine = null; motion.player = null
        val uri = currentItem.localUri?.let(Uri::parse)
        if (uri == null || !allowed(uri)) { showError("无法读取这个 GIF 文件。"); return }
        val io = worker ?: Executors.newSingleThreadExecutor().also { worker = it }
        io.execute {
            val decoded = runCatching {
                if (Build.VERSION.SDK_INT >= 28) {
                    ImageDecoder.decodeDrawable(ImageDecoder.createSource(activity.contentResolver, uri)) { decoder, info, _ ->
                        val scale = min(1f, 1_024f / maxOf(info.size.width, info.size.height).coerceAtLeast(1))
                        decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                    }
                } else activity.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "无法读取 GIF" }
                    // shortcut: Movie cannot downsample on API 24–27; bound input until real imports supply a modern decoder.
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(8_192)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        check(bytes.size() + count <= 24 * 1_024 * 1_024) { "这个 GIF 过大，当前系统无法解码" }
                        bytes.write(buffer, 0, count)
                    }
                    val data = bytes.toByteArray()
                    @Suppress("DEPRECATION")
                    requireNotNull(Movie.decodeByteArray(data, 0, data.size)) { "GIF 文件已损坏" }
                }
            }
            handler.post {
                if (load != generation || released || !isShown) return@post
                decoded.fold(onSuccess = { value ->
                    cover.visibility = GONE
                    if (Build.VERSION.SDK_INT >= 28 && value is AnimatedImageDrawable) {
                        animated = value; value.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                        image.setImageDrawable(value); image.visibility = VISIBLE
                        if (gifPlaying) value.start()
                    } else if (value is Movie) {
                        movie = value; legacyGif.visibility = VISIBLE; legacyGif.reset(gifPlaying)
                    } else { showError("这个文件没有可播放的 GIF 动画。") }
                    updateButtons()
                }, onFailure = { showError("GIF 无法解码，文件可能已损坏。") })
            }
        }
    }

    private fun playMotion() {
        val uri = currentItem.motionVideoUri?.let(Uri::parse)
        if (uri == null || !allowed(uri)) { showError("没有可读取的实况动态片段。"); return }
        if (!isShown || released) return
        val player = engine ?: ExoPlayer.Builder(activity).build().also {
            engine = it
            it.setAudioAttributes(liveAudio(), !muted)
            it.setHandleAudioBecomingNoisy(true); it.addListener(liveListener); motion.player = it
        }
        if (motion.player !== player) motion.player = player
        player.repeatMode = Player.REPEAT_MODE_OFF
        if (player.currentMediaItem?.localConfiguration?.uri != uri) {
            liveFrameRendered = false
            player.setMediaItem(PlaybackItem.fromUri(uri)); player.prepare()
        } else player.seekTo(0)
        player.volume = if (muted) 0f else 1f
        // The cover stays in front until the player reports its first frame, so no black shutter is ever shown.
        liveActive = true
        cover.visibility = VISIBLE
        motion.visibility = VISIBLE; player.play()
        updateButtons()
    }

    private val liveListener = object : Player.Listener {
        override fun onRenderedFirstFrame() { liveFrameRendered = true; if (liveActive) cover.visibility = GONE }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Replaying the same prepared clip need not emit another first-frame callback.
            if (liveActive && isPlaying && liveFrameRendered) cover.visibility = GONE
            keepScreenOn = liveActive && isPlaying; updateButtons()
        }
        override fun onPlaybackStateChanged(playbackState: Int) { if (liveActive && playbackState == Player.STATE_ENDED) returnToCover() }
        override fun onPlayerError(error: PlaybackException) { returnToCover(); showError("动态片段无法播放，文件或读取权限可能已失效。") }
    }

    /** The cover layer sits above the player surface, so showing it again is what ends playback visually. */
    private fun returnToCover() {
        // Reset the flag first: the seek below can render another frame and must not hide the cover again.
        liveActive = false
        engine?.pause()
        cover.visibility = VISIBLE
        motion.visibility = GONE
        engine?.seekTo(0)
        keepScreenOn = false
        updateButtons()
    }

    internal fun playbackDiagnostics(): String = engine.let { player ->
        "state=${player?.playbackState}, playing=${player?.isPlaying}, playWhenReady=${player?.playWhenReady}, " +
            "position=${player?.currentPosition}, duration=${player?.duration}, repeat=${player?.repeatMode}, " +
            "active=$liveActive, held=$held, rendered=$liveFrameRendered, cover=${cover.visibility}, motion=${motion.visibility}, " +
            "attached=$isAttachedToWindow, shown=$isShown, released=$released, error=${error.visibility}"
    }

    private fun togglePlayback() {
        if (currentItem.type == MediaType.GIF) {
            gifPlaying = !gifPlaying
            if (Build.VERSION.SDK_INT >= 28) (animated as? AnimatedImageDrawable)?.let { if (gifPlaying) it.start() else it.stop() }
            legacyGif.setPlaying(gifPlaying)
        } else if (liveActive) returnToCover() else playMotion()
        updateButtons()
    }

    private fun updateButtons() {
        val live = currentItem.type == MediaType.LIVE_PHOTO
        toggle.visibility = if (!live || liveActive) VISIBLE else GONE
        toggle.text = if (!live) { if (gifPlaying) "暂停 GIF" else "播放 GIF" } else "回到封面"
        toggle.contentDescription = toggle.text
        replay.visibility = if (live) VISIBLE else GONE
        sound.visibility = if (currentItem.type == MediaType.LIVE_PHOTO) VISIBLE else GONE
        sound.text = if (muted) "静音" else "声音开启"
        sound.contentDescription = if (muted) "实况已静音，点按开启声音" else "实况声音已开启，点按静音"
        favorite.text = actionText(if (currentItem.favorite) "♥" else "♡", if (currentItem.favorite) "已收藏" else "收藏")
        favorite.contentDescription = if (currentItem.favorite) "已收藏，点按取消收藏" else "收藏"
    }

    private fun changeItem(direction: Int) {
        val next = index + direction
        if (next !in works.indices) { toast(if (direction > 0) "已经是最后一个作品" else "已经是第一个作品"); return }
        cancelAnimations()
        index = next; currentItem = mediaRepo.get(works[next].id) ?: works[next]
        gifPlaying = true; held = false; loadedId = null
        loadCurrent(); showControls(true)
        if (animationsEnabled()) {
            scene.translationX = width * .12f * direction; scene.alpha = .35f
            animate(scene, DynamicAnimation.TRANSLATION_X, 0f); animate(scene, DynamicAnimation.ALPHA, 1f)
        }
    }

    private fun showControls(show: Boolean) {
        controlsVisible = show
        listOf(top, bottom).forEach { view ->
            animations.remove(view to DynamicAnimation.ALPHA)?.cancel()
            view.visibility = VISIBLE
            if (animationsEnabled()) animate(view, DynamicAnimation.ALPHA, if (show) 1f else 0f) {
                if (!controlsVisible) view.visibility = GONE
            } else { view.alpha = if (show) 1f else 0f; view.visibility = if (show) VISIBLE else GONE }
        }
    }

    private fun animate(view: View, property: DynamicAnimation.ViewProperty, value: Float, done: (() -> Unit)? = null) {
        val key = view to property
        animations.remove(key)?.cancel()
        val animation = SpringAnimation(view, property, value).apply {
            spring = SpringForce(value).apply { dampingRatio = .8f; stiffness = 400f }
            if (property == DynamicAnimation.ALPHA) { setMinValue(0f); setMaxValue(1f) }
        }
        animations[key] = animation
        animation.addEndListener { _, canceled, _, _ ->
            if (animations[key] === animation) animations.remove(key)
            if (!canceled) done?.invoke()
        }
        animation.start()
    }

    private fun cancelAnimations() {
        val running = animations.values.toList()
        animations.clear()
        running.forEach { it.cancel() }
    }

    private fun isDemo() = currentItem.localUri?.startsWith("android.resource:") == true || currentItem.motionVideoUri?.startsWith("android.resource:") == true
    private fun allowed(uri: Uri): Boolean {
        if (uri.scheme == "content" || uri.scheme == "android.resource" && uri.authority == activity.packageName) return true
        if (uri.scheme != "file" || !uri.authority.isNullOrEmpty()) return false
        return runCatching {
            val path = File(requireNotNull(uri.path)).canonicalPath
            listOf(File(activity.filesDir, "works"), activity.cacheDir).any { root ->
                path.startsWith(root.canonicalPath + File.separator)
            }
        }.getOrDefault(false)
    }

    private fun share() {
        val uri = currentItem.localUri?.let(Uri::parse)
        if (isDemo() || uri?.scheme != "content") { toast("演示资源未写入系统相册，暂不能分享文件"); return }
        if (!currentItem.exists) { showError("文件不存在，无法分享。"); return }
        val intent = Intent(Intent.ACTION_SEND).setType(if (currentItem.type == MediaType.GIF) "image/gif" else "image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(currentItem.title, uri)
        runCatching { activity.startActivity(Intent.createChooser(intent, "分享作品")) }.onFailure { toast("没有找到可分享的应用") }
    }

    private fun showMore() {
        show(AlertDialog.Builder(activity).setItems(arrayOf("作品信息", "分享", "删除")) { _, which ->
            when (which) { 0 -> showInfo(); 1 -> share(); 2 -> confirmDelete() }
        }.create())
    }

    private fun showInfo() {
        val original = currentItem.sourceMediaId?.let(mediaRepo::get)
        val lines = listOf("类型：${if (currentItem.type == MediaType.GIF) "GIF" else "实况"}",
            "尺寸：${currentItem.width} × ${currentItem.height}", "时长：${VideoLibraryUi.duration(currentItem.durationMs)}",
            "帧率：${currentItem.fps?.let { "$it fps" } ?: "未记录"}", "帧数：${currentItem.frameCount ?: "未记录"}",
            "大小：${TaskUi.bytes(currentItem.sizeBytes)}", "路径：${currentItem.localUri ?: currentItem.motionVideoUri ?: "无文件"}",
            "创建于：${VideoLibraryUi.downloaded(currentItem.downloadedAt)}", "来源：${original?.title ?: "原视频已不在库中"}",
            "来源片段：${VideoLibraryUi.duration(currentItem.sourceStartMs)} — ${VideoLibraryUi.duration(currentItem.sourceEndMs)}",
            "创建参数：\n${runCatching { JSONObject(currentItem.params).toString(2) }.getOrDefault(currentItem.params)}",
            if (isDemo()) "演示资源，未写入系统相册" else "已保存到系统相册")
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(28)); background = theme.shape(0xff25252c.toInt(), 24)
            addView(label("作品信息", 22f))
            addView(ScrollView(activity).apply {
                addView(label(lines.joinToString("\n\n"), 14f).apply { setPadding(0, dp(20), 0, dp(12)); setTextIsSelectable(true) })
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(button("关闭", "work_info_close") { activeDialog?.dismiss() })
        }
        val dialog = Dialog(activity).apply { requestWindowFeature(Window.FEATURE_NO_TITLE); setContentView(body) }
        show(dialog)
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setGravity(Gravity.BOTTOM); setLayout(-1, (resources.displayMetrics.heightPixels * .78f).toInt()) }
        if (animationsEnabled()) { body.translationY = dp(48).toFloat(); animate(body, DynamicAnimation.TRANSLATION_Y, 0f) }
    }

    private fun confirmDelete() {
        val uri = currentItem.localUri?.let(Uri::parse)
        val demo = isDemo()
        val deleteFile = CheckBox(activity).apply {
            tag = "work_delete_file"; text = "同时删除本地文件"; isChecked = false
            isEnabled = !demo && uri?.scheme == "content"
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), 0)
            addView(TextView(activity).apply { text = "移除“${currentItem.title}”这条记录？" })
            addView(deleteFile)
            if (demo) addView(TextView(activity).apply { text = "演示文件不会被删除。" })
        }
        show(AlertDialog.Builder(activity).setTitle("删除作品").setView(body).setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                val item = currentItem
                stopForBackground()
                if (!deleteFile.isChecked || uri == null) { mediaRepo.delete(item.id); onBack() }
                else Thread {
                    val result = runCatching { check(activity.contentResolver.delete(uri, null, null) > 0) }
                    handler.post {
                        result.onSuccess { mediaRepo.delete(item.id); if (isAttachedToWindow) onBack() }
                            .onFailure { if (isAttachedToWindow) toast("系统未允许删除文件，记录已保留") }
                    }
                }.start()
            }.create())
    }

    private fun show(dialog: Dialog) {
        activeDialog?.dismiss(); activeDialog = dialog
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
        dialog.show()
    }

    private fun stopAnimation() {
        if (Build.VERSION.SDK_INT >= 28) (animated as? AnimatedImageDrawable)?.stop()
        animated = null; image.setImageDrawable(null); legacyGif.setPlaying(false); movie = null
    }

    fun stopForBackground() {
        generation++; loadedId = null; worker?.shutdownNow(); worker = null
        stopAnimation(); held = false; liveActive = false; liveFrameRendered = false
        val player = engine; engine = null; motion.player = null
        player?.removeListener(liveListener); player?.release()
        cover.visibility = VISIBLE; motion.visibility = GONE; keepScreenOn = false
        activeDialog?.dismiss(); cancelAnimations()
    }

    fun release() { released = true; stopForBackground() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!released) loadCurrent() }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && !released) loadCurrent() else if (!isVisible) stopForBackground()
    }
    override fun onDetachedFromWindow() { release(); super.onDetachedFromWindow() }

    private fun showError(message: String) {
        stopAnimation(); liveActive = false; liveFrameRendered = false; engine?.pause(); cover.visibility = VISIBLE; motion.visibility = GONE; keepScreenOn = false
        error.removeAllViews()
        error.addView(label(message, 16f).apply { gravity = Gravity.CENTER })
        error.addView(button("移除记录", "work_remove_missing") { mediaRepo.delete(currentItem.id); stopForBackground(); onBack() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        error.visibility = VISIBLE; toggle.isEnabled = false; replay.isEnabled = false
    }

    private fun actionText(icon: String, value: String) = SpannableString("$icon\n$value").apply {
        setSpan(AbsoluteSizeSpan(26, true), 0, icon.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    private fun action(icon: String, value: String, tag: String, callback: () -> Unit) = button(value, tag, callback).apply {
        text = actionText(icon, value); textSize = 14f; contentDescription = value
        minWidth = dp(84); minHeight = dp(80); maxLines = 2
        setPadding(dp(12), dp(8), dp(12), dp(8)); setLineSpacing(dp(3).toFloat(), 1f)
        background = theme.control(0x66393942, 18)
    }
    private fun button(value: String, viewTag: String, callback: () -> Unit): TextView = label(value, 15f).apply {
        tag = viewTag; gravity = Gravity.CENTER; minHeight = dp(52); minWidth = dp(52)
        setPadding(dp(10), dp(6), dp(10), dp(6)); background = theme.control(0x88393942.toInt(), 100)
        isFocusable = true; setOnClickListener { UiMotion.haptic(this); callback() }; UiMotion.press(this)
    }
    private fun label(value: String, size: Float) = TextView(activity).apply { text = value; textSize = size; setTextColor(Color.WHITE) }
    private fun dp(value: Int) = theme.dp(value)
    private fun liveAudio() = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()
    private fun toast(value: String) = Toast.makeText(activity, value, Toast.LENGTH_SHORT).show()
    private fun animationsEnabled() = if (Build.VERSION.SDK_INT >= 26) android.animation.ValueAnimator.areAnimatorsEnabled()
        else android.provider.Settings.Global.getFloat(activity.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

    @Suppress("DEPRECATION")
    private inner class MovieCanvas : View(activity) {
        private var running = false
        private var offset = 0L
        private var started = 0L
        fun reset(play: Boolean) { offset = 0L; started = SystemClock.uptimeMillis(); running = play; invalidate() }
        fun setPlaying(play: Boolean) {
            if (running == play) return
            if (running) offset += SystemClock.uptimeMillis() - started else started = SystemClock.uptimeMillis()
            running = play; invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            val clip = movie ?: return
            val time = offset + if (running) SystemClock.uptimeMillis() - started else 0L
            clip.setTime((time % clip.duration().coerceAtLeast(1)).toInt())
            val scale = min(width.toFloat() / clip.width().coerceAtLeast(1), height.toFloat() / clip.height().coerceAtLeast(1))
            val save = canvas.save()
            canvas.translate((width - clip.width() * scale) / 2, (height - clip.height() * scale) / 2)
            canvas.scale(scale, scale); clip.draw(canvas, 0f, 0f); canvas.restoreToCount(save)
            if (running && isShown && !released) postInvalidateOnAnimation()
        }
    }
}
