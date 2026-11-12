package cn.secret.downloader

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object VideoLibraryUi {
    fun duration(milliseconds: Long): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1_000
        return if (seconds >= 3_600) String.format(Locale.US, "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
    }
    fun downloaded(timestamp: Long) = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    fun relativeDownloaded(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val minutes = (now - timestamp).coerceAtLeast(0) / 60_000
        return "下载于 " + when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "$minutes 分钟前"
            minutes < 1_440 -> "${minutes / 60} 小时前"
            minutes < 43_200 -> "${minutes / 1_440} 天前"
            else -> downloaded(timestamp).substringBefore(' ')
        }
    }
    fun stableId(id: String) = id.fold(1_125_899_906_842_597L) { hash, char -> 31 * hash + char.code }
    fun aspect(item: MediaItem) = if (item.width > 0 && item.height > 0) {
        (item.width.toFloat() / item.height).coerceIn(3f / 4f, 16f / 9f)
    } else 16f / 9f
}

/** Neutral fallback while the actual file cover loads, or when it cannot be decoded. */
internal class VideoCoverDrawable(private val item: MediaItem, private val theme: UiTheme) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var opacity = 255
    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        paint.color = theme.muted; paint.alpha = opacity; paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.color = theme.secondary; paint.alpha = opacity
        val size = minOf(w, h) * .24f
        val left = (w - size) / 2f; val top = (h - size * .72f) / 2f
        paint.style = Paint.Style.STROKE; paint.strokeWidth = maxOf(1f, size * .05f)
        canvas.drawRoundRect(left, top, left + size, top + size * .72f, size * .1f, size * .1f, paint)
        path.reset()
        if (!item.exists) {
            path.moveTo(left + size * .28f, top + size * .16f); path.lineTo(left + size * .72f, top + size * .56f)
            path.moveTo(left + size * .72f, top + size * .16f); path.lineTo(left + size * .28f, top + size * .56f)
        } else if (item.type == MediaType.VIDEO) {
            paint.style = Paint.Style.FILL
            path.moveTo(left + size * .4f, top + size * .2f)
            path.lineTo(left + size * .66f, top + size * .36f)
            path.lineTo(left + size * .4f, top + size * .52f); path.close()
        } else {
            canvas.drawCircle(left + size * .72f, top + size * .2f, size * .06f, paint)
            path.moveTo(left + size * .12f, top + size * .58f)
            path.lineTo(left + size * .36f, top + size * .32f)
            path.lineTo(left + size * .55f, top + size * .5f)
            path.lineTo(left + size * .7f, top + size * .4f)
            path.lineTo(left + size * .88f, top + size * .58f)
        }
        canvas.drawPath(path, paint)
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { opacity = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun getAlpha() = opacity
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = if (opacity == 255) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT

}
