package cn.secret.downloader

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Typeface
import androidx.exifinterface.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

class VideoCoverActivity : Activity() {
    private lateinit var theme: UiTheme
    private lateinit var videoUri: Uri
    private lateinit var preview: ImageView
    private lateinit var position: TextView
    private lateinit var message: TextView
    private lateinit var seek: SeekBar
    private lateinit var confirm: TextView
    private lateinit var photoButton: TextView
    private lateinit var frameButton: TextView
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    private var retriever: MediaMetadataRetriever? = null
    private var bitmap: Bitmap? = null
    private var imageUri: Uri? = null
    private var timestampUs = 0L
    private var durationUs = 0L
    @Volatile private var destroyed = false
    private val pendingFrame = Runnable { if (!destroyed) loadFrame() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri?.scheme != "content") {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        videoUri = uri
        imageUri = savedInstanceState?.getString(EXTRA_COVER_URI)?.let(Uri::parse)
        timestampUs = savedInstanceState?.getLong(EXTRA_COVER_TIME_US, 0) ?: 0
        theme = UiTheme(this)
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.dp(20), theme.dp(20), theme.dp(20), theme.dp(20))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(theme.background)
            addView(body)
        }
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                scroll.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        scroll.requestApplyInsets()
        body.addView(label("选择实况封面", UiTheme.TITLE, true))
        body.addView(label("拖动进度选取视频画面，或从相册选择图片。只更换封面，保留完整视频。", UiTheme.BODY), spaced())
        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = theme.shape(theme.muted)
            clipToOutline = true
            contentDescription = "所选实况封面预览"
        }
        body.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, theme.dp(280)).apply {
            topMargin = theme.dp(20)
        })
        position = label("正在读取视频…", UiTheme.BODY)
        body.addView(position, spaced())
        seek = SeekBar(this).apply {
            isEnabled = false
            contentDescription = "拖动选择封面画面，不裁剪视频"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    timestampUs = frameTimeUs(progress, max, durationUs)
                    imageUri = null
                    position.text = "视频封面 ${time(timestampUs)} / ${time(durationUs)}"
                    confirm.isEnabled = false
                    generation.incrementAndGet()
                    handler.removeCallbacks(pendingFrame)
                    handler.postDelayed(pendingFrame, 150)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {
                    imageUri = null
                    confirm.isEnabled = false
                    generation.incrementAndGet()
                }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    handler.removeCallbacks(pendingFrame)
                    loadFrame()
                }
            })
        }
        body.addView(seek, spaced())
        val choices = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        frameButton = action("从视频选帧", false) {
            handler.removeCallbacks(pendingFrame)
            imageUri = null
            loadFrame()
        }
        photoButton = action("从相册选图", false) {
            handler.removeCallbacks(pendingFrame)
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, PICK_IMAGE)
            } catch (_: Exception) { showError("无法打开系统图片选择器") }
        }
        choices.addView(frameButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        choices.addView(photoButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = theme.dp(8)
        })
        frameButton.isEnabled = false
        photoButton.isEnabled = false
        body.addView(choices, spaced())
        message = label("正在生成封面预览…", UiTheme.LABEL).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        body.addView(message, spaced())
        confirm = action("使用此封面并转换", true) { finishSelection() }.apply { isEnabled = false }
        body.addView(confirm, spaced())
        body.addView(action("取消", false) { finish() }, spaced())
        loadVideo()
    }

    private fun loadVideo() {
        worker.execute {
            try {
                val reader = MediaMetadataRetriever()
                retriever = reader
                reader.setDataSource(this, videoUri)
                val durationMs = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?: throw IOException("无法读取视频时长")
                require(durationMs in 1..Long.MAX_VALUE / 1000) { "视频时长无效" }
                val length = durationMs * 1000
                handler.post {
                    if (destroyed) return@post
                    durationUs = length
                    timestampUs = timestampUs.coerceIn(0, (length - 1000).coerceAtLeast(0))
                    seek.max = durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
                    seek.progress = ((timestampUs.toDouble() / length) * seek.max).roundToInt().coerceIn(0, seek.max)
                    seek.isEnabled = true
                    frameButton.isEnabled = true
                    photoButton.isEnabled = true
                    val selected = imageUri
                    if (selected == null) loadFrame() else loadImage(selected)
                }
            } catch (error: Exception) {
                handler.post { if (!destroyed) showError(error.message ?: "无法读取所选视频") }
            }
        }
    }

    private fun loadFrame() {
        val at = timestampUs
        position.text = "视频封面 ${time(at)} / ${time(durationUs)}"
        loadPreview("正在提取所选视频画面…") {
            val reader = retriever ?: throw IOException("视频尚未准备好")
            val width = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 960
            val height = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 960
            val (targetWidth, targetHeight) = previewSize(width, height)
            if (Build.VERSION.SDK_INT >= 27) {
                reader.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST, targetWidth, targetHeight)
                    ?: throw IOException("无法提取该画面，请换一个时间位置")
            } else {
                val source = reader.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: throw IOException("无法提取该画面，请换一个时间位置")
                val (w, h) = previewSize(source.width, source.height)
                Bitmap.createScaledBitmap(source, w, h, true).also { if (it !== source) source.recycle() }
            }
        }
    }

    private fun loadImage(uri: Uri) {
        handler.removeCallbacks(pendingFrame)
        position.text = "相册图片封面 · 视频时长 ${time(durationUs)}"
        loadPreview("正在读取所选图片…") {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val stream = contentResolver.openInputStream(uri) ?: throw IOException("无法读取所选图片")
            stream.use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "所选文件不是可读取的图片" }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1280) sample *= 2
            val decoded = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: throw IOException("无法解码所选图片")
            try {
                val orientation = contentResolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL
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
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            } catch (_: IOException) { decoded }
        }
    }

    private fun loadPreview(status: String, decode: () -> Bitmap) {
        val ticket = generation.incrementAndGet()
        confirm.isEnabled = false
        message.setTextColor(theme.secondary)
        message.text = status
        worker.execute {
            if (destroyed || generation.get() != ticket) return@execute
            try {
                val image = decode()
                handler.post {
                    if (destroyed || generation.get() != ticket) image.recycle()
                    else {
                        preview.setImageBitmap(image)
                        bitmap?.takeIf { it !== image }?.recycle()
                        bitmap = image
                        message.text = if (imageUri == null) "已选视频画面，转换时使用原始清晰度。" else "已选相册图片，转换时使用原图。"
                        message.setTextColor(theme.secondary)
                        confirm.isEnabled = true
                    }
                }
            } catch (error: Exception) {
                handler.post { if (!destroyed && generation.get() == ticket) showError(error.message ?: "无法生成封面预览") }
            }
        }
    }

    @Deprecated("Uses the platform document picker without another UI dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data?.takeIf { it.scheme == "content" } ?: return
        imageUri = uri
        loadImage(uri)
    }

    private fun finishSelection() {
        if (!confirm.isEnabled) return
        val selected = imageUri
        val result = Intent().setData(videoUri).apply {
            putExtra(EXTRA_COVER_TIME_US, if (selected == null) timestampUs else -1L)
            putExtra(EXTRA_COVER_URI, selected?.toString())
            clipData = ClipData.newRawUri("实况视频", videoUri).apply {
                if (selected != null) addItem(ClipData.Item(selected))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        setResult(RESULT_OK, result)
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(EXTRA_COVER_URI, imageUri?.toString())
        outState.putLong(EXTRA_COVER_TIME_US, timestampUs)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        destroyed = true
        generation.incrementAndGet()
        handler.removeCallbacks(pendingFrame)
        if (::preview.isInitialized) preview.setImageDrawable(null)
        bitmap?.recycle()
        bitmap = null
        worker.execute {
            try { retriever?.release() } catch (_: Exception) { }
            retriever = null
        }
        worker.shutdown()
        super.onDestroy()
    }

    private fun showError(text: String) {
        confirm.isEnabled = false
        message.setTextColor(theme.error)
        message.text = text
    }

    private fun label(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        setTextColor(if (bold) theme.text else theme.secondary)
    }

    private fun action(value: String, primary: Boolean, click: () -> Unit) = label(value, UiTheme.BODY, true).apply {
        gravity = Gravity.CENTER
        minHeight = theme.dp(UiTheme.TOUCH_TARGET)
        setPadding(theme.dp(12), theme.dp(12), theme.dp(12), theme.dp(12))
        setTextColor(if (primary) theme.onPrimary else theme.primaryText)
        background = theme.control(if (primary) theme.primary else theme.primarySoft)
        isFocusable = true
        setOnClickListener { UiMotion.haptic(this); click() }
        UiMotion.press(this)
    }

    private fun spaced() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = theme.dp(UiTheme.CARD_GAP)
    }

    private fun time(us: Long): String {
        val ms = us / 1000
        return String.format(Locale.ROOT, "%d:%02d.%03d", ms / 60_000, ms / 1000 % 60, ms % 1000)
    }

    companion object {
        const val EXTRA_COVER_URI = "cover_uri"
        const val EXTRA_COVER_TIME_US = "cover_time_us"
        private const val PICK_IMAGE = 201

        internal fun frameTimeUs(progress: Int, maximum: Int, durationUs: Long): Long {
            require(maximum > 0 && durationUs > 0 && progress in 0..maximum)
            return (progress.toDouble() / maximum * (durationUs - 1000).coerceAtLeast(0)).toLong()
        }

        internal fun previewSize(width: Int, height: Int): Pair<Int, Int> {
            require(width > 0 && height > 0)
            val scale = minOf(1.0, 960.0 / maxOf(width, height))
            return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
        }
    }
}
