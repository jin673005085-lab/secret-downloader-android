package cn.secret.downloader

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

object UiMotion {
    private fun enabled(view: View): Boolean = if (Build.VERSION.SDK_INT >= 26) {
        ValueAnimator.areAnimatorsEnabled()
    } else {
        Settings.Global.getFloat(view.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }

    private fun spring(view: View, property: DynamicAnimation.ViewProperty, target: Float) =
        SpringAnimation(view, property).apply {
            spring = SpringForce(target).apply {
                dampingRatio = UiTheme.SPRING_DAMPING
                stiffness = UiTheme.SPRING_STIFFNESS
            }
        }

    @SuppressLint("ClickableViewAccessibility") // Returning false leaves performClick to the native widget.
    fun press(view: View) {
        val x = spring(view, DynamicAnimation.SCALE_X, 1f)
        val y = spring(view, DynamicAnimation.SCALE_Y, 1f)
        // Let the native widget keep click, scroll cancellation, keyboard and TalkBack semantics.
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val down = event.actionMasked == MotionEvent.ACTION_DOWN && view.isEnabled
                    if (enabled(view)) {
                        x.animateToFinalPosition(if (down) UiTheme.PRESS_X else 1f)
                        y.animateToFinalPosition(if (down) UiTheme.PRESS_Y else 1f)
                    } else {
                        x.cancel(); y.cancel()
                        view.scaleX = 1f; view.scaleY = 1f
                    }
                }
            }
            false
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                x.cancel(); y.cancel()
                v.scaleX = 1f; v.scaleY = 1f
            }
        })
    }

    fun haptic(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun enter(view: View, index: Int) {
        if (!enabled(view)) return
        val translation = spring(view, DynamicAnimation.TRANSLATION_Y, 0f)
        val opacity = spring(view, DynamicAnimation.ALPHA, 1f).apply {
            setMinValue(0f); setMaxValue(1f)
        }
        view.translationY = UiTheme(view.context).dp(UiTheme.ENTRY_OFFSET).toFloat()
        view.alpha = 0f
        val start = Runnable {
            if (view.isAttachedToWindow && enabled(view)) {
                translation.start(); opacity.start()
            } else {
                view.translationY = 0f; view.alpha = 1f
            }
        }
        view.postDelayed(start, index * UiTheme.ENTRY_STAGGER_MS)
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                view.removeCallbacks(start)
                translation.cancel(); opacity.cancel()
                view.translationY = 0f; view.alpha = 1f
                view.removeOnAttachStateChangeListener(this)
            }
        })
    }
}
