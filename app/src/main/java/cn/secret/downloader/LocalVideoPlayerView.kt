package cn.secret.downloader

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem as PlaybackItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Owns one local playback engine while visible. The host owns the surrounding metadata page. */
@androidx.annotation.OptIn(UnstableApi::class)
class LocalVideoPlayerView(
    private val activity: Activity,
    private val theme: UiTheme,
    private val media: MediaRepository,
    private val onBack: () -> Unit,
    private val onMore: () -> Unit,
    private val onItemChanged: (MediaItem) -> Unit = {},
    private val onRedownload: (MediaItem) -> Unit = {},
    private val onFullscreenChanged: (Boolean) -> Unit = {},
) : FrameLayout(activity) {
    var currentItem: MediaItem? = null
        private set
    var isFullscreen = false
        private set
    var isEnteringPictureInPicture = false
        private set
    internal val playbackEngine: ExoPlayer? get() = engine
    private var engine: ExoPlayer? = null
    private var inPip = false
    private var counted = false
    private var wantedPlayback = true
    private var resumePosition = 0L
    private var lastSavedAt = 0L
    private var speed = 1f
    private var holdSpeed: Float? = null
    private var locked = false
    private var scrubbing = false
    private var failed = false
    private var controlsVisible = true
    private var originalOrientation = activity.requestedOrientation
    private var originalCutoutMode = 0
    private var fullscreenBack: android.window.OnBackInvokedCallback? = null
    @Suppress("DEPRECATION")
    private val originalSystemUiFlags = activity.window.decorView.systemUiVisibility
    private val originalBrightness = activity.window.attributes.screenBrightness
    private val handler = Handler(Looper.getMainLooper())
    private val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val hideControls = Runnable { if (engine?.isPlaying == true && !scrubbing) showControls(false) }
    private val clearHint = Runnable { hint.visibility = GONE }
    private val uiTick = object : Runnable {
        override fun run() {
            updateTimeline()
            if (engine?.isPlaying == true && SystemClock.elapsedRealtime() - lastSavedAt >= 5_000) saveProgress()
            if (engine != null) handler.postDelayed(this, 200)
        }
    }
    private val video = PlayerView(activity).apply {
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        setKeepContentOnPlayerReset(true)
        setShutterBackgroundColor(Color.BLACK)
    }
    private val cover = ImageView(activity).apply {
        tag = "video_player_cover"
        scaleType = ImageView.ScaleType.CENTER_CROP
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val controls = FrameLayout(activity)
    private val title = label("", 14f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val resolution = label("原画", 11f).apply { gravity = Gravity.CENTER; background = glass(); setPadding(dp(8), dp(5), dp(8), dp(5)) }
    private val playGlyph = PlayPauseGlyph(activity)
    private val playButton = FrameLayout(activity).apply {
        tag = "player_toggle"
        background = glass()
        addView(playGlyph, LayoutParams(dp(32), dp(32), Gravity.CENTER))
        contentDescription = "播放"
        isFocusable = true
        setOnClickListener { togglePlayback() }
        UiMotion.press(this)
    }
    private val busy = ProgressBar(activity).apply { tag = "player_buffering"; visibility = GONE; indeterminateTintList = ColorStateList.valueOf(Color.WHITE) }
    private val time = label("0:00 / 0:00", 12f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val progress = JellyProgressBar(activity, theme, 4)
    private val thumb = SeekThumb()
    private val seek: SeekBar = SeekBar(activity).apply {
        tag = "player_seek"
        max = 1_000
        progressDrawable = ColorDrawable(Color.TRANSPARENT)
        background = null
        thumb = this@LocalVideoPlayerView.thumb
        splitTrack = false
        setPadding(dp(8), 0, dp(8), 0)
        contentDescription = "播放进度"
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                scrubbing = true; handler.removeCallbacks(hideControls)
                this@LocalVideoPlayerView.thumb.animateScale(1.3f)
            }
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) updateTimeline(totalDuration() * value / 1_000)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                engine?.seekTo(totalDuration() * seekBar.progress / 1_000)
                scrubbing = false; this@LocalVideoPlayerView.thumb.animateScale(1f)
                saveProgress(immediate = true); updateTimeline(); scheduleHide()
            }
        })
    }
    private val speedButton = smallButton("1×", "播放速度", "player_speed") { showSpeeds() }
    private val subtitleButton = smallButton("字幕", "字幕", "player_subtitles") { showSubtitles() }.apply { visibility = GONE }
    private val pipButton = smallButton("▣", "画中画", "player_pip") { enterPictureInPicture() }
    private val fullscreenButton = smallButton("⛶", "进入全屏", "player_fullscreen") { setFullscreen(!isFullscreen) }
    private val lockButton = smallButton("锁定", "锁定屏幕", "player_lock") { setLocked(!locked) }.apply { visibility = GONE }
    private val hint = label("", 16f).apply {
        tag = "player_gesture_hint"
        gravity = Gravity.CENTER; setPadding(dp(18), dp(12), dp(18), dp(12))
        background = RippleDrawable(ColorStateList.valueOf(0x55ffffff), glass(), theme.shape(Color.WHITE, 20))
        visibility = GONE
    }
    private val resumeTip = label("", 12f).apply {
        tag = "player_resume_tip"; gravity = Gravity.CENTER; background = glass(); visibility = GONE
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }
    private val error = LinearLayout(activity).apply {
        tag = "player_error"
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        setPadding(dp(18), dp(12), dp(18), dp(12)); background = theme.shape(0xe6222222.toInt(), 20)
        visibility = GONE
    }
    private var controlsSpring: SpringAnimation? = null
    private var downX = 0f
    private var downY = 0f
    private var downPosition = 0L
    private var downVolume = 0
    private var downBrightness = .5f
    private var dragMode = 0
    private var dragPosition = 0L
    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
    private val gestures = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            if (!locked) showControls(!controlsVisible)
            return true
        }
        override fun onDoubleTap(event: MotionEvent): Boolean {
            if (locked || failed) return true
            when {
                event.x < width / 3f -> skip(-10_000)
                event.x > width * 2 / 3f -> skip(10_000)
                else -> togglePlayback()
            }
            return true
        }
        override fun onLongPress(event: MotionEvent) {
            if (locked || failed || engine == null || dragMode != 0) return
            holdSpeed = speed; setSpeed(2f); showHint("2× 倍速播放", false)
            handler.removeCallbacks(clearHint)
        }
    })

    init {
        tag = "local_video_player"
        setBackgroundColor(Color.BLACK)
        addView(video, LayoutParams(-1, -1))
        addView(cover, LayoutParams(-1, -1))
        addView(View(activity).apply {
            contentDescription = "视频手势区域；也可使用播放按钮与进度条"
            setOnClickListener { if (!locked) showControls(!controlsVisible) }
            setOnTouchListener { _, event -> handleTouch(event) }
        }, LayoutParams(-1, -1))
        controls.addView(View(activity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99000000.toInt(), Color.TRANSPARENT))
        }, LayoutParams(-1, dp(84), Gravity.TOP))
        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(4), dp(6), 0)
            addView(smallButton("‹", "返回", "player_back") { if (!handleBack()) onBack() }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4); marginEnd = dp(8) })
            addView(resolution)
            addView(smallButton("⋯", "视频更多操作", "player_more") { showControls(true); onMore() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        controls.addView(top, LayoutParams(-1, dp(52), Gravity.TOP))
        controls.addView(playButton, LayoutParams(dp(64), dp(64), Gravity.CENTER))
        val bottom = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(5))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, 0xb3000000.toInt()))
            addView(FrameLayout(activity).apply {
                addView(progress, LayoutParams(-1, dp(14), Gravity.CENTER_VERTICAL).apply { leftMargin = dp(8); rightMargin = dp(8) })
                addView(seek, LayoutParams(-1, -1))
            }, LinearLayout.LayoutParams(-1, dp(48)))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(time, LinearLayout.LayoutParams(0, -2, 1f))
                addView(speedButton, LinearLayout.LayoutParams(dp(52), dp(48)))
                addView(subtitleButton, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(pipButton, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(fullscreenButton, LinearLayout.LayoutParams(dp(48), dp(48)))
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }
        controls.addView(bottom, LayoutParams(-1, -2, Gravity.BOTTOM))
        addView(controls, LayoutParams(-1, -1))
        addView(busy, LayoutParams(dp(36), dp(36), Gravity.CENTER))
        addView(hint, LayoutParams(-2, -2, Gravity.CENTER))
        addView(resumeTip, LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(60) })
        addView(error, LayoutParams(-1, -2, Gravity.CENTER).apply { leftMargin = dp(20); rightMargin = dp(20) })
        addView(lockButton, LayoutParams(dp(52), dp(48), Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = dp(12) })
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Keep the central button clear of the 48dp seek target in the compact 16:9 panel.
        playButton.translationY = if (h < dp(300)) -dp(24).toFloat() else 0f
    }

    fun setMedia(item: MediaItem) {
        if (currentItem?.id == item.id && currentItem?.localUri == item.localUri) {
            currentItem = item; title.text = item.title
            return
        }
        saveProgress(immediate = true)
        engine?.stop()
        currentItem = media.get(item.id) ?: item
        counted = false; wantedPlayback = true; failed = false
        resumePosition = if (item.isFinished) 0 else item.lastPositionMs.coerceAtLeast(0)
        title.text = item.title
        MediaThumbnails.bind(cover, item, VideoCoverDrawable(item, theme)); cover.visibility = VISIBLE
        cover.contentDescription = item.title
        error.visibility = GONE; resumeTip.visibility = GONE; busy.visibility = GONE; playButton.visibility = VISIBLE
        seek.isEnabled = true
        onItemChanged(currentItem!!)
        updateResolution(item.width, item.height)
        updateTimeline(resumePosition)
        showControls(true)
        if (isAttachedToWindow && isShown) prepareCurrent()
    }

    private fun prepareCurrent() {
        val item = currentItem ?: return
        if (failed) return
        val uri = item.localUri?.let(Uri::parse)
        if (!item.exists || uri == null) { showError("找不到本地视频", "文件可能已被删除或移动，请移除记录或重新下载。"); return }
        if (!isLocalUri(uri)) { showError("无法打开视频", "请选择应用可读取的本地视频文件。"); return }
        val player = engine ?: ExoPlayer.Builder(activity).build().also {
            engine = it
            it.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            it.setHandleAudioBecomingNoisy(true)
            it.addListener(listener)
            video.player = it
        }
        player.setMediaItem(PlaybackItem.fromUri(uri), resumePosition)
        player.setPlaybackSpeed(speed)
        video.visibility = VISIBLE
        player.prepare()
        player.playWhenReady = wantedPlayback
        showControls(true)
        handler.removeCallbacks(uiTick); handler.post(uiTick)
        if (resumePosition > 0) {
            resumeTip.text = "已从 ${VideoLibraryUi.duration(resumePosition)} 继续播放"
            resumeTip.visibility = VISIBLE
            resumeTip.postDelayed({ resumeTip.visibility = GONE }, 3_000)
        }
    }

    private fun isLocalUri(uri: Uri): Boolean {
        if (uri.scheme == "content") return true
        if (uri.scheme != "file") return false
        val file = runCatching { File(uri.path ?: return false).canonicalFile }.getOrNull() ?: return false
        return listOfNotNull(activity.cacheDir, activity.filesDir, activity.getExternalFilesDir(null)).any { root ->
            val prefix = root.canonicalPath + File.separator
            file.path.startsWith(prefix)
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            keepScreenOn = isPlaying && isShown
            playGlyph.setPlaying(isPlaying)
            playButton.contentDescription = if (isPlaying) "暂停" else "播放"
            if (isPlaying) {
                if (!counted) { counted = true; currentItem?.let { media.recordPlayback(it.id, positionMs(), started = true) } }
                scheduleHide()
            } else {
                saveProgress(immediate = true)
                if (!inPip) showControls(true)
            }
        }
        override fun onPlaybackStateChanged(state: Int) {
            busy.visibility = if (state == Player.STATE_BUFFERING && !failed) VISIBLE else GONE
            if (state == Player.STATE_READY) syncMetadata()
            if (state == Player.STATE_ENDED) { wantedPlayback = false; saveProgress(immediate = true); showControls(true) }
            updateTimeline()
        }
        override fun onRenderedFirstFrame() { cover.visibility = GONE; busy.visibility = GONE }
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            updateResolution(videoSize.width, videoSize.height); syncMetadata()
        }
        override fun onTracksChanged(tracks: Tracks) {
            subtitleButton.visibility = if (tracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSupported }) VISIBLE else GONE
        }
        override fun onPlayerError(error: PlaybackException) {
            val missing = generateSequence<Throwable>(error) { it.cause }.any { it is FileNotFoundException } ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND || error.errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION
            if (missing) currentItem?.let { item -> media.get(item.id)?.let { media.update(it.copy(exists = false)) } }
            showError(if (missing) "文件无法读取" else "暂时无法播放这个格式",
                if (missing) "文件可能已移动，或文件访问授权已失效。" else "可尝试用其他播放器打开，或从原链接重新下载。")
        }
    }

    private fun syncMetadata() {
        val item = currentItem ?: return
        val player = engine ?: return
        val original = media.get(item.id) ?: return
        val total = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: original.durationMs
        val size = player.videoSize
        val updated = original.copy(durationMs = total,
            width = size.width.takeIf { it > 0 } ?: original.width,
            height = size.height.takeIf { it > 0 } ?: original.height)
        currentItem = updated
        if (updated != original) { media.update(updated); onItemChanged(updated) }
    }

    fun positionMs(): Long = engine?.currentPosition?.coerceAtLeast(0) ?: resumePosition
    private fun totalDuration(): Long = engine?.duration?.takeIf { it > 0 && it != C.TIME_UNSET } ?: currentItem?.durationMs ?: 0L

    private fun saveProgress(immediate: Boolean = false) {
        val item = currentItem ?: return
        resumePosition = positionMs()
        if (counted) media.recordPlayback(item.id, resumePosition, flushImmediately = immediate)
        lastSavedAt = SystemClock.elapsedRealtime()
    }

    private fun togglePlayback() {
        val player = engine ?: run { if (!failed) prepareCurrent(); return }
        if (failed) return
        if (player.isPlaying || player.playWhenReady) { wantedPlayback = false; player.pause() }
        else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            wantedPlayback = true; player.play()
        }
        showControls(true)
    }

    private fun skip(delta: Long) {
        val player = engine ?: return
        val total = totalDuration()
        val destination = (positionMs() + delta).coerceIn(0, if (total > 0) total else Long.MAX_VALUE)
        player.seekTo(destination); updateTimeline(destination)
        showHint(if (delta > 0) "快进 10 秒" else "后退 10 秒", true)
    }

    private fun updateTimeline(preview: Long? = null) {
        val total = totalDuration().coerceAtLeast(0)
        val position = (preview ?: positionMs()).coerceAtLeast(0)
        val value = "${VideoLibraryUi.duration(position)} / ${VideoLibraryUi.duration(total)}"
        if (time.text.toString() != value) time.text = value
        val fraction = if (total > 0) (position.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
        if (!scrubbing && preview == null) seek.progress = (fraction * 1_000).roundToInt()
        // Playback can seek backwards; each target is a new progress segment rather than a task attempt.
        progress.setProgress(fraction, TaskStatus.RUNNING, "playback:${currentItem?.id}:$fraction")
        seek.contentDescription = "播放进度，${VideoLibraryUi.duration(position)}，共 ${VideoLibraryUi.duration(total)}"
    }

    private fun updateResolution(width: Int, height: Int) {
        resolution.text = if (width > 0 && height > 0) "${min(width, height)}P" else "原画"
        resolution.contentDescription = "分辨率 $width × $height"
    }

    private fun showControls(show: Boolean) {
        controlsVisible = show
        handler.removeCallbacks(hideControls)
        controlsSpring?.cancel()
        if (inPip || locked) { controls.visibility = GONE; return }
        if (!show && !animationsEnabled()) { controls.visibility = GONE; controls.alpha = 0f; return }
        controls.visibility = VISIBLE
        if (animationsEnabled()) {
            controlsSpring = SpringAnimation(controls, DynamicAnimation.ALPHA, if (show) 1f else 0f).apply {
                spring = SpringForce(if (show) 1f else 0f).apply { dampingRatio = 1f; stiffness = 380f }
                setMinValue(0f); setMaxValue(1f)
                addEndListener { _, canceled, _, _ -> if (!canceled && !controlsVisible) controls.visibility = GONE }
                start()
            }
        } else controls.alpha = if (show) 1f else 0f
        if (show) scheduleHide()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        if (engine?.isPlaying == true && !locked && !scrubbing) handler.postDelayed(hideControls, 3_000)
    }

    private fun showSpeeds() {
        val options = floatArrayOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)
        handler.removeCallbacks(hideControls)
        AlertDialog.Builder(activity).setTitle("播放速度")
            .setSingleChoiceItems(options.map { "$it×" }.toTypedArray(), options.indexOfFirst { it == speed }) { dialog, index ->
                setSpeed(options[index]); dialog.dismiss(); scheduleHide()
            }.setNegativeButton("取消", null).setOnDismissListener { scheduleHide() }.show()
    }

    private fun setSpeed(value: Float) {
        speed = value
        engine?.setPlaybackSpeed(value)
        speedButton.text = String.format(Locale.US, if (value % 1f == 0f) "%.0f×" else "%.2f×", value).replace("0×", "×")
    }

    private fun showSubtitles() {
        val player = engine ?: return
        val choices = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it }
        }
        if (choices.isEmpty()) return
        val labels = arrayOf("关闭字幕") + choices.mapIndexed { index, (group, track) ->
            group.getTrackFormat(track).label ?: group.getTrackFormat(track).language ?: "字幕 ${index + 1}"
        }
        val selected = choices.indexOfFirst { (group, track) -> group.isTrackSelected(track) } + 1
        AlertDialog.Builder(activity).setTitle("字幕").setSingleChoiceItems(labels, selected) { dialog, index ->
            val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, index == 0)
            if (index > 0) choices[index - 1].let { (group, track) -> builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup, track)) }
            player.trackSelectionParameters = builder.build()
            dialog.dismiss(); scheduleHide()
        }.setNegativeButton("取消", null).show()
    }

    @SuppressLint("ClickableViewAccessibility") // All gestures have focusable button/seek-bar alternatives.
    private fun handleTouch(event: MotionEvent): Boolean {
        if (locked) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x; downY = event.y; downPosition = positionMs(); dragPosition = downPosition; dragMode = 0
            downVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            downBrightness = activity.window.attributes.screenBrightness.takeIf { it >= 0 }
                ?: (Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
        }
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (holdSpeed == null && engine != null && !failed) {
                val dx = event.x - downX; val dy = event.y - downY
                if (dragMode == 0 && max(abs(dx), abs(dy)) > touchSlop * 2) {
                    dragMode = if (abs(dx) > abs(dy)) 1 else if (downX < width / 2) 2 else 3
                    handler.removeCallbacks(hideControls)
                }
                when (dragMode) {
                    1 -> {
                        dragPosition = (downPosition + dx / width.coerceAtLeast(1) * min(totalDuration(), 180_000L)).toLong()
                            .coerceIn(0, totalDuration().coerceAtLeast(0))
                        updateTimeline(dragPosition)
                        showHint("${VideoLibraryUi.duration(dragPosition)} / ${VideoLibraryUi.duration(totalDuration())}", false)
                    }
                    2 -> {
                        val value = (downBrightness - dy / height.coerceAtLeast(1) * 1.5f).coerceIn(.01f, 1f)
                        activity.window.attributes = activity.window.attributes.apply { screenBrightness = value }
                        showHint("亮度 ${(value * 100).roundToInt()}%", false)
                    }
                    3 -> {
                        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val value = (downVolume - dy / height.coerceAtLeast(1) * maximum * 1.5f).roundToInt().coerceIn(0, maximum)
                        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0) }
                        showHint("音量 ${if (maximum > 0) value * 100 / maximum else 0}%", false)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                holdSpeed?.let { previous -> holdSpeed = null; setSpeed(previous) }
                if (dragMode == 1 && event.actionMasked == MotionEvent.ACTION_UP) { engine?.seekTo(dragPosition); saveProgress(immediate = true) }
                dragMode = 0; handler.postDelayed(clearHint, 700); scheduleHide()
            }
        }
        return true
    }

    private fun showHint(message: String, ripple: Boolean) {
        handler.removeCallbacks(clearHint)
        hint.text = message; hint.visibility = VISIBLE
        if (ripple) {
            hint.post {
                hint.background.setHotspot(hint.width / 2f, hint.height / 2f)
                hint.isPressed = true
                hint.postDelayed({ hint.isPressed = false }, 160)
            }
        }
        handler.postDelayed(clearHint, 900)
    }

    private fun setFullscreen(value: Boolean, changeOrientation: Boolean = true) {
        if (isFullscreen == value) return
        if (value) {
            originalOrientation = activity.requestedOrientation
            if (Build.VERSION.SDK_INT >= 28) originalCutoutMode = activity.window.attributes.layoutInDisplayCutoutMode
        }
        isFullscreen = value
        if (!value) setLocked(false)
        lockButton.visibility = if (value && !inPip) VISIBLE else GONE
        fullscreenButton.contentDescription = if (value) "退出全屏" else "进入全屏"
        // Landscape without the cutout opt-in leaves a wide strip of window background beside the notch.
        if (Build.VERSION.SDK_INT >= 28) {
            val attributes = activity.window.attributes
            attributes.layoutInDisplayCutoutMode = if (!value) originalCutoutMode
                else if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            activity.window.attributes = attributes
        }
        if (changeOrientation) activity.requestedOrientation = if (value) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else originalOrientation
        updateSystemBars()
        updateBackHandling()
        onFullscreenChanged(value)
        showControls(true)
    }

    /**
     * While fullscreen the back gesture must only leave fullscreen. An overlay callback swallows it
     * before the shell (or the system) turns the gesture into "exit the app".
     */
    private fun updateBackHandling() {
        if (Build.VERSION.SDK_INT < 33) return
        fullscreenBack?.let { callback ->
            runCatching { activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback) }
            fullscreenBack = null
        }
        if (!isFullscreen) return
        val callback = if (Build.VERSION.SDK_INT >= 34) object : android.window.OnBackAnimationCallback {
            override fun onBackStarted(backEvent: android.window.BackEvent) = Unit
            override fun onBackProgressed(backEvent: android.window.BackEvent) = Unit
            override fun onBackCancelled() = Unit
            override fun onBackInvoked() { if (isFullscreen) setFullscreen(false) }
        } else {
            android.window.OnBackInvokedCallback { if (isFullscreen) setFullscreen(false) }
        }
        fullscreenBack = callback
        runCatching {
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        }
    }

    private fun updateSystemBars() {
        val hide = isFullscreen || inPip
        if (Build.VERSION.SDK_INT >= 30) activity.window.insetsController?.apply {
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (hide) {
                // Light status-bar icons would be invisible on the black player surface.
                setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
                hide(WindowInsets.Type.systemBars())
            } else {
                show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            activity.window.decorView.systemUiVisibility = if (hide) {
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else originalSystemUiFlags
        }
    }

    private fun setLocked(value: Boolean) {
        locked = value
        lockButton.text = if (value) "解锁" else "锁定"
        lockButton.contentDescription = if (value) "解锁屏幕" else "锁定屏幕"
        if (value) { controlsSpring?.cancel(); controls.visibility = GONE; handler.removeCallbacks(hideControls) }
        else showControls(true)
    }

    fun handleBack(): Boolean {
        if (isFullscreen) { setFullscreen(false); return true }
        return false
    }

    fun onHostConfigurationChanged() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (!inPip && landscape != isFullscreen) setFullscreen(landscape, changeOrientation = false)
        updateSystemBars()
        updateBackHandling()
    }

    private fun enterPictureInPicture() {
        if (Build.VERSION.SDK_INT < 26 || !activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            toast("此系统不支持画中画"); return
        }
        if (engine == null || failed) { toast("先打开一个可播放的视频"); return }
        val item = currentItem ?: return
        val rect = Rect(); video.getGlobalVisibleRect(rect)
        val aspect = if (item.width > 0 && item.height > 0) item.width.toFloat() / item.height else 16f / 9
        val params = PictureInPictureParams.Builder().setAspectRatio(Rational((aspect.coerceIn(.42f, 2.38f) * 1_000).toInt(), 1_000))
            .setSourceRectHint(rect).build()
        isEnteringPictureInPicture = true
        val entered = runCatching { activity.enterPictureInPictureMode(params) }.getOrDefault(false)
        if (!entered) { isEnteringPictureInPicture = false; toast("无法进入画中画，请检查系统设置") }
    }

    fun onPictureInPictureModeChanged(value: Boolean) {
        inPip = value; isEnteringPictureInPicture = false
        controls.visibility = if (value) GONE else VISIBLE
        lockButton.visibility = if (!value && isFullscreen) VISIBLE else GONE
        hint.visibility = GONE; resumeTip.visibility = GONE
        updateSystemBars()
        if (!value) showControls(true)
    }

    fun pauseForBackground() {
        if (inPip || isEnteringPictureInPicture) return
        wantedPlayback = false
        holdSpeed?.let { holdSpeed = null; setSpeed(it) }
        engine?.pause(); saveProgress(immediate = true); keepScreenOn = false
    }

    fun release() {
        saveProgress(immediate = true)
        handler.removeCallbacks(uiTick); handler.removeCallbacks(hideControls); handler.removeCallbacks(clearHint)
        controlsSpring?.cancel(); thumb.cancel()
        if (Build.VERSION.SDK_INT >= 33) fullscreenBack?.let { callback -> runCatching { activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback) } }
        fullscreenBack = null
        holdSpeed?.let { previous -> holdSpeed = null; setSpeed(previous) }
        val player = engine
        engine = null
        // A retained frame would stay in the SurfaceView (which ignores the view alpha) and leak a
        // frozen, semi-transparent player rectangle over the list while this page animates away.
        video.setKeepContentOnPlayerReset(false)
        video.player = null
        video.visibility = GONE
        player?.removeListener(listener); player?.release()
        keepScreenOn = false
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = originalBrightness }
        busy.visibility = GONE; playGlyph.setPlaying(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (currentItem != null && isShown && engine == null) prepareCurrent()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && engine == null && currentItem != null) prepareCurrent()
        else if (!isVisible && !inPip && !isEnteringPictureInPicture) { wantedPlayback = false; release() }
    }

    override fun onDetachedFromWindow() {
        release()
        if (isFullscreen) setFullscreen(false)
        super.onDetachedFromWindow()
    }

    private fun showError(title: String, message: String) {
        failed = true; wantedPlayback = false; engine?.pause(); keepScreenOn = false; busy.visibility = GONE
        seek.isEnabled = false
        error.removeAllViews()
        error.addView(label(title, 17f).apply { gravity = Gravity.CENTER })
        error.addView(label(message, 12f).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)) })
        error.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(smallButton("移除记录", "移除记录", "player_remove") { currentItem?.let { media.delete(it.id); onBack() } })
            currentItem?.takeIf { !it.sourceUrl.isNullOrBlank() }?.let { item ->
                addView(smallButton("重新下载", "重新下载", "player_redownload") { onRedownload(item) })
            }
            currentItem?.localUri?.let(Uri::parse)?.takeIf { it.scheme == "content" }?.let { uri ->
                addView(smallButton("外部打开", "用其他播放器打开", "player_external") { openExternally(uri) })
            }
        })
        error.visibility = VISIBLE
        playButton.visibility = GONE
        showControls(true)
    }

    private fun openExternally(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(currentItem?.title ?: "视频", uri)
        runCatching { activity.startActivity(Intent.createChooser(intent, "用其他播放器打开")) }.onFailure { toast("没有找到可打开视频的应用") }
    }

    private fun smallButton(value: String, description: String, viewTag: String, action: () -> Unit) = label(value, 13f).apply {
        tag = viewTag; contentDescription = description; gravity = Gravity.CENTER
        minHeight = dp(48); minWidth = dp(48); setPadding(dp(8), dp(4), dp(8), dp(4))
        background = glass(); isFocusable = true; setOnClickListener { UiMotion.haptic(this); action() }; UiMotion.press(this)
    }
    private fun label(value: String, size: Float) = TextView(activity).apply { text = value; textSize = size; setTextColor(Color.WHITE) }
    private fun glass() = theme.shape(0x77303030, 100)
    private fun dp(value: Int) = theme.dp(value)
    private fun toast(value: String) = Toast.makeText(activity, value, Toast.LENGTH_SHORT).show()
    private fun animationsEnabled() = if (Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled()
        else Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

    private inner class SeekThumb : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private var scale = 1f
        private val motion = SpringAnimation(FloatValueHolder(1f)).apply {
            spring = SpringForce(1f).apply { dampingRatio = .6f; stiffness = 400f }
            addUpdateListener { _, value, _ -> scale = value; invalidateSelf() }
        }
        fun animateScale(value: Float) {
            if (animationsEnabled()) { if (!motion.isRunning) motion.setStartValue(scale); motion.animateToFinalPosition(value) }
            else { scale = value; invalidateSelf() }
        }
        fun cancel() { motion.cancel() }
        override fun draw(canvas: Canvas) { canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), dp(6) * scale, paint) }
        override fun getIntrinsicWidth() = dp(18)
        override fun getIntrinsicHeight() = dp(18)
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}

