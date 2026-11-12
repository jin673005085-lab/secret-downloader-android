package cn.secret.downloader

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import kotlin.math.roundToInt

/** Shared visual tokens; colors resolve through values / values-night. */
class UiTheme(private val context: Context) {
    val background = context.getColor(R.color.ui_background)
    val surface = context.getColor(R.color.ui_surface)
    val muted = context.getColor(R.color.ui_surface_muted)
    val text = context.getColor(R.color.ui_text)
    val secondary = context.getColor(R.color.ui_text_secondary)
    val primary = context.getColor(R.color.ui_primary)
    val primaryText = context.getColor(R.color.ui_primary_text)
    val onPrimary = context.getColor(R.color.ui_on_primary)
    val primarySoft = context.getColor(R.color.ui_primary_soft)
    val success = context.getColor(R.color.ui_success)
    val successSoft = context.getColor(R.color.ui_success_soft)
    val warning = context.getColor(R.color.ui_warning)
    val warningSoft = context.getColor(R.color.ui_warning_soft)
    val error = context.getColor(R.color.ui_error)
    val errorSoft = context.getColor(R.color.ui_error_soft)

    fun dp(value: Int) = (context.resources.displayMetrics.density * value).roundToInt()
    fun shape(color: Int, radius: Int = CARD_RADIUS) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    fun control(color: Int, radius: Int = CONTROL_RADIUS) = StateListDrawable().apply {
        fun highlighted() = shape(color, radius).apply {
            setStroke(dp(FOCUS_STROKE), if (color == primary) onPrimary else primary)
        }
        addState(intArrayOf(android.R.attr.state_focused), highlighted())
        addState(intArrayOf(android.R.attr.state_pressed), highlighted())
        addState(intArrayOf(), shape(color, radius))
    }

    companion object {
        const val CARD_RADIUS = 28
        const val CONTROL_RADIUS = 18
        const val PILL_RADIUS = 100
        const val PAGE_PADDING = 20
        const val CARD_PADDING = 20
        const val CARD_GAP = 14
        const val SMALL_GAP = 8
        const val TOUCH_TARGET = 48
        const val PRIMARY_ACTION = 72
        const val CONTROL_PADDING = 16
        const val DIALOG_PADDING = 24
        const val PILL_PADDING_X = 12
        const val PILL_PADDING_Y = 6
        const val PROGRESS_HEIGHT = 6
        const val LINE_GAP = 4
        const val FOCUS_STROKE = 2
        const val CARD_ELEVATION = 1
        const val ACTION_ELEVATION = 2
        const val TITLE = 30f
        const val HEADING = 22f
        const val BODY = 15f
        const val LABEL = 13f
        const val NUMBER = 38f
        const val SPRING_DAMPING = 0.55f
        const val SPRING_STIFFNESS = 400f
        const val PRESS_X = 0.96f
        const val PRESS_Y = 0.94f
        const val ENTRY_OFFSET = 16
        const val ENTRY_STAGGER_MS = 50L
    }
}
