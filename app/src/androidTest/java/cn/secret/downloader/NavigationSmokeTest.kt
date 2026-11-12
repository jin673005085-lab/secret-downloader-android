package cn.secret.downloader

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ScrollView
import kotlin.math.abs

/** Uses the real view hierarchy without submitting downloads or modifying saved settings. */
object NavigationSmokeTest {
    fun run(instrumentation: Instrumentation): String {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ) as MainActivity
        val report = mutableListOf<String>()

        fun <T> main(action: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(action) }
            return checkNotNull(result).getOrThrow()
        }

        fun await(description: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 4_000
            do {
                instrumentation.waitForIdleSync()
                if (main(condition)) return
                SystemClock.sleep(32)
            } while (SystemClock.uptimeMillis() < deadline)
            error("Timed out: $description")
        }

        fun tagged(tag: String): View = checkNotNull(find(activity.window.decorView, tag)) {
            "Missing view tag: $tag"
        }

        fun click(tag: String) {
            main {
                val view = checkNotNull(find(activity.window.decorView, tag, visibleOnly = true)) {
                    "Missing visible navigation action: $tag"
                }
                check(view.isEnabled && view.performClick()) { "Navigation action is not clickable: $tag" }
            }
        }

        val pageTags = listOf("tab_download", "tab_convert", "tab_library")
        fun page(index: Int) = tagged(pageTags[index])
        fun scrollPage(index: Int) = page(index) as ScrollView
        fun tab(index: Int) {
            if (!main { tagged("tab_button_$index").isSelected }) click("tab_button_$index")
            await("tab $index") {
                val view = page(index)
                tagged("tab_button_$index").isSelected && view.isShown &&
                    view.alpha > .99f && abs(view.translationX) < 1f
            }
        }

        fun route(name: String, action: String = "open_$name") {
            click(action)
            await("route $name with hidden bottom bar") {
                val view = tagged("route_$name")
                view.isShown && view.alpha > .99f && !tagged("bottom_tabs").isShown
            }
        }

        fun returnToTab(index: Int, y: Int, systemBack: Boolean = false) {
            if (systemBack) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK) else click("route_back")
            await("return to tab $index") {
                    tagged("bottom_tabs").isShown && page(index).isShown && (index != 0 || page(index).scrollY == y)
            }
        }

        val repository = TaskRepository.get(instrumentation.targetContext)
        val navigationTask = main {
            repository.create(TaskType.DOWNLOAD, "导航测试 · 任务详情", params = "{\"fakeTask\":true}").also { repository.pause(it.id) }
        }
        try {
            await("download page layout") { page(0).isShown && page(0).height > 0 }
            val density = activity.resources.displayMetrics.density
            val bar = main { tagged("bottom_tabs") }
            val positions = IntArray(3)
            for (index in 0..0) {
                tab(index)
                main {
                    val scroll = scrollPage(index)
                    scroll.scrollTo(0, 0)
                    val mask = tagged("status_bar_mask")
                    val insets = checkNotNull(activity.window.decorView.rootWindowInsets)
                    val statusTop = if (Build.VERSION.SDK_INT >= 30) {
                        insets.getInsets(WindowInsets.Type.statusBars()).top
                    } else {
                        @Suppress("DEPRECATION")
                        insets.systemWindowInsetTop
                    }
                    check(mask.isShown && mask.height >= statusTop) { "Status bar mask is too short on tab $index" }
                    val content = scroll.getChildAt(0) as ViewGroup
                    val first = content.getChildAt(0)
                    check(bounds(first).top >= bounds(mask).top + statusTop) {
                        "Initial content overlaps status icons on tab $index"
                    }
                    val unobscuredBottom = bounds(scroll).bottom - scroll.paddingBottom
                    check(unobscuredBottom <= bounds(bar).top + 2) {
                        "Tab $index does not reserve bottom bar space"
                    }
                    val navigationBottom = if (Build.VERSION.SDK_INT >= 30) {
                        insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                    } else {
                        @Suppress("DEPRECATION")
                        insets.systemWindowInsetBottom
                    }
                    check(bar.height >= (56 * density).toInt() + navigationBottom - 2) {
                        "Bottom bar omits the navigation safe area"
                    }
                    // Placeholder pages may fit on a tall screen; create real scroll range for retention checks.
                    content.minimumHeight = maxOf(content.height, scroll.height * 2)
                    content.requestLayout()
                }
                await("scroll range for tab $index") { scrollPage(index).getChildAt(0).height >= page(index).height * 2 }
                main {
                    page(index).scrollTo(0, ((180 + index * 40) * density).toInt())
                    positions[index] = page(index).scrollY
                    check(positions[index] > 0) { "Could not scroll tab $index" }
                }
            }
            for (index in 0..0) {
                tab(1)
                tab(index)
                main { check(page(index).scrollY == positions[index]) { "Tab $index lost its scroll position" } }
            }
            click("tab_button_0")
            await("reselected tab scrolls to top") { page(0).scrollY == 0 }
            main { page(0).scrollTo(0, positions[0]) }
            report += "Download tab retains scroll position; reselect returns to top; top/bottom safe areas: PASS"

            tab(0)
            route("TASK_CENTER", "open_task_center")
            await("test task row") { find(activity.window.decorView, "task_row_${navigationTask.id}", true) != null }
            route("TASK_DETAIL", "task_row_${navigationTask.id}")
            click("route_back")
            await("task detail returns to task center") {
                tagged("route_TASK_CENTER").isShown && !bar.isShown
            }
            returnToTab(0, positions[0])
            report += "Task center/detail stack keeps bottom bar hidden until returning to download: PASS"

            tab(1)
            for (name in listOf("CREATE_GIF", "CREATE_LIVE")) {
                route(name)
                returnToTab(1, positions[1], systemBack = name == "CREATE_GIF")
            }
            for (index in 0..2) {
                tab(index)
                route("SETTINGS")
                returnToTab(index, positions[index])
            }
            report += "GIF/live creators and settings hide/restore bottom bar and preserve the underlying page: PASS"

            tab(1)
            route("CREATE_GIF")
            main {
                checkNotNull(find(activity.window.decorView, "route_back", true)).performClick()
                checkNotNull(find(activity.window.decorView, "open_CREATE_LIVE", true)).performClick()
            }
            await("rapid Back/open resolves to the new route") {
                tagged("route_CREATE_LIVE").isShown &&
                    (tagged("secondary_pages") as ViewGroup).childCount == 1 && !bar.isShown
            }
            returnToTab(1, positions[1])
            main {
                for (index in listOf(0, 2, 0, 1, 2)) tagged("tab_button_$index").performClick()
            }
            await("rapid tab switching settles on the last selection") {
                tagged("tab_button_2").isSelected && page(2).isShown && page(2).alpha > .99f &&
                    abs(page(2).translationX) < 1f && !page(0).isShown && !page(1).isShown
            }
            main {
                check((tagged("secondary_pages") as ViewGroup).childCount == 0) { "A stale secondary overlay remains" }
                val bottomBar = bar as BottomTabBar
                bottomBar.setBadges(2, 1)
                check(tagged("tab_button_0").contentDescription.contains("2 个"))
                check(tagged("tab_button_1").contentDescription.contains("1 个"))
                bottomBar.setBadges(repository.tasks.count { it.isOngoing && it.type == TaskType.DOWNLOAD },
                    repository.tasks.count { it.isOngoing && it.type != TaskType.DOWNLOAD })
            }
            report += "Interrupted route/tab animations leave no stale overlay; task badge interface: PASS"

            val original = main { activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) }
            try {
                val routes = mutableMapOf<ScreenRoute, ScrollView>()
                fun newPage() = ScrollView(activity).apply {
                    isFillViewport = true
                    // ScrollView measures child height with UNSPECIFIED, so a bare View needs a minimum.
                    addView(View(activity).apply { minimumHeight = (2_000 * density).toInt() }, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                fun geometry(page: ScrollView) = "pageHeight=${page.height}, childHeight=${page.getChildAt(0)?.height}, " +
                    "padding=${page.paddingTop}/${page.paddingBottom}, scrollY=${page.scrollY}"
                fun awaitScrollable(label: String, page: ScrollView) {
                    try {
                        await(label) { page.isShown && page.height > 0 && page.getChildAt(0).height > page.height && page.alpha > .99f }
                    } catch (error: Throwable) {
                        throw IllegalStateException("$label: ${main { geometry(page) }}", error)
                    }
                }
                fun newShell(tabs: List<ScrollView>) = NavigationShell(activity, UiTheme(activity), tabs) { route, _ ->
                    newPage().also { routes[route] = it }
                }
                val scratchTabs = main { List(3) { newPage() } }
                val scratch = main { newShell(scratchTabs).also(activity::setContentView) }
                val savedPositions = IntArray(3)
                for (index in 0..2) {
                    main { checkNotNull(find(scratch, "tab_button_$index")).performClick() }
                    awaitScrollable("scratch tab $index scroll range", scratchTabs[index])
                    main {
                        scratchTabs[index].scrollTo(0, ((200 + index * 40) * density).toInt())
                        savedPositions[index] = scratchTabs[index].scrollY
                        check(savedPositions[index] > 0) { "scratch tab $index failed to scroll: ${geometry(scratchTabs[index])}" }
                    }
                }
                main {
                    checkNotNull(find(scratch, "tab_button_1")).performClick()
                    scratch.open(ScreenRoute.TASK_CENTER, false)
                }
                awaitScrollable("scratch task center scroll range", checkNotNull(routes[ScreenRoute.TASK_CENTER]))
                val centerY = main {
                    checkNotNull(routes[ScreenRoute.TASK_CENTER]).run {
                        scrollTo(0, (330 * density).toInt())
                        check(scrollY > 0) { "scratch task center failed to scroll: ${geometry(this)}" }
                        scrollY
                    }
                }
                main { scratch.open(ScreenRoute.TASK_DETAIL, false) }
                awaitScrollable("scratch task detail scroll range", checkNotNull(routes[ScreenRoute.TASK_DETAIL]))
                val detailY = main {
                    checkNotNull(routes[ScreenRoute.TASK_DETAIL]).run {
                        scrollTo(0, (410 * density).toInt())
                        check(scrollY > 0) { "scratch task detail failed to scroll: ${geometry(this)}" }
                        scrollY
                    }
                }
                val saved = main { scratch.saveState() }
                val restoredTabs = main { List(3) { newPage() } }
                val restored = main {
                    routes.clear()
                    newShell(restoredTabs).also {
                        it.restoreState(saved)
                        activity.setContentView(it)
                    }
                }
                await("restored top route scroll") { checkNotNull(routes[ScreenRoute.TASK_DETAIL]).scrollY == detailY }
                main {
                    check(checkNotNull(find(restored, "tab_button_1")).isSelected) { "Selected tab was not restored" }
                    check(!checkNotNull(find(restored, "bottom_tabs")).isShown)
                    check(restored.back())
                }
                await("restored previously GONE task center scroll") {
                    checkNotNull(routes[ScreenRoute.TASK_CENTER]).run { isShown && scrollY == centerY }
                }
                main { check(restored.back()) }
                await("restored selected tab scroll") {
                    restoredTabs[1].isShown && restoredTabs[1].scrollY == savedPositions[1] &&
                        checkNotNull(find(restored, "bottom_tabs")).isShown
                }
                for (index in listOf(0, 2, 1)) {
                    main { checkNotNull(find(restored, "tab_button_$index")).performClick() }
                    await("restored previously GONE tab $index scroll") {
                        restoredTabs[index].isShown && restoredTabs[index].alpha > .99f &&
                            restoredTabs[index].scrollY == savedPositions[index]
                    }
                }
                report += "Saved state restores the selected tab, unmeasured hidden tabs and nested route scroll positions: PASS"
            } finally {
                main { activity.setContentView(original) }
                await("original navigation reattached") { original.isAttachedToWindow && bar.isShown }
            }

            for (index in listOf(1, 2)) {
                tab(index)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                await("Back from tab $index returns to download") {
                    tagged("tab_button_0").isSelected && page(0).isShown && bar.isShown
                }
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Back from download exits the foreground") { activity.isFinishing || !activity.hasWindowFocus() }
            report += "System Back returns non-download tabs to download, then exits: PASS"
            return report.joinToString("\n")
        } finally {
            main { repository.delete(navigationTask.id); if (!activity.isFinishing) activity.finish() }
        }
    }

    private fun find(view: View, tag: String, visibleOnly: Boolean = false): View? {
        if (view.tag == tag && (!visibleOnly || view.isShown)) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                find(view.getChildAt(index), tag, visibleOnly)?.let { return it }
            }
        }
        return null
    }

    private fun bounds(view: View): Rect {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }
}