private class PlayPauseGlyph(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()
    private var blend = 0f
    private val motion = SpringAnimation(FloatValueHolder(0f)).apply {
        spring = SpringForce(0f).apply { dampingRatio = .8f; stiffness = 400f }
        setMinValue(0f); setMaxValue(1f)
        addUpdateListener { _, value, _ -> blend = value; invalidate() }
    }
    fun setPlaying(playing: Boolean) {
        val animate = if (Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled() else
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        if (!animate) { motion.cancel(); blend = if (playing) 1f else 0f; invalidate(); return }
        if (!motion.isRunning) motion.setStartValue(blend)
        motion.animateToFinalPosition(if (playing) 1f else 0f)
    }
    override fun onDraw(canvas: Canvas) {
        val left = width * .23f; val top = height * .15f; val bottom = height * .85f
        paint.alpha = ((1f - blend) * 255).toInt()
        path.reset(); path.moveTo(left + width * .05f * blend, top); path.lineTo(left + width * .05f * blend, bottom)
        path.lineTo(width * .82f - width * .1f * blend, height / 2f); path.close(); canvas.drawPath(path, paint)
        paint.alpha = (blend * 255).toInt()
        canvas.drawRoundRect(left, top, width * .43f, bottom, width * .035f, width * .035f, paint)
        canvas.drawRoundRect(width * .59f, top, width * .79f, bottom, width * .035f, width * .035f, paint)
        paint.alpha = 255
    }
    override fun onDetachedFromWindow() { motion.cancel(); super.onDetachedFromWindow() }
}
