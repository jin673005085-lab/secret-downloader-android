package cn.secret.downloader

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ThumbnailUtils
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

internal class MediaPicker(private val activity: Activity, private val theme: UiTheme) {
    private val thumbnails = Executors.newFixedThreadPool(2)
    private val previews = Executors.newSingleThreadExecutor()
    private var picker: AlertDialog? = null
    private var enlarged: AlertDialog? = null
    private fun dp(value: Int) = theme.dp(value)

    fun show(items: List<MediaSource>, onSelected: (List<String>) -> Unit): AlertDialog {
        val rows = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val boxes = items.mapIndexed { index, item ->
            val name = "${index + 1}. ${item.kind}"
            val row = LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val thumb = ImageView(activity).apply {
                tag = "media_thumb_$index"
                background = theme.shape(theme.muted, 10)
                clipToOutline = true
                setImageResource(android.R.drawable.ic_menu_gallery)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(FrameLayout(activity).apply {
                addView(thumb, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                if (item.isGif) addView(TextView(activity).apply {
                    tag = "media_gif_badge_$index"
                    text = "GIF"
                    textSize = 10f
                    setTextColor(Color.WHITE)
                    setPadding(dp(4), dp(2), dp(4), dp(2))
                    background = theme.shape(0xB3000000.toInt(), 4)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                    bottomMargin = dp(4); marginEnd = dp(4)
                })
            }, LinearLayout.LayoutParams(dp(64), dp(64)))
            val box = CheckBox(activity).apply {
                tag = "media_check_$index"
                text = name
                isChecked = true
                minimumHeight = dp(48)
                setTextColor(theme.text)
            }
            row.addView(box, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
            rows.addView(row, MATCH_PARENT, WRAP_CONTENT)
            if (item.previewCandidates.isNotEmpty() || (item.isGif && item.urls.isNotEmpty())) {
                thumb.contentDescription = "预览 $name"
                thumb.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                thumb.setOnClickListener { enlarge(item, name) }
                load(item, 192, { picker?.isShowing == true }) { bitmap ->
                    if (bitmap != null) {
                        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                        thumb.setImageBitmap(bitmap)
                    } else thumb.contentDescription = "缩略图不可用，点按重试预览 $name"
                }
            }
            box
        }
        return AlertDialog.Builder(activity).setTitle("选择要下载的媒体")
            .setView(ScrollView(activity).apply { addView(rows) })
            .setNegativeButton("取消", null)
            .setPositiveButton("下载所选") { _, _ ->
                val selection = items.filterIndexed { index, _ -> boxes[index].isChecked }.map { it.selectionKey }
                if (selection.isNotEmpty()) onSelected(selection)
            }.create().also { dialog ->
                picker = dialog
                dialog.setOnDismissListener {
                    enlarged?.dismiss()
                    thumbnails.shutdownNow()
                    previews.shutdownNow()
                }
                dialog.show()
                val download = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                boxes.forEach { box -> box.setOnCheckedChangeListener { _, _ -> download.isEnabled = boxes.any { it.isChecked } } }
            }
    }

    private fun enlarge(item: MediaSource, name: String) {
        val image = ImageView(activity).apply {
            tag = "media_enlarged_preview"
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = name
        }
        val status = TextView(activity).apply {
            text = "正在加载预览…"
            gravity = Gravity.CENTER
            setTextColor(theme.secondary)
        }
        val content = FrameLayout(activity).apply {
            minimumHeight = dp(260)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(image, FrameLayout.LayoutParams(MATCH_PARENT, dp(minOf(460, activity.resources.configuration.screenHeightDp / 2))))
            addView(status, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        val dialog = AlertDialog.Builder(activity).setTitle(name).setView(content).setPositiveButton("关闭", null).show()
        enlarged = dialog
        dialog.setOnDismissListener { if (enlarged === dialog) enlarged = null }
        load(item, 1280, { picker?.isShowing == true && dialog.isShowing }) { bitmap ->
            if (bitmap == null) status.text = "无法加载预览，仍可勾选下载"
            else {
                image.setImageBitmap(bitmap)
                status.visibility = View.GONE
            }
        }
    }

    private fun load(item: MediaSource, size: Int, visible: () -> Boolean, onResult: (Bitmap?) -> Unit) {
        val fullSize = size > 192
        val candidates = if (fullSize) {
            val original = item.livePhotoCoverUrls ?: item.urls.takeIf { item.kind == "图片" }.orEmpty()
            (original + item.previewCandidates.asReversed()).distinct()
        } else item.previewCandidates
        (if (fullSize) previews else thumbnails).execute {
            val bitmap = candidates.take(3).firstNotNullOfOrNull { url ->
                if (Thread.currentThread().isInterrupted) null
                else runCatching { fetchPreview(url, item.headers)?.let { decodeImage(it, size) } }.getOrNull()
            } ?: if (item.isGif && !Thread.currentThread().isInterrupted) runCatching {
                item.urls.firstOrNull()?.let { fetchPreview(it, item.headers) }?.let { bytes ->
                    decodeImage(bytes, size) ?: videoPoster(bytes)
                }
            }.getOrNull() else null
            activity.runOnUiThread {
                if (!activity.isDestroyed && visible()) onResult(bitmap) else bitmap?.recycle()
            }
        }
    }

    private fun fetchPreview(url: String, headers: Map<String, String>): ByteArray? {
        val connection = DownloadEngine.connection(url, headers).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
        }
        return try {
            if (connection.responseCode != 200 || connection.contentLength > MAX_PREVIEW_BYTES) return null
            val started = System.nanoTime()
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (!Thread.currentThread().isInterrupted && System.nanoTime() - started < 8_000_000_000L) {
                    val count = input.read(buffer)
                    if (count < 0) return@use output.toByteArray()
                    if (output.size() + count > MAX_PREVIEW_BYTES) return null
                    output.write(buffer, 0, count)
                }
                return null
            }
        } finally { connection.disconnect() }
    }

    private fun decodeImage(bytes: ByteArray, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > size) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    @Suppress("DEPRECATION")
    private fun videoPoster(bytes: ByteArray): Bitmap? {
        // X represents GIF attachments as MP4. Decode a frame only when the still image failed.
        val file = File.createTempFile("gif-poster-", ".mp4", activity.cacheDir)
        return try {
            file.writeBytes(bytes)
            ThumbnailUtils.createVideoThumbnail(file.path, MediaStore.Video.Thumbnails.MINI_KIND)
        } finally { file.delete() }
    }

    companion object {
        // shortcut: bounded in-memory previews; use a shared image cache if larger albums need it.
        private const val MAX_PREVIEW_BYTES = 6 * 1024 * 1024
    }
}
