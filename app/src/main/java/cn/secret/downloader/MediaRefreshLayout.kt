package cn.secret.downloader

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

/** A bounded pull gesture; it never intercepts a scrolling list or starts parallel scans. */
internal class MediaRefreshLayout(context: Context, private val theme: UiTheme,
                                  private val repository: MediaRepository, private val enabled: Boolean) : FrameLayout(context) {
    private var list: View? = null
    private var startY = 0f
    private var startX = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var pulling = false
    private var eligible = false
    private var firstScan = false
    private var refreshing = false
    private var distance = 0f
    private val indicator = TextView(context).apply {
        gravity = Gravity.CENTER; textSize = 13f; setTextColor(theme.primaryText)
        background = theme.shape(theme.surface, 14); elevation = theme.dp(4).toFloat()
        setPadding(theme.dp(16), theme.dp(8), theme.dp(16), theme.dp(8)); visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun content(grid: View, empty: View) {
        list = grid; addView(grid, LayoutParams(-1, -1)); addView(empty, LayoutParams(-1, -1))
        addView(indicator, LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = theme.dp(8) })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scanFirstVisible()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) scanFirstVisible()
    }

    private fun scanFirstVisible() {
        if (enabled && !firstScan && isAttachedToWindow && isShown) { firstScan = true; refresh(false) }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!enabled || refreshing) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startY = event.y; startX = event.x; eligible = list?.canScrollVertically(-1) != true; pulling = false; distance = 0f }
            MotionEvent.ACTION_MOVE -> if (eligible && event.y - startY > touchSlop && event.y - startY > kotlin.math.abs(event.x - startX) && list?.canScrollVertically(-1) != true) {
                pulling = true; parent?.requestDisallowInterceptTouchEvent(true); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> eligible = false
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!pulling) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                distance = (event.y - startY).coerceAtLeast(0f)
                indicator.text = if (distance >= theme.dp(78)) "松开刷新" else "下拉刷新"
                indicator.visibility = VISIBLE
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pulling = false; eligible = false; parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP && distance >= theme.dp(78)) refresh(true)
                else indicator.visibility = GONE
                distance = 0f
            }
        }
        return true
    }

    private fun refresh(announce: Boolean) {
        if (refreshing) return
        refreshing = true; indicator.text = "正在扫描…"; indicator.visibility = VISIBLE
        MediaScanner.scan(context, repository) { result ->
            refreshing = false; indicator.visibility = GONE
            if (isAttachedToWindow && isShown && (announce || result.contains("目录选择器"))) Toast.makeText(context, result, Toast.LENGTH_LONG).show()
        }
    }
}
