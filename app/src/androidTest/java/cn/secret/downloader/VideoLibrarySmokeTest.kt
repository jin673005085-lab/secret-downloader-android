package cn.secret.downloader

import android.app.AlertDialog
import android.app.Instrumentation
import android.app.Activity
import android.content.Intent
import android.content.Context
import android.graphics.Rect
import android.os.Parcelable
import android.os.SystemClock
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.util.SparseArray
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections
import java.util.Locale

/** Every mutation is limited to uniquely named metadata created by this run. No media files are opened. */
object VideoLibrarySmokeTest {
    fun run(instrumentation: Instrumentation): String {
        fun <T> main(action: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(action) }
            return checkNotNull(result).getOrThrow()
        }
        fun await(description: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 6_000
            do {
                instrumentation.waitForIdleSync()
                if (main(condition)) return
                SystemClock.sleep(32)
            } while (SystemClock.uptimeMillis() < deadline)
            error("Timed out: $description")
        }

        val context = instrumentation.targetContext
        val repository = MediaRepository.get(context)
        val prefix = "LibrarySmoke${System.nanoTime().toString(36)}"
        val owned = FakeMediaItems.create(System.currentTimeMillis()).mapIndexed { index, item ->
            item.copy(id = "$prefix-$index", title = "$prefix ${item.title}", localUri = null, coverUri = null,
                favorite = if (index < 2) false else item.favorite)
        }
        check(owned.count { it.type == MediaType.VIDEO } == 20)
        check(owned.count { it.type == MediaType.VIDEO && !it.exists } == 2)
        val ownedIds = owned.map { it.id }.toSet()
        val serviceCounts = DownloadService.activeDownloadCount to DownloadService.activeConversionCount
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ) as MainActivity
        val report = mutableListOf<String>()
        var protected = emptyMap<String, MediaItem>()

        fun tagged(tag: String, root: View = activity.window.decorView): View =
            checkNotNull(find(root, tag)) { "Missing visible library view: $tag" }
        fun click(tag: String) = main { check(tagged(tag).performClick()) { "Not clickable: $tag" } }
        fun grid() = checkNotNull(activity.window.decorView.findViewWithTag<RecyclerView>("video_grid"))
        fun library(): VideoLibraryView = generateSequence<View>(grid()) { it.parent as? View }
            .filterIsInstance<VideoLibraryView>().first()
        fun dialog() = checkNotNull(library().activeDialog) { "Expected a library dialog" }
        fun alert() = checkNotNull(dialog() as? AlertDialog) { "Expected an alert dialog" }
        fun dialogClick(tag: String) = main {
            val root = checkNotNull(dialog().window).decorView
            check(tagged(tag, root).performClick()) { "Dialog action is not clickable: $tag" }
        }
        fun dismissDialog() = main { library().activeDialog?.dismiss() }
        fun search(value: String) = main { (tagged("video_search_input") as EditText).setText(value) }
        fun expected(search: String = prefix, filter: MediaFilter = MediaFilter.ALL,
                     sort: MediaSort = MediaSort.RECENT_DOWNLOAD) =
            repository.query(search, sort, filter, MediaType.VIDEO, limit = 100)
        fun assertRows(expected: List<MediaItem>) {
            await("${expected.size} matching video cards") { grid().adapter?.itemCount == expected.size }
            for ((position, item) in expected.withIndex()) {
                main { grid().scrollToPosition(position) }
                await("video order at $position (${item.id})") {
                    grid().findViewHolderForAdapterPosition(position)?.itemView?.let {
                        find(it, "video_card_${item.id}") != null
                    } == true
                }
            }
        }
        fun showCard(item: MediaItem, filter: MediaFilter = MediaFilter.ALL) {
            val position = expected(filter = filter).indexOfFirst { it.id == item.id }
            check(position >= 0)
            main { grid().scrollToPosition(position) }
            await("recycled card ${item.id}") { find(activity.window.decorView, "video_card_${item.id}") != null }
        }
        fun openMenu(item: MediaItem) {
            showCard(item)
            click("video_menu_${item.id}")
            await("item menu") { library().activeDialog?.isShowing == true }
        }
        fun positive() = main { check(alert().getButton(AlertDialog.BUTTON_POSITIVE).performClick()) }
        fun leaveSelection() = main {
            if (find(activity.window.decorView, "video_selection_toolbar") != null) {
                check(tagged("video_bulk_done").performClick())
            }
        }

        try {
            await("media repository initialization") { repository.isLoaded }
            main { owned.forEach(repository::add) }
            click("tab_button_2")
            await("video library tab") { find(activity.window.decorView, "video_grid") != null }
            click("video_search_toggle")
            await("search field") { find(activity.window.decorView, "video_search_input") != null }
            search(prefix)
            main {
                val input = tagged("video_search_input")
                input.clearFocus()
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(input.windowToken, 0)
            }
            await("search text entered with keyboard dismissed") { find(activity.window.decorView, "bottom_tabs") != null }
            assertRows(expected())
            main { protected = repository.items.filter { it.id !in ownedIds }.associateBy { it.id } }
            showCard(owned.first { it.type == MediaType.VIDEO })
            main {
                val target = (48 * activity.resources.displayMetrics.density).toInt()
                for (tag in listOf("video_search_toggle", "video_sort", "video_filter_ALL",
                    "video_menu_${owned.first { it.type == MediaType.VIDEO }.id}")) {
                    val control = tagged(tag)
                    check(control.width >= target && control.height >= target) { "$tag touch target is smaller than 48dp" }
                }
            }
            report += "20 owned video fixtures render; GIF/live entries stay outside the video grid: PASS"

            for (filter in MediaFilter.entries) {
                click("video_filter_${filter.name}")
                assertRows(expected(filter = filter))
            }
            click("video_filter_ALL")
            for (sort in MediaSort.entries) {
                click("video_sort")
                main {
                    val options = alert().listView
                    val index = sort.ordinal
                    check(options.performItemClick(options.getChildAt(index - options.firstVisiblePosition), index,
                        options.adapter.getItemId(index)))
                }
                assertRows(expected(sort = sort))
            }
            click("video_sort")
            main {
                val options = alert().listView
                check(options.performItemClick(options.getChildAt(0), 0, options.adapter.getItemId(0)))
            }
            for (query in listOf("$prefix 极光", "$prefix Lena", "$prefix 抖音")) {
                search(query)
                val result = expected(search = query)
                check(result.isNotEmpty())
                assertRows(result)
            }
            val fullWidthPrefix = prefix.uppercase().map { if (it in '!'..'~') (it.code + 0xfee0).toChar() else it }.joinToString("")
            search(fullWidthPrefix)
            assertRows(expected(search = fullWidthPrefix))
            search("$prefix no-such-video")
            assertRows(emptyList())
            await("friendly empty-search state") { find(activity.window.decorView, "video_library_empty") != null }
            search(prefix)
            assertRows(expected())
            report += "All four filters/sorts, title/author/platform search, Unicode normalization and empty search: PASS"

            val playing = owned.filter { it.type == MediaType.VIDEO && it.exists }[10]
            showCard(playing)
            val anchor = main {
                val videos = grid()
                (0 until videos.childCount).map { videos.getChildAt(it) }
                    .filter { videos.getChildAdapterPosition(it) >= 0 }
                    .minBy { videos.getChildAdapterPosition(it) }.let {
                        videos.getChildAdapterPosition(it) to checkNotNull(videos.layoutManager).getDecoratedTop(it)
                    }
            }
            val coverPoint = main {
                val cover = tagged("video_cover_${playing.id}")
                val visible = Rect()
                check(cover.getGlobalVisibleRect(visible))
                visible.exactCenterX() to visible.exactCenterY()
            }
            val touchTime = SystemClock.uptimeMillis()
            MotionEvent.obtain(touchTime, touchTime, MotionEvent.ACTION_DOWN, coverPoint.first, coverPoint.second, 0).let {
                try { instrumentation.sendPointerSync(it) } finally { it.recycle() }
            }
            MotionEvent.obtain(touchTime, touchTime + 60, MotionEvent.ACTION_UP, coverPoint.first, coverPoint.second, 0).let {
                try { instrumentation.sendPointerSync(it) } finally { it.recycle() }
            }
            await("cover opens full-screen video player") {
                find(activity.window.decorView, "route_PLAY_VIDEO")?.alpha?.let { it > .99f } == true &&
                    find(activity.window.decorView, "bottom_tabs") == null &&
                    find(activity.window.decorView, "video_player_cover")?.alpha?.let { it > .99f } == true
            }
            main { check(repository.get(playing.id)?.playCount == playing.playCount) { "A fake video was counted without playback" } }
            click("player_back")
            await("player return restores library viewport") {
                val videos = find(activity.window.decorView, "video_grid") as? RecyclerView
                val child = videos?.findViewHolderForAdapterPosition(anchor.first)?.itemView
                find(activity.window.decorView, "bottom_tabs") != null && videos != null && child != null &&
                    checkNotNull(videos.layoutManager).getDecoratedTop(child) == anchor.second &&
                    (find(activity.window.decorView, "video_search_input") as? EditText)?.text?.toString() == prefix
            }
            report += "Cover tap opens player with hidden bottom bar; return preserves search and exact grid viewport: PASS"

            val original = main { activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) }
            try {
                main {
                    val previous = library()
                    val state = SparseArray<Parcelable>()
                    previous.saveHierarchyState(state)
                    check(state.size() > 0)
                    val restored = VideoLibraryView(activity, UiTheme(activity), repository, TaskRepository.get(context),
                        {}, {}, { _, _ -> }, { _, _ -> }, {})
                    restored.setPadding(previous.paddingLeft, previous.paddingTop, previous.paddingRight, previous.paddingBottom)
                    restored.restoreHierarchyState(state)
                    activity.setContentView(restored)
                }
                await("recreated library restores its viewport and search") {
                    val videos = grid()
                    val child = videos.findViewHolderForAdapterPosition(anchor.first)?.itemView
                    child != null && checkNotNull(videos.layoutManager).getDecoratedTop(child) == anchor.second &&
                        (find(activity.window.decorView, "video_search_input") as? EditText)?.text?.toString() == prefix
                }
                SystemClock.sleep(260)
                main {
                    val videos = grid()
                    val child = checkNotNull(videos.findViewHolderForAdapterPosition(anchor.first)).itemView
                    check(checkNotNull(videos.layoutManager).getDecoratedTop(child) == anchor.second) {
                        "Restored search debounce reset the viewport"
                    }
                }
                report += "Recreated library hierarchy retains search and viewport after the search debounce interval: PASS"
            } finally {
                main { activity.setContentView(original) }
                await("original navigation reattached") { find(activity.window.decorView, "bottom_tabs") != null }
            }

            val missing = owned.first { it.type == MediaType.VIDEO && !it.exists }
            showCard(missing)
            main {
                val card = tagged("video_card_${missing.id}")
                check(card.alpha < 1f || tagged("video_cover_${missing.id}").alpha < 1f) { "Missing file card is not dimmed" }
            }
            click("video_card_${missing.id}")
            await("missing-file explanation") {
                library().activeDialog?.window?.decorView?.let { texts(it).any { text -> "文件已不存在" in text } } == true
            }
            dismissDialog()

            val item = owned.first { it.type == MediaType.VIDEO && it.exists }
            openMenu(item)
            main {
                val root = checkNotNull(dialog().window).decorView
                listOf("play", "favorite", "watch_later", "gif", "live", "share", "rename", "info", "delete").forEach {
                    tagged("video_action_$it", root)
                }
            }
            dialogClick("video_action_favorite")
            await("favorite metadata") { repository.get(item.id)?.favorite == true }
            openMenu(item)
            dialogClick("video_action_watch_later")
            await("watch-later metadata") { repository.get(item.id)?.watchLater == !item.watchLater }
            openMenu(item)
            dialogClick("video_action_rename")
            main {
                val edit = descendants(checkNotNull(dialog().window).decorView).filterIsInstance<EditText>().first()
                edit.setText("$prefix renamed video")
            }
            positive()
            await("renamed title") { repository.get(item.id)?.title == "$prefix renamed video" }
            openMenu(checkNotNull(repository.get(item.id)))
            dialogClick("video_action_info")
            main {
                val content = texts(checkNotNull(dialog().window).decorView).joinToString("\n")
                check("$prefix renamed video" in content && item.platform in content) { "Details omit title/platform" }
            }
            dismissDialog()
            report += "Missing-file dimming/explanation, complete item menu, favorite, watch later, rename and metadata details: PASS"

            for ((action, route) in listOf("gif" to "CREATE_GIF", "live" to "CREATE_LIVE")) {
                val selectedItem = checkNotNull(repository.get(item.id))
                openMenu(selectedItem)
                val viewport = main {
                    val videos = grid()
                    (0 until videos.childCount).map { videos.getChildAt(it) }
                        .filter { videos.getChildAdapterPosition(it) >= 0 }
                        .minBy { videos.getChildAdapterPosition(it) }.let {
                            videos.getChildAdapterPosition(it) to checkNotNull(videos.layoutManager).getDecoratedTop(it)
                        }
                }
                dialogClick("video_action_$action")
                await("$action opens the selected video in conversion") {
                    val page = find(activity.window.decorView, "route_$route")
                    page != null && page.alpha > .99f && texts(page).any { selectedItem.title in it } &&
                        find(activity.window.decorView, "bottom_tabs") == null &&
                        activity.window.decorView.findViewWithTag<View>("tab_button_1").isSelected
                }
                click("route_back")
                await("creator returns to conversion tab") {
                    find(activity.window.decorView, "tab_convert") != null && find(activity.window.decorView, "bottom_tabs") != null
                }
                click("tab_button_2")
                await("conversion leaves original library state intact") {
                    val videos = find(activity.window.decorView, "video_grid") as? RecyclerView
                    val child = videos?.findViewHolderForAdapterPosition(viewport.first)?.itemView
                    videos != null && child != null && checkNotNull(videos.layoutManager).getDecoratedTop(child) == viewport.second &&
                        (find(activity.window.decorView, "video_search_input") as? EditText)?.text?.toString() == prefix
                }
            }
            report += "GIF/live menu actions carry the selected title to the conversion tab and retain original library search/viewport: PASS"

            val selected = owned.filter { it.type == MediaType.VIDEO && it.exists }.take(2)
            click("video_filter_FAVORITES")
            val matchingFavorites = expected(filter = MediaFilter.FAVORITES)
            check(matchingFavorites.isNotEmpty() && matchingFavorites.size < expected().size)
            check(matchingFavorites.all { it.id in ownedIds }) { "Search escaped this run's owned fixtures" }
            assertRows(matchingFavorites)
            val selectionBefore = main { repository.items.associateBy { it.id } }
            showCard(matchingFavorites.first(), MediaFilter.FAVORITES)
            main { check(tagged("video_card_${matchingFavorites.first().id}").performLongClick()) }
            await("video selection starts with one owned result") { (tagged("video_selection_count") as TextView).text.toString() == "已选 1 项" }
            click("video_bulk_all")
            main {
                check((tagged("video_selection_count") as TextView).text.toString() == "已选 ${matchingFavorites.size} 项")
                check((tagged("video_bulk_all") as TextView).text.toString() == "取消全选")
            }
            click("video_bulk_all")
            main {
                check((tagged("video_selection_count") as TextView).text.toString() == "已选 0 项")
                check((tagged("video_bulk_all") as TextView).text.toString() == "全选")
                check(!tagged("video_bulk_favorite").isEnabled && !tagged("video_bulk_delete").isEnabled)
            }
            leaveSelection()
            await("video multi-selection completed") { find(activity.window.decorView, "video_selection_toolbar") == null }
            main { check(repository.items.associateBy { it.id } == selectionBefore) { "Selection-only actions changed media records" } }
            click("video_filter_ALL")
            assertRows(expected())
            report += "Select-all is limited to current favorite/search results; deselect-all reaches zero and Done exits without mutations: PASS"

            leaveSelection()
            showCard(selected[0])
            main { check(tagged("video_card_${selected[0].id}").performLongClick()) }
            await("multi-selection toolbar") { find(activity.window.decorView, "video_selection_toolbar") != null }
            main { check((tagged("video_selection_count") as TextView).text.toString() == "已选 1 项") }
            showCard(selected[1])
            click("video_card_${selected[1].id}")
            main { check((tagged("video_selection_count") as TextView).text.toString() == "已选 2 项") }
            click("video_bulk_favorite")
            await("bulk favorite") { selected.all { repository.get(it.id)?.favorite == true } }
            leaveSelection()
            showCard(selected[0])
            main { check(tagged("video_card_${selected[0].id}").performLongClick()) }
            main { check((tagged("video_selection_count") as TextView).text.toString() == "已选 1 项") }
            showCard(selected[1])
            click("video_card_${selected[1].id}")
            main { check((tagged("video_selection_count") as TextView).text.toString() == "已选 2 项") }
            click("video_bulk_delete")
            main {
                val deleteFile = tagged("video_delete_file", checkNotNull(dialog().window).decorView) as CheckBox
                check(!deleteFile.isChecked) { "Physical file deletion is selected by default" }
            }
            positive()
            await("bulk metadata deletion") { selected.all { repository.get(it.id) == null } }
            main { repository.update(selected[0]); check(repository.get(selected[0].id) == null) { "A stale update resurrected a deleted record" } }
            main { check(find(activity.window.decorView, "video_selection_toolbar") == null) { "Selection mode remains active after bulk delete" } }
            report += "Long-press multi-select, bulk favorite/delete, unchecked file-delete option and selection exit: PASS"
            report += scrollFrames(instrumentation, activity, main { grid() })
            click("tab_button_2")
            await("reselected library tab scrolls back to its first row") {
                val videos = grid()
                (0 until videos.childCount).map { videos.getChildAdapterPosition(videos.getChildAt(it)) }
                    .filter { it >= 0 }.minOrNull() == 0
            }

            main {
                check(protected.all { (id, original) -> repository.get(id) == original }) { "An existing library record changed" }
                check(ownedIds.mapNotNull(repository::get).all { it.localUri == null && it.coverUri == null })
                check(DownloadService.activeDownloadCount == serviceCounts.first && DownloadService.activeConversionCount == serviceCounts.second)
                check(repository.persistenceError == null) { "Media metadata persistence failed: ${repository.persistenceError}" }
            }
            report += "Existing records untouched; no real media, network or download/conversion service work: PASS"
            report += MediaRepositorySmokeTest.run(context)
            return report.joinToString("\n")
        } finally {
            main {
                if (!activity.isFinishing) {
                    find(activity.window.decorView, "video_grid")?.let { library().activeDialog?.dismiss() }
                    activity.finish()
                }
                ownedIds.forEach(repository::delete)
            }
        }
    }

    private fun scrollFrames(instrumentation: Instrumentation, activity: Activity, grid: RecyclerView): String {
        val thread = HandlerThread("library-scroll-metrics").apply { start() }
        val samples = Collections.synchronizedList(mutableListOf<Long>())
        val listener = Window.OnFrameMetricsAvailableListener { _, frame, _ ->
            val duration = frame.getMetric(FrameMetrics.TOTAL_DURATION)
            if (duration > 0) samples += duration
        }
        var refreshRate = 60f
        try {
            instrumentation.runOnMainSync { grid.scrollToPosition(0) }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                @Suppress("DEPRECATION")
                refreshRate = activity.windowManager.defaultDisplay.refreshRate.coerceAtLeast(1f)
                activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
                grid.smoothScrollBy(0, (activity.resources.displayMetrics.density * 2_200).toInt(),
                    android.view.animation.LinearInterpolator(), 1_000)
            }
            SystemClock.sleep(1_200)
        } finally {
            instrumentation.runOnMainSync { activity.window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
            thread.join(500)
        }
        val sorted = synchronized(samples) { samples.sorted() }
        check(sorted.size >= 3) { "No meaningful frame samples were captured during library scrolling" }
        val p50 = sorted[((sorted.size - 1) * .50).toInt()] / 1_000_000.0
        val p95 = sorted[((sorted.size - 1) * .95).toInt()] / 1_000_000.0
        val budgetMs = 1_000.0 / refreshRate
        val slow = sorted.count { it / 1_000_000.0 > budgetMs }
        return String.format(Locale.US,
            "Instrumented video-grid scroll: %d frames, p50 %.1fms, p95 %.1fms; %d/%d over %.1fms (%.0fHz display budget).",
            sorted.size, p50, p95, slow, sorted.size, budgetMs, refreshRate)
    }

    private fun find(root: View, tag: String): View? {
        if (!root.isShown) return null
        if (root.tag == tag) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) find(root.getChildAt(index), tag)?.let { return it }
        return null
    }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }
    private fun texts(root: View) = descendants(root).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }.toList()
}
