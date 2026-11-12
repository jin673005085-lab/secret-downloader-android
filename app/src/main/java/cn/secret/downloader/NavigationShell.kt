package cn.secret.downloader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.SparseArray
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs

internal fun sharedElementUniformScale(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Float? {
    if (minOf(sourceWidth, sourceHeight, targetWidth, targetHeight) <= 0) return null
    val x = targetWidth.toFloat() / sourceWidth
    val y = targetHeight.toFloat() / sourceHeight
    return minOf(x, y).takeIf { abs(x - y) <= it * .01f }
}

internal enum class ScreenRoute(val title: String) {
    TASK_CENTER("任务中心"), TASK_DETAIL("任务详情"),
    CREATE_GIF("视频转 GIF"), CREATE_LIVE("视频转实况"),
    VIEW_GIF("GIF 作品"), VIEW_LIVE("实况作品"),
    PLAY_VIDEO("视频播放"), SETTINGS("设置")
}

/** Keeps the three tab views attached so page state survives every secondary route. */
internal class NavigationShell(
    context: Context,
    private val theme: UiTheme,
    private val tabs: List<View>,
    private val buildRoute: (ScreenRoute, String?) -> View
) : FrameLayout(context) {
    private val tabHost = FrameLayout(context)
    private val secondary = FrameLayout(context).apply { tag = "secondary_pages"; visibility = GONE }
    private val statusSolid = View(context).apply { setBackgroundColor(theme.background) }
    private val statusMask = FrameLayout(context).apply {
        tag = "status_bar_mask"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(statusSolid, LayoutParams(LayoutParams.MATCH_PARENT, 0, Gravity.TOP))
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(theme.background, theme.background and 0x00ffffff))
        }, LayoutParams(LayoutParams.MATCH_PARENT, theme.dp(12), Gravity.BOTTOM))
    }
    private val stack = mutableListOf<Pair<ScreenRoute, View>>()
    private val pendingScroll = mutableMapOf<ScrollView, Int>()
    private val taskIds = mutableMapOf<View, String?>()
    private val animations = mutableListOf<SpringAnimation>()
    private data class SharedElement(val image: ImageView, val bitmap: Bitmap, val target: View)
    private val sharedElements = mutableListOf<SharedElement>()
    private data class PendingTransition(val observer: ViewTreeObserver, val listener: ViewTreeObserver.OnPreDrawListener,
                                         val bitmaps: List<Bitmap>)
    private val pendingTransitions = mutableListOf<PendingTransition>()
    private val bar = BottomTabBar(context, theme, ::selectTab)
    private var selected = 0
    private var topInset = 0
    private var bottomInset = 0
    private var leftInset = 0
    private var rightInset = 0
    private var imeInset = 0

    init {
        setBackgroundColor(theme.background)
        tabs.forEachIndexed { index, page ->
            tabHost.addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            page.visibility = if (index == selected) VISIBLE else GONE
        }
        addView(tabHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        addView(secondary, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(statusMask, LayoutParams(LayoutParams.MATCH_PARENT, 0, Gravity.TOP))
        bar.setBackdrop(tabHost)
        bar.select(selected, false)
        setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                topInset = bars.top; bottomInset = bars.bottom
                leftInset = bars.left; rightInset = bars.right
                imeInset = insets.getInsets(WindowInsets.Type.ime()).bottom
            } else {
                @Suppress("DEPRECATION")
                run {
                    topInset = insets.systemWindowInsetTop
                    bottomInset = insets.stableInsetBottom
                    leftInset = insets.systemWindowInsetLeft; rightInset = insets.systemWindowInsetRight
                    imeInset = insets.systemWindowInsetBottom
                }
            }
            applyInsets()
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
    }

    private fun applyInsets() {
        bar.setBottomInset(bottomInset)
        val keyboard = imeInset > bottomInset
        bar.visibility = if (stack.isEmpty() && !keyboard) VISIBLE else GONE
        tabs.forEach { page ->
            if (page is android.view.ViewGroup) page.clipToPadding = false
            page.setPadding(leftInset, topInset, rightInset,
                maxOf(imeInset, theme.dp(56) + bottomInset))
        }
        stack.forEach { (_, page) ->
            // Secondary content scrolls below the status mask and above the navigation gesture area.
            if (page is android.view.ViewGroup) page.clipToPadding = false
            // A fullscreen player owns every pixel; any inset padding would frame it with the page colour.
            val immersive = page === activePlaybackPage() && activePlaybackPage()?.isImmersive == true
            if (immersive) page.setPadding(0, 0, 0, 0)
            else page.setPadding(leftInset, topInset, rightInset, maxOf(bottomInset, imeInset))
        }
        statusMask.layoutParams = (statusMask.layoutParams as LayoutParams).apply { height = topInset + theme.dp(12) }
        statusSolid.layoutParams = (statusSolid.layoutParams as LayoutParams).apply { height = topInset }
        val work = activeWorkViewer() != null
        val maskColor = if (work) android.graphics.Color.rgb(17, 17, 20) else theme.background
        statusSolid.setBackgroundColor(maskColor)
        statusMask.getChildAt(1).background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(maskColor, maskColor and 0x00ffffff))
        @Suppress("DEPRECATION")
        (context as? android.app.Activity)?.window?.decorView?.let { decor ->
            val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
            decor.systemUiVisibility = if (work || dark) decor.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                else decor.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        statusMask.visibility = if (activePlaybackPage()?.isImmersive == true) GONE else VISIBLE
    }

    fun updateBadges(downloads: Int, conversions: Int) = bar.setBadges(downloads, conversions)

    private fun stopAnimations() {
        animations.forEach { it.cancel() }
        animations.clear()
        pendingTransitions.forEach {
            if (it.observer.isAlive) it.observer.removeOnPreDrawListener(it.listener)
            it.bitmaps.forEach(Bitmap::recycle)
        }
        pendingTransitions.clear()
        finishSharedElements()
        tabs.forEachIndexed { index, view ->
            view.alpha = 1f; view.translationX = 0f
            view.visibility = if (index == selected) VISIBLE else GONE
        }
        for (index in secondary.childCount - 1 downTo 0) {
            val view = secondary.getChildAt(index)
            if (stack.none { it.second === view }) {
                pendingScroll.remove(view)
                taskIds.remove(view)
                secondary.removeView(view)
            }
        }
        stack.forEachIndexed { index, (_, view) ->
            view.alpha = 1f; view.scaleX = 1f; view.scaleY = 1f
            view.visibility = if (index == stack.lastIndex) VISIBLE else GONE
        }
        secondary.visibility = if (stack.isEmpty()) GONE else VISIBLE
        tabHost.visibility = if (stack.isEmpty()) VISIBLE else INVISIBLE
    }

    private fun spring(view: View, property: DynamicAnimation.ViewProperty, end: Float, alpha: Boolean = false,
                       done: (() -> Unit)? = null) {
        val animation = SpringAnimation(view, property).apply {
            spring = SpringForce(end).apply {
                dampingRatio = if (alpha) SpringForce.DAMPING_RATIO_NO_BOUNCY else .8f
                stiffness = 380f
            }
            if (alpha) { setMinValue(0f); setMaxValue(1f) }
            // One spring drives both axes so snapshots and pages cannot stretch between frames.
            if (property === DynamicAnimation.SCALE_X) {
                view.scaleY = view.scaleX
                addUpdateListener { _, scale, _ -> view.scaleY = scale }
            }
            addEndListener { _, canceled, _, _ -> if (!canceled) done?.invoke() }
        }
        animations += animation
        animation.start()
    }

    private fun motionEnabled() = if (Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled() else
        android.provider.Settings.Global.getFloat(context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

    private fun selectTab(index: Int) {
        if (stack.isNotEmpty()) return
        if (index == selected) {
            scrollToTop(tabs[selected])
            return
        }
        stopAnimations()
        val previous = tabs[selected]
        val incoming = tabs[index]
        val direction = if (index > selected) 1 else -1
        tabs.forEach { it.visibility = GONE }
        previous.visibility = VISIBLE
        incoming.visibility = VISIBLE
        incoming.bringToFront()
        selected = index
        bar.select(index)
        if (motionEnabled()) {
            incoming.translationX = theme.dp(28).toFloat() * direction
            incoming.alpha = 0f
            spring(previous, DynamicAnimation.TRANSLATION_X, -theme.dp(20).toFloat() * direction)
            spring(previous, DynamicAnimation.ALPHA, 0f, true) {
                previous.visibility = GONE; previous.alpha = 1f; previous.translationX = 0f
            }
            spring(incoming, DynamicAnimation.TRANSLATION_X, 0f)
            spring(incoming, DynamicAnimation.ALPHA, 1f, true)
        } else previous.visibility = GONE
    }

    fun open(route: ScreenRoute, animate: Boolean = true, taskId: String? = null,
             shared: List<Pair<View, String>> = emptyList()) {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(windowToken, 0)
        findFocus()?.clearFocus()
        stopAnimations()
        val snapshots = if (animate && motionEnabled()) captureShared(shared) else emptyList()
        val page = buildRoute(route, taskId)
        taskIds[page] = taskId
        stack.lastOrNull()?.second?.visibility = GONE
        stack += route to page
        secondary.addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        secondary.visibility = VISIBLE
        tabHost.visibility = INVISIBLE
        applyInsets()
        if (snapshots.isNotEmpty()) {
            page.alpha = 0f
            startSharedTransition(page, snapshots) { matched -> animatePageIn(page, !matched) }
        } else if (animate && motionEnabled()) {
            // Routes without a visible matching element use the spring scale/fade fallback.
            animatePageIn(page, true)
        }
    }

    private fun animatePageIn(page: View, scale: Boolean) {
        if (scale) {
            page.scaleX = .96f; page.scaleY = .96f
            spring(page, DynamicAnimation.SCALE_X, 1f)
        }
        page.alpha = 0f
        spring(page, DynamicAnimation.ALPHA, 1f, true)
    }

    private fun captureShared(elements: List<Pair<View, String>>): List<Triple<Bitmap, IntArray, String>> =
        elements.mapNotNull { (source, targetTag) ->
            val rect = Rect()
            if (source.width <= 0 || source.height <= 0 || !source.getGlobalVisibleRect(rect)) null else {
                val position = IntArray(2)
                source.getLocationOnScreen(position)
                val bitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                source.draw(Canvas(bitmap))
                Triple(bitmap, position, targetTag)
            }
        }

    private fun startSharedTransition(targetRoot: View, snapshots: List<Triple<Bitmap, IntArray, String>>,
                                      onReady: ((Boolean) -> Unit)? = null) {
        val observer = targetRoot.viewTreeObserver
        lateinit var pending: PendingTransition
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                pendingTransitions.remove(pending)
                if (!targetRoot.isShown) {
                    snapshots.forEach { it.first.recycle() }
                    onReady?.invoke(false)
                    return true
                }
                val origin = IntArray(2)
                getLocationOnScreen(origin)
                var remaining = 0
                val done = { remaining--; if (remaining == 0) finishSharedElements() }
                snapshots.forEach { (bitmap, start, targetTag) ->
                    val target = targetRoot.findViewWithTag<View>(targetTag)
                    val visible = Rect()
                    if (target == null || target.width == 0 || !target.getGlobalVisibleRect(visible)) {
                        bitmap.recycle(); return@forEach
                    }
                    // A cropped grid cover and a FIT_CENTER viewer have different image bounds.
                    // Cross-fade the page instead of stretching the cropped bitmap into those bounds.
                    val scale = sharedElementUniformScale(bitmap.width, bitmap.height, target.width, target.height)
                    if (scale == null) { bitmap.recycle(); return@forEach }
                    val destination = IntArray(2)
                    target.getLocationOnScreen(destination)
                    target.alpha = 0f
                    val image = ImageView(context).apply {
                        setImageBitmap(bitmap)
                        tag = "shared_transition_overlay"
                        pivotX = 0f; pivotY = 0f
                        translationX = (start[0] - origin[0]).toFloat()
                        translationY = (start[1] - origin[1]).toFloat()
                        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                    addView(image, LayoutParams(bitmap.width, bitmap.height))
                    sharedElements += SharedElement(image, bitmap, target)
                    remaining += 3
                    spring(image, DynamicAnimation.TRANSLATION_X, (destination[0] - origin[0]).toFloat(), done = done)
                    spring(image, DynamicAnimation.TRANSLATION_Y, (destination[1] - origin[1]).toFloat(), done = done)
                    spring(image, DynamicAnimation.SCALE_X, scale, done = done)
                }
                onReady?.invoke(remaining > 0)
                statusMask.bringToFront()
                return true
            }
        }
        pending = PendingTransition(observer, listener, snapshots.map { it.first })
        pendingTransitions += pending
        observer.addOnPreDrawListener(listener)
    }

    private fun finishSharedElements() {
        sharedElements.forEach {
            it.target.alpha = 1f
            removeView(it.image)
            it.image.setImageDrawable(null)
            it.bitmap.recycle()
        }
        sharedElements.clear()
    }

    private fun scrollToTop(page: View) {
        when (page) {
            is ScrollView -> page.smoothScrollTo(0, 0)
            is VideoLibraryView -> page.scrollToTop()
            is ConversionLibraryView -> page.scrollToTop()
        }
    }

    fun openOnTab(index: Int, route: ScreenRoute, taskId: String? = null, shared: List<Pair<View, String>> = emptyList()) {
        stopAnimations()
        selected = index
        bar.select(index, false)
        open(route, taskId = taskId, shared = shared)
    }

    fun showDownloads() {
        stopAnimations()
        releasePlayers()
        stack.forEach { pendingScroll.remove(it.second) }
        stack.clear()
        taskIds.clear()
        secondary.removeAllViews()
        secondary.visibility = GONE
        tabHost.visibility = VISIBLE
        if (selected != 0) selectTab(0)
        scrollToTop(tabs[0])
        applyInsets()
    }

    /** Returns false only when Back should exit the root download page. */
    fun back(): Boolean {
        if (stack.isNotEmpty()) {
            if (activePlaybackPage()?.player?.handleBack() == true) return true
            if ((stack.last().second as? TaskCenterView)?.exitSelection() == true) return true
            stopAnimations()
            val entry = stack.removeAt(stack.lastIndex)
            val outgoing = entry.second
            (outgoing as? VideoPlaybackPage)?.player?.release()
            (outgoing as? WorkViewerView)?.release()
            val id = taskIds[outgoing]
            val returningElements = when {
                entry.first == ScreenRoute.TASK_DETAIL && id != null ->
                    listOf("detail_cover" to "task_cover_$id", "detail_progress" to "task_progress_$id")
                entry.first == ScreenRoute.PLAY_VIDEO && id?.startsWith("media:") == true ->
                    listOf("video_player_cover" to "video_cover_${id.removePrefix("media:")}")
                outgoing is WorkViewerView ->
                    listOf("work_viewer_cover" to "work_cover_${outgoing.currentItem.id}")
                else -> emptyList()
            }
            val snapshots = if (returningElements.isNotEmpty() && motionEnabled()) {
                returningElements.mapNotNull { (sourceTag, targetTag) ->
                    outgoing.findViewWithTag<View>(sourceTag)?.let { it to targetTag }
                }.let(::captureShared)
            } else emptyList()
            val underneath = stack.lastOrNull()?.second
            underneath?.visibility = VISIBLE
            if (underneath == null) {
                tabHost.visibility = VISIBLE
                tabs.forEachIndexed { index, page -> page.visibility = if (index == selected) VISIBLE else GONE }
            }
            if (snapshots.isNotEmpty()) startSharedTransition(underneath ?: tabs[selected], snapshots)
            val finish = {
                pendingScroll.remove(outgoing)
                taskIds.remove(outgoing)
                secondary.removeView(outgoing)
                if (stack.isEmpty()) secondary.visibility = GONE
                applyInsets()
            }
            // stopAnimations removes this outgoing view if another navigation action interrupts it.
            if (motionEnabled()) {
                spring(outgoing, DynamicAnimation.SCALE_X, .96f)
                spring(outgoing, DynamicAnimation.ALPHA, 0f, true, finish)
            } else finish()
            applyInsets()
            return true
        }
        if (selected == 2 && (tabs[2] as? VideoLibraryView)?.exitSelection() == true) return true
        if (selected == 1 && (tabs[1] as? ConversionLibraryView)?.exitSelection() == true) return true
        if (selected != 0) { selectTab(0); return true }
        return false
    }

    fun activePlaybackPage(): VideoPlaybackPage? = stack.lastOrNull()?.second as? VideoPlaybackPage
    fun activeWorkViewer(): WorkViewerView? = stack.lastOrNull()?.second as? WorkViewerView
    fun activeCreator(): ConversionCreatorView? = stack.lastOrNull()?.second as? ConversionCreatorView
    fun refreshPresentation() = applyInsets()
    fun releasePlayers() = stack.forEach { (_, page) ->
        (page as? VideoPlaybackPage)?.player?.release()
        (page as? WorkViewerView)?.release()
    }

    fun saveState(): Bundle = Bundle().apply {
        putInt("tab", selected)
        putIntArray("scroll", tabs.map { pendingScroll[it] ?: (it as? ScrollView)?.scrollY ?: 0 }.toIntArray())
        tabs.forEachIndexed { index, page ->
            val state = SparseArray<Parcelable>()
            page.saveHierarchyState(state)
            putSparseParcelableArray("tab_state_$index", state)
        }
        putStringArrayList("routes", ArrayList(stack.map { it.first.name }))
        stack.forEachIndexed { index, (_, page) ->
            putString("route_task_$index", taskIds[page])
            putInt("route_scroll_$index", pendingScroll[page] ?: (page as? ScrollView)?.scrollY ?: 0)
            val state = SparseArray<Parcelable>()
            page.saveHierarchyState(state)
            putSparseParcelableArray("route_state_$index", state)
        }
    }

    fun restoreState(state: Bundle?) {
        if (state == null) return
        selected = state.getInt("tab").coerceIn(tabs.indices)
        bar.select(selected, false)
        tabs.forEachIndexed { index, page ->
            page.visibility = if (index == selected) VISIBLE else GONE
            val y = state.getIntArray("scroll")?.getOrNull(index) ?: 0
            @Suppress("DEPRECATION")
            state.getSparseParcelableArray<Parcelable>("tab_state_$index")?.let { page.restoreHierarchyState(it) }
            if (page is ScrollView) restoreScrollAfterLayout(page, y)
        }
        state.getStringArrayList("routes")?.forEachIndexed { index, name ->
            val route = ScreenRoute.entries.firstOrNull { it.name == name } ?: return@forEachIndexed
            open(route, false, state.getString("route_task_$index"))
            val page = stack.last().second
            @Suppress("DEPRECATION")
            state.getSparseParcelableArray<Parcelable>("route_state_$index")?.let { page.restoreHierarchyState(it) }
            if (page is ScrollView) restoreScrollAfterLayout(page, state.getInt("route_scroll_$index"))
        }
    }

    private fun restoreScrollAfterLayout(page: ScrollView, y: Int) {
        // GONE tabs and covered routes are not measured until they become visible.
        pendingScroll[page] = y
        page.addOnLayoutChangeListener(object : OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int,
                                        oldL: Int, oldT: Int, oldR: Int, oldB: Int) {
                if (page.visibility == VISIBLE && page.height > 0) {
                    page.removeOnLayoutChangeListener(this)
                    pendingScroll.remove(page)
                    page.scrollTo(0, y)
                }
            }
        })
    }

    override fun onDetachedFromWindow() {
        stopAnimations()
        super.onDetachedFromWindow()
    }
}
