package cn.secret.downloader

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.ceil
import kotlin.math.max

/** The backdrop belongs to a sibling, so the snapshot never includes the tabs themselves. */
class BottomTabBar(
    context: Context,
    private val theme: UiTheme,
    private val onSelect: (Int) -> Unit,
) : FrameLayout(context) {
    private val labels = listOf("下载", "转换", "视频库")
    private val buttons = mutableListOf<FrameLayout>()
    private val icons = mutableListOf<ImageView>()
    private val titles = mutableListOf<TextView>()
    private val badges = mutableListOf<TextView>()
    private val iconSprings = mutableListOf<Pair<SpringAnimation, SpringAnimation>>()
    private val bounceTimes = LongArray(3)
    private val badgeCounts = IntArray(3)
    private val snapshot = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val tint = View(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var backdrop: View? = null
    private var observer: ViewTreeObserver? = null
    private var bitmap: Bitmap? = null
    private var blurAvailable = Build.VERSION.SDK_INT >= 31
    private var pendingCapture = false
    private var dirty = true
    private var lastCapture = 0L
    private var lastSignature = 0L
    private var bottomInset = 0
    var selectedIndex: Int = 0
        private set

    private val capture = Runnable {
        pendingCapture = false
        captureBackdrop()
    }
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { refreshBackdrop() }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { refreshBackdrop() }
    private val drawListener = ViewTreeObserver.OnPreDrawListener {
        val source = backdrop
        if (blurAvailable && isShown && source != null &&
            (dirty || source.isDirty || visualSignature(source) != lastSignature)) {
            scheduleCapture()
        }
        true
    }

    init {
        tag = "bottom_tabs"
        clipChildren = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(snapshot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (Build.VERSION.SDK_INT >= 31) {
            snapshot.setRenderEffect(RenderEffect.createBlurEffect(
                theme.dp(18).toFloat(), theme.dp(18).toFloat(), Shader.TileMode.CLAMP))
        }
        updateTint()
        addView(tint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            labels.forEachIndexed { index, title ->
                addView(makeButton(index, title), LinearLayout.LayoutParams(0, theme.dp(56), 1f))
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, theme.dp(56)))
        addView(View(context).apply {
            setBackgroundColor(alpha(theme.text, 0.12f))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(LayoutParams.MATCH_PARENT, max(1, ceil(resources.displayMetrics.density * 0.5).toInt())))
        select(0, false)
    }

    private fun makeButton(index: Int, title: String): FrameLayout {
        val icon = ImageView(context).apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        icons += icon
        val label = TextView(context).apply {
            text = title
            textSize = 10f
            includeFontPadding = false
            gravity = Gravity.CENTER
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        titles += label
        val badge = TextView(context).apply {
            textSize = 10f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            gravity = Gravity.CENTER
            minWidth = theme.dp(16)
            minHeight = theme.dp(16)
            setPadding(theme.dp(4), 0, theme.dp(4), 0)
            setTextColor(theme.onPrimary)
            background = theme.shape(theme.error, UiTheme.PILL_RADIUS)
            visibility = GONE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        badges += badge
        val x = iconSpring(icon, DynamicAnimation.SCALE_X)
        val y = iconSpring(icon, DynamicAnimation.SCALE_Y)
        iconSprings += x to y
        return FrameLayout(context).apply {
            tag = "tab_button_$index"
            isClickable = true
            isFocusable = true
            background = RippleDrawable(ColorStateList.valueOf(alpha(theme.primary, 0.12f)), null, null)
            accessibilityDelegate = object : AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Button"
                }
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                clipChildren = false
                addView(FrameLayout(context).apply {
                    clipChildren = false
                    addView(icon, LayoutParams(theme.dp(25), theme.dp(25), Gravity.CENTER))
                    addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, theme.dp(16), Gravity.TOP or Gravity.END).apply {
                        marginEnd = -theme.dp(5)
                        topMargin = -theme.dp(2)
                    })
                }, LinearLayout.LayoutParams(theme.dp(36), theme.dp(28)))
                addView(label, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = theme.dp(2)
                })
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            setOnClickListener {
                UiMotion.haptic(this)
                select(index)
                onSelect(index)
            }
            buttons += this
        }
    }

    fun select(index: Int, animate: Boolean = true) {
        require(index in labels.indices)
        selectedIndex = index
        buttons.forEachIndexed { position, button ->
            val selected = position == index
            button.isSelected = selected
            icons[position].setImageDrawable(TabIcon(position, selected,
                if (selected) theme.primary else theme.secondary, theme.onPrimary))
            titles[position].setTextColor(if (selected) theme.primary else theme.secondary)
            titles[position].typeface = Typeface.create(if (selected) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            updateDescription(position)
        }
        if (animate) bounce(index)
        refreshBackdrop()
    }

    fun setBadges(downloads: Int, conversions: Int) {
        listOf(downloads, conversions).forEachIndexed { index, count ->
            val safeCount = count.coerceAtLeast(0)
            if (badgeCounts[index] != safeCount) {
                badgeCounts[index] = safeCount
                badges[index].text = if (safeCount > 99) "99+" else safeCount.toString()
                badges[index].visibility = if (safeCount == 0) GONE else VISIBLE
                updateDescription(index)
            }
        }
    }

    fun setBottomInset(pixels: Int) {
        val safeInset = pixels.coerceAtLeast(0)
        if (bottomInset != safeInset) {
            bottomInset = safeInset
            requestLayout()
            refreshBackdrop()
        }
    }

    fun setBackdrop(source: View) {
        require(source !== this) { "The backdrop must not include the tab bar" }
        stopObserving()
        backdrop = source
        if (isAttachedToWindow) startObserving()
        refreshBackdrop()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(theme.dp(56) + bottomInset, MeasureSpec.EXACTLY))
    }

    private fun updateDescription(index: Int) {
        buttons[index].contentDescription = labels[index] +
            (if (badgeCounts[index] > 0) "，${badgeCounts[index]} 个进行中的任务" else "")
    }

    private fun iconSpring(view: View, property: DynamicAnimation.ViewProperty) = SpringAnimation(view, property).apply {
        spring = SpringForce(1f).apply {
            dampingRatio = UiTheme.SPRING_DAMPING
            stiffness = UiTheme.SPRING_STIFFNESS
        }
        setMinValue(0.9f)
        setMaxValue(1.15f)
    }

    private fun bounce(index: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - bounceTimes[index] < 60) return
        bounceTimes[index] = now
        val (x, y) = iconSprings[index]
        x.cancel()
        y.cancel()
        icons[index].scaleX = 1f
        icons[index].scaleY = 1f
        val enabled = if (Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled() else
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        if (enabled) {
            // Initial velocity gives a 1.15 peak, then the spring returns both axes to 1.
            x.setStartVelocity(6f).start()
            y.setStartVelocity(6f).start()
        }
    }

    private fun refreshBackdrop() {
        dirty = true
        if (isAttachedToWindow && blurAvailable && isShown) scheduleCapture()
    }

    private fun scheduleCapture() {
        if (pendingCapture) return
        pendingCapture = true
        postDelayed(capture, (lastCapture + 64L - SystemClock.uptimeMillis()).coerceAtLeast(0L))
    }

    private fun captureBackdrop() {
        val source = backdrop ?: return
        if (!blurAvailable || !isShown || !source.isAttachedToWindow || width == 0 || height == 0) return
        lastCapture = SystemClock.uptimeMillis()
        val sourceLocation = IntArray(2)
        val barLocation = IntArray(2)
        source.getLocationInWindow(sourceLocation)
        getLocationInWindow(barLocation)
        val captureWidth = max(1, ceil(width / 4.0).toInt())
        val captureHeight = max(1, ceil(height / 4.0).toInt())
        try {
            var target = bitmap
            if (target == null || target.width != captureWidth || target.height != captureHeight) {
                releaseBitmap()
                target = Bitmap.createBitmap(captureWidth, captureHeight, Bitmap.Config.ARGB_8888)
                bitmap = target
            }
            target.eraseColor(theme.background)
            val canvas = Canvas(target)
            canvas.scale(captureWidth.toFloat() / width, captureHeight.toFloat() / height)
            canvas.translate((sourceLocation[0] - barLocation[0]).toFloat(),
                (sourceLocation[1] - barLocation[1]).toFloat())
            source.draw(canvas)
            snapshot.setImageBitmap(target)
            snapshot.invalidate()
            lastSignature = visualSignature(source)
            dirty = false
        } catch (_: RuntimeException) {
            // A future hardware-only media view may not permit software snapshots.
            blurAvailable = false
            releaseBitmap()
            updateTint()
        }
    }

    private fun visualSignature(view: View): Long {
        var value = view.visibility.toLong()
        value = 31 * value + view.scrollX
        value = 31 * value + view.scrollY
        value = 31 * value + view.translationX.toBits()
        value = 31 * value + view.translationY.toBits()
        value = 31 * value + view.alpha.toBits()
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val child = view.getChildAt(index)
                if (child.visibility == VISIBLE) value = 31 * value + visualSignature(child)
            }
        }
        return value
    }

    private fun updateTint() {
        tint.setBackgroundColor(alpha(theme.surface, if (blurAvailable) 0.72f else 0.90f))
    }

    private fun startObserving() {
        if (!blurAvailable || observer != null) return
        observer = backdrop?.viewTreeObserver?.also {
            it.addOnScrollChangedListener(scrollListener)
            it.addOnGlobalLayoutListener(layoutListener)
            it.addOnPreDrawListener(drawListener)
        }
    }

    private fun stopObserving() {
        observer?.takeIf { it.isAlive }?.let {
            it.removeOnScrollChangedListener(scrollListener)
            it.removeOnGlobalLayoutListener(layoutListener)
            it.removeOnPreDrawListener(drawListener)
        }
        observer = null
        removeCallbacks(capture)
        pendingCapture = false
    }

    private fun releaseBitmap() {
        snapshot.setImageDrawable(null)
        bitmap?.recycle()
        bitmap = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startObserving()
        refreshBackdrop()
    }

    override fun onDetachedFromWindow() {
        stopObserving()
        iconSprings.forEach { (x, y) -> x.cancel(); y.cancel() }
        icons.forEach { it.scaleX = 1f; it.scaleY = 1f }
        releaseBitmap()
        super.onDetachedFromWindow()
    }

    private fun alpha(color: Int, opacity: Float) = (color and 0x00ffffff) or ((255 * opacity).toInt() shl 24)

    private class TabIcon(
        private val index: Int,
        private val selected: Boolean,
        private val color: Int,
        private val onSelected: Int,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 1.7f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        override fun draw(canvas: Canvas) {
            val checkpoint = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
            paint.color = color
            paint.style = if (selected) Paint.Style.FILL else Paint.Style.STROKE
            when (index) {
                0 -> {
                    canvas.drawCircle(12f, 12f, 10f, paint)
                    paint.color = if (selected) onSelected else color
                    paint.style = Paint.Style.STROKE
                    canvas.drawLine(12f, 6f, 12f, 15.5f, paint)
                    path.reset()
                    path.moveTo(8.5f, 12f)
                    path.lineTo(12f, 15.5f)
                    path.lineTo(15.5f, 12f)
                    canvas.drawPath(path, paint)
                    canvas.drawLine(8f, 18.5f, 16f, 18.5f, paint)
                }
                1 -> {
                    star(canvas, 11f, 13f, 8.5f)
                    star(canvas, 20f, 4.5f, 3f)
                    star(canvas, 3.5f, 3.5f, 2f)
                }
                2 -> {
                    canvas.drawRoundRect(RectF(2f, 6f, 22f, 21f), 3f, 3f, paint)
                    paint.style = Paint.Style.STROKE
                    canvas.drawLine(6f, 2.5f, 18f, 2.5f, paint)
                    paint.color = if (selected) onSelected else color
                    paint.style = Paint.Style.FILL
                    path.reset()
                    path.moveTo(10f, 10f)
                    path.lineTo(16f, 13.5f)
                    path.lineTo(10f, 17f)
                    path.close()
                    canvas.drawPath(path, paint)
                }
            }
            canvas.restoreToCount(checkpoint)
        }

        private fun star(canvas: Canvas, x: Float, y: Float, radius: Float) {
            val waist = radius * 0.28f
            path.reset()
            path.moveTo(x, y - radius)
            path.lineTo(x + waist, y - waist)
            path.lineTo(x + radius, y)
            path.lineTo(x + waist, y + waist)
            path.lineTo(x, y + radius)
            path.lineTo(x - waist, y + waist)
            path.lineTo(x - radius, y)
            path.lineTo(x - waist, y - waist)
            path.close()
            canvas.drawPath(path, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
