package cn.secret.downloader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** Fixed digit slots roll on the canvas without measuring the surrounding task page again. */
class RollingPercentView(context: Context, private val theme: UiTheme) : View(context) {
    private val value = ProgressValue()
    private var status = TaskStatus.RUNNING
    private var shown = 0f
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(UiTheme.NUMBER)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val suffixPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(UiTheme.LABEL)
        color = theme.secondary
    }
    private val unknownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(UiTheme.BODY)
        color = theme.secondary
    }
    private val digitWidth = ('0'..'9').maxOf { numberPaint.measureText(it.toString()) }
    private val digitHeight = numberPaint.fontSpacing
    private val numericWidth = digitWidth * 3f
    private val contentWidth = max(numericWidth + theme.dp(5) + suffixPaint.measureText("%"),
        unknownPaint.measureText("处理中…"))
    private val rolling = SpringAnimation(FloatValueHolder(0f)).apply {
        spring = SpringForce(0f).apply { dampingRatio = 1f; stiffness = 350f }
        minimumVisibleChange = 0.01f
        setMinValue(0f); setMaxValue(100f)
        addUpdateListener { _, next, _ ->
            shown = max(shown, next).coerceAtMost(targetPercent() ?: 100f)
            invalidate()
        }
        addEndListener { _, canceled, _, _ ->
            if (!canceled) { targetPercent()?.let { shown = it }; invalidate() }
        }
    }
    private val motion = ProgressMotion(this) { updateMotion(it) }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    fun bind(task: Task) {
        val reset = value.update("${task.id}:${task.attempt}", task.progress, task.status == TaskStatus.COMPLETED)
        status = task.status
        if (reset) {
            rolling.cancel()
            shown = targetPercent() ?: 0f
        }
        motion.refresh()
        updateMotion(motion.allowed)
        contentDescription = progressStatusLabel(status) + (value.value?.let { "，${(it * 100).roundToInt()}%" } ?: "，进度未知")
        invalidate()
    }

    private fun updateMotion(allowed: Boolean) {
        val target = targetPercent()
        val moving = status == TaskStatus.QUEUED || status == TaskStatus.PARSING ||
            status == TaskStatus.RUNNING || status == TaskStatus.COMPLETED
        if (allowed && moving && target != null && target > shown + 0.001f) {
            if (!rolling.isRunning) rolling.setStartValue(shown)
            rolling.animateToFinalPosition(target)
        } else {
            rolling.cancel()
            if (target != null) shown = target
            invalidate()
        }
    }

    private fun targetPercent() = value.value?.let { (it * 100f).roundToInt().toFloat() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(ceil(contentWidth).toInt() + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(ceil(digitHeight).toInt() + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val middle = paddingTop + (height - paddingTop - paddingBottom) / 2f
        if (value.value == null) {
            val metrics = unknownPaint.fontMetrics
            canvas.drawText("处理中…", paddingLeft.toFloat(), middle - (metrics.ascent + metrics.descent) / 2f, unknownPaint)
            return
        }
        numberPaint.color = when (status) {
            TaskStatus.COMPLETED -> theme.success
            TaskStatus.PAUSED, TaskStatus.INTERRUPTED -> theme.warning
            TaskStatus.FAILED -> theme.error
            else -> theme.text
        }
        val metrics = numberPaint.fontMetrics
        val baseline = middle - (metrics.ascent + metrics.descent) / 2f
        val current = floor(shown.coerceIn(0f, 100f)).toInt()
        val next = (current + 1).coerceAtMost(100)
        val fraction = shown - current
        val currentDigits = current.toString().padStart(3, ' ')
        val nextDigits = next.toString().padStart(3, ' ')
        val saved = canvas.save()
        canvas.clipRect(paddingLeft.toFloat(), middle - digitHeight / 2f,
            paddingLeft + numericWidth, middle + digitHeight / 2f)
        for (slot in 0..2) {
            val oldDigit = currentDigits[slot].toString()
            val newDigit = nextDigits[slot].toString()
            val slotLeft = paddingLeft + slot * digitWidth
            val oldX = slotLeft + (digitWidth - numberPaint.measureText(oldDigit)) / 2f
            if (oldDigit == newDigit) {
                canvas.drawText(oldDigit, oldX, baseline, numberPaint)
            } else {
                canvas.drawText(oldDigit, oldX, baseline - fraction * digitHeight, numberPaint)
                val newX = slotLeft + (digitWidth - numberPaint.measureText(newDigit)) / 2f
                canvas.drawText(newDigit, newX, baseline + (1f - fraction) * digitHeight, numberPaint)
            }
        }
        canvas.restoreToCount(saved)
        canvas.drawText("%", paddingLeft + numericWidth + theme.dp(5), baseline, suffixPaint)
    }

    private fun sp(size: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, resources.displayMetrics)
    override fun onAttachedToWindow() { super.onAttachedToWindow(); motion.attach() }
    override fun onDetachedFromWindow() { motion.detach(); rolling.cancel(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isAttachedToWindow) motion.refresh()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttachedToWindow) motion.refresh()
    }
}
