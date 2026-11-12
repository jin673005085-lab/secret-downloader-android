package cn.secret.downloader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** All movement is canvas-only; a progress tick never changes its row's layout. */
class JellyProgressBar(context: Context, private val theme: UiTheme, private val thicknessDp: Int = 6) : View(context) {
    private val value = ProgressValue()
    private var status = TaskStatus.RUNNING
    private var fill = 0f
    private var bulge = 0f
    private var capsule = 0f
    private var capsuleTarget = 1f
    private var sweep = 0f
    private var shake = 0f
    private var completionScale = 1f
    private var completedGreen = false
    private var completionShown = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()
    private val fillPath = Path()
    private val clipPath = Path()
    private val highlightMatrix = Matrix()
    private val highlight = LinearGradient(-1f, 0f, 1f, 0f,
        intArrayOf(Color.TRANSPARENT, 0x70ffffff, Color.TRANSPARENT), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    private val progressSpring = spring(0f, 1f, 400f) { next ->
        fill = max(fill, next).coerceAtMost(value.value ?: 1f)
        invalidate()
    }.apply {
        setMinValue(0f); setMaxValue(1f)
        addEndListener { _, canceled, _, _ -> if (!canceled && status == TaskStatus.COMPLETED) finishCompletion() }
    }
    private val bulgeSpring = spring(0f, 0.55f, 350f) { bulge = it; invalidate() }
    private val completionSpring = spring(1f, 0.45f, 400f) { completionScale = it; invalidate() }.apply {
        setMinValue(0.94f); setMaxValue(1.08f)
    }
    private val shakeSpring = spring(0f, 0.25f, 600f) { shake = it; invalidate() }.apply {
        minimumVisibleChange = 0.05f
        setMinValue(-theme.dp(3).toFloat()); setMaxValue(theme.dp(3).toFloat())
    }
    private val capsuleSpring = spring(0f, 0.75f, 30f) { capsule = it; invalidate() }.apply {
        setMinValue(0f); setMaxValue(1f)
        addEndListener { _, canceled, _, _ ->
            if (!canceled && motion.allowed && isWorking() && value.value == null) {
                capsuleTarget = 1f - capsuleTarget
                animateToFinalPosition(capsuleTarget)
            }
        }
    }
    private val sweepAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { sweep = it.animatedValue as Float; invalidate() }
    }
    private val motion = ProgressMotion(this) { updateMotion(it) }

