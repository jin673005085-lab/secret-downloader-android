package cn.secret.downloader

import android.animation.ValueAnimator
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewTreeObserver

/** A scrolled-off row stays attached, so visibility must also be checked before drawing. */
internal class ProgressMotion(private val view: View, private val changed: (Boolean) -> Unit) {
    private val visibleBounds = Rect()
    private var observer: ViewTreeObserver? = null
    var allowed = false
        private set
    private val listener = ViewTreeObserver.OnPreDrawListener { refresh(); true }

    fun attach() {
        observer = view.viewTreeObserver.also { it.addOnPreDrawListener(listener) }
        refresh()
    }

    fun refresh() {
        val enabled = if (Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled() else
            Settings.Global.getFloat(view.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        val next = enabled && view.isAttachedToWindow && view.windowVisibility == View.VISIBLE &&
            view.isShown && view.getGlobalVisibleRect(visibleBounds)
        if (allowed != next) {
            allowed = next
            changed(next)
        }
    }

    fun detach() {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        observer = null
        if (allowed) {
            allowed = false
            changed(false)
        }
    }
}