    init {
        require(thicknessDp > 0)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun bind(task: Task) = setProgress(task.progress, task.status, "${task.id}:${task.attempt}")

    fun setProgress(progress: Float?, status: TaskStatus = TaskStatus.RUNNING, resetKey: String = "storage") {
        val previousStatus = this.status
        val previousTarget = value.value ?: fill
        val reset = value.update(resetKey, progress, status == TaskStatus.COMPLETED)
        this.status = status
        if (reset) {
            stopAnimations()
            fill = value.value ?: 0f
            capsule = 0f
            capsuleTarget = 1f
            completedGreen = status == TaskStatus.COMPLETED
            completionShown = completedGreen
        }
        motion.refresh()
        if (!reset && motion.allowed) {
            if ((value.value ?: 0f) > previousTarget && (isWorking() || status == TaskStatus.COMPLETED)) {
                bulgeSpring.cancel()
                bulgeSpring.setStartValue(1f).animateToFinalPosition(0f)
            }
            if (status == TaskStatus.FAILED && previousStatus != TaskStatus.FAILED) {
                shakeSpring.cancel()
                shakeSpring.setStartValue(0f).setStartVelocity(theme.dp(70).toFloat()).animateToFinalPosition(0f)
            }
        }
        contentDescription = progressStatusLabel(status) + (value.value?.let { "，${(it * 100).roundToInt()}%" } ?: "，进度未知")
        updateMotion(motion.allowed)
        invalidate()
    }

    private fun isWorking() = status == TaskStatus.QUEUED || status == TaskStatus.PARSING || status == TaskStatus.RUNNING

    private fun updateMotion(allowed: Boolean) {
        if (!allowed) {
            stopAnimations()
            value.value?.let { fill = it }
            if (status == TaskStatus.COMPLETED) {
                completedGreen = true
                completionShown = true
            }
            invalidate()
            return
        }
        val target = value.value
        if (target != null && (isWorking() || status == TaskStatus.COMPLETED) && target > fill + 0.0001f) {
            if (!progressSpring.isRunning) progressSpring.setStartValue(fill)
            progressSpring.animateToFinalPosition(target)
        } else {
            progressSpring.cancel()
            if (target != null) fill = target
            if (status == TaskStatus.COMPLETED) finishCompletion()
        }
        if (isWorking()) {
            if (!sweepAnimator.isStarted) sweepAnimator.start()
            if (target == null) {
                if (!capsuleSpring.isRunning) capsuleSpring.setStartValue(capsule).animateToFinalPosition(capsuleTarget)
            } else capsuleSpring.cancel()
        } else {
            sweepAnimator.cancel()
            capsuleSpring.cancel()
        }
    }

    private fun finishCompletion() {
        fill = 1f
        completedGreen = true
        if (!completionShown && motion.allowed) {
            completionShown = true
            completionSpring.setStartValue(1.08f).animateToFinalPosition(1f)
        }
        invalidate()
    }

    private fun stopAnimations() {
        progressSpring.cancel()
        bulgeSpring.cancel()
        completionSpring.cancel()
        shakeSpring.cancel()
        capsuleSpring.cancel()
        sweepAnimator.cancel()
        bulge = 0f
        shake = 0f
        completionScale = 1f
    }

    private fun spring(start: Float, damping: Float, stiffness: Float, update: (Float) -> Unit) =
        SpringAnimation(FloatValueHolder(start)).apply {
            spring = SpringForce(start).apply { dampingRatio = damping; this.stiffness = stiffness }
            minimumVisibleChange = 0.001f
            addUpdateListener { _, next, _ -> update(next) }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(theme.dp(160) + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(theme.dp(thicknessDp + 4) + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val thickness = min(theme.dp(thicknessDp).toFloat(), (height - paddingTop - paddingBottom) / 1.12f).coerceAtLeast(0f)
        val left = paddingLeft + theme.dp(3).toFloat()
        val right = width - paddingRight - theme.dp(3).toFloat()
        if (right <= left || thickness == 0f) return
        val middle = paddingTop + (height - paddingTop - paddingBottom) / 2f
        val half = thickness * completionScale / 2f
        track.set(left, middle - half, right, middle + half)
        val saved = canvas.save()
        canvas.translate(shake, 0f)
        paint.shader = null
        paint.color = theme.muted
        canvas.drawRoundRect(track, half, half, paint)
        val color = when (status) {
            TaskStatus.PAUSED, TaskStatus.INTERRUPTED -> theme.warning
            TaskStatus.FAILED -> theme.error
            TaskStatus.CANCELED -> theme.secondary
            else -> if (completedGreen) theme.success else theme.primary
        }
        var fillLeft = left
        val fillRight: Float
        if (value.value == null) {
            val capsuleWidth = (right - left) * 0.26f
            fillLeft += (right - left - capsuleWidth) * capsule
            fillRight = fillLeft + capsuleWidth
        } else fillRight = left + (right - left) * fill.coerceIn(0f, 1f)
        if (fillRight <= fillLeft) {
            canvas.restoreToCount(saved)
            return
        }
        fillPath.reset()
        fillPath.addRoundRect(fillLeft, track.top, fillRight, track.bottom, half, half, Path.Direction.CW)
        val expansion = thickness * 0.04f * bulge.coerceIn(0f, 1f)
        if (expansion > 0f && value.value != null) {
            fillPath.addOval(max(fillLeft, fillRight - thickness), track.top - expansion,
                fillRight, track.bottom + expansion, Path.Direction.CW)
        }
        clipPath.reset()
        clipPath.addRoundRect(left, track.top - expansion, right, track.bottom + expansion,
            half + expansion, half + expansion, Path.Direction.CW)
        canvas.clipPath(clipPath)
        paint.color = color
        canvas.drawPath(fillPath, paint)
        if (isWorking() && motion.allowed) {
            canvas.clipPath(fillPath)
            val band = (right - left) * 0.32f
            highlightMatrix.setScale(band / 2f, 1f)
            highlightMatrix.postTranslate(left - band + ((right - left) + 2f * band) * sweep, 0f)
            highlight.setLocalMatrix(highlightMatrix)
            paint.shader = highlight
            canvas.drawRect(left, track.top - expansion, right, track.bottom + expansion, paint)
            paint.shader = null
        }
        canvas.restoreToCount(saved)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.ProgressBar"
        value.value?.let { info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, 1f, it) }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); motion.attach() }
    override fun onDetachedFromWindow() { motion.detach(); stopAnimations(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isAttachedToWindow) motion.refresh()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttachedToWindow) motion.refresh()
    }
}
