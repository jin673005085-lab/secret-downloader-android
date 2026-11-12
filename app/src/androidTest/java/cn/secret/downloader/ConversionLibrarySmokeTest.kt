package cn.secret.downloader

import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.os.Parcelable
import android.os.SystemClock
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Exercises only uniquely named metadata and never modifies an existing work or media file. */
object ConversionLibrarySmokeTest {
    fun run(instrumentation: Instrumentation): String {
        fun <T> main(action: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(action) }
            return checkNotNull(result).getOrThrow()
        }
        fun await(label: String, predicate: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 6_000
            do {
                instrumentation.waitForIdleSync()
                if (main(predicate)) return
                SystemClock.sleep(40)
            } while (SystemClock.uptimeMillis() < deadline)
            error("Timed out: $label")
        }
        val context = instrumentation.targetContext
        val media = MediaRepository.get(context)
        val tasks = TaskRepository.get(context)
        val id = "WorkSmoke${System.nanoTime().toString(36)}"
        val now = System.currentTimeMillis()
        val owned = (0..21).map { index -> MediaItem("$id-$index", title = "$id work $index",
            type = if (index == 1) MediaType.LIVE_PHOTO else MediaType.GIF,
            downloadedAt = now + 100 + index, sizeBytes = (index + 1) * 1_000L, exists = index != 3) }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val original = main { activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) }
        var created: Boolean? = null
        var opened: String? = null
        fun makeView() = ConversionLibraryView(activity, UiTheme(activity), media, tasks,
            { gif, source -> check(source != null); created = gif }, {},
            { item, entries, source -> check(source != null && entries.all { it.type != MediaType.VIDEO }); opened = item.id }, {})
        var view: ConversionLibraryView? = null
        fun tagged(tag: String): View = checkNotNull(find(checkNotNull(view), tag)) { "Missing work view $tag" }
        fun click(tag: String) = main { check(tagged(tag).performClick()) }
        fun grid() = checkNotNull(view).findViewWithTag<RecyclerView>("work_grid")
        fun show(item: MediaItem, filter: WorkFilter = WorkFilter.ALL) {
            val position = WorkQueries.select(media.items, filter, WorkSort.CREATED).indexOfFirst { it.id == item.id }
            check(position >= 0)
            main { grid().scrollToPosition(position) }
            await("visible work ${item.id}") { find(checkNotNull(view), "work_card_${item.id}") != null }
        }
        var protected = emptyMap<String, MediaItem>()
        try {
            await("media repository initialization") { media.isLoaded }
            main {
                protected = media.items.associateBy { it.id }; owned.forEach(media::add)
                view = makeView().also(activity::setContentView)
            }
            await("three-column work grid") { (grid().layoutManager as GridLayoutManager).spanCount == 3 }
            main {
                val target = (48 * activity.resources.displayMetrics.density).toInt()
                for (tag in listOf("work_sort", "work_filter_ALL")) {
                    val control = tagged(tag)
                    check(control.width >= target && control.height >= target) { "$tag touch target is smaller than 48dp" }
                }
            }
            for (filter in WorkFilter.entries) {
                click("work_filter_${filter.name}")
                await("work filter $filter") { grid().adapter?.itemCount == WorkQueries.select(media.items, filter, WorkSort.CREATED).size }
            }
            click("work_filter_ALL")
            click("work_sort")
            main {
                val dialog = checkNotNull(view?.activeDialog)
                val options = dialog.listView
                check(options.performItemClick(options.getChildAt(1), 1, options.adapter.getItemId(1)))
            }
            await("size sorting") {
                val expected = WorkQueries.select(media.items, WorkFilter.ALL, WorkSort.SIZE)
                grid().adapter?.getItemId(0) == VideoLibraryUi.stableId(expected.first().id)
            }
            click("work_sort")
            main {
                val dialog = checkNotNull(view?.activeDialog); val options = dialog.listView
                check(options.performItemClick(options.getChildAt(0), 0, options.adapter.getItemId(0)))
            }
            click("open_CREATE_GIF"); main { check(created == true) }
            click("open_CREATE_LIVE"); main { check(created == false) }
            show(owned[0]); click("work_card_${owned[0].id}"); main { check(opened == owned[0].id) }
            val anchor = main {
                val first = (grid().layoutManager as GridLayoutManager).findFirstVisibleItemPosition()
                first to checkNotNull(grid().layoutManager).getDecoratedTop(checkNotNull(grid().findViewHolderForAdapterPosition(first)).itemView)
            }
            main {
                val state = SparseArray<Parcelable>()
                checkNotNull(view).saveHierarchyState(state)
                view = makeView().also { it.restoreHierarchyState(state); activity.setContentView(it) }
            }
            await("work grid restores its scroll position") {
                val child = grid().findViewHolderForAdapterPosition(anchor.first)?.itemView
                child != null && checkNotNull(grid().layoutManager).getDecoratedTop(child) == anchor.second
            }
            show(owned[3])
            main { check(tagged("work_cover_${owned[3].id}").alpha < 1f) }
            click("work_card_${owned[3].id}")
            main {
                val dialog = checkNotNull(view?.activeDialog)
                check(dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick())
            }
            await("missing work record removed") { media.get(owned[3].id) == null }

            click("work_filter_GIF")
            await("GIF-only selection scope") { grid().adapter?.itemCount == WorkQueries.select(media.items, WorkFilter.GIF, WorkSort.CREATED).size }
            val selectionBefore = main { media.items.associateBy { it.id } }
            show(owned[0], WorkFilter.GIF)
            main { check(tagged("work_card_${owned[0].id}").performLongClick()) }
            await("work selection starts with one owned GIF") { (tagged("work_selection_count") as TextView).text.toString() == "已选 1 项" }
            click("work_bulk_all")
            main {
                val count = WorkQueries.select(media.items, WorkFilter.GIF, WorkSort.CREATED).size
                check(count == grid().adapter?.itemCount && count < WorkQueries.select(media.items, WorkFilter.ALL, WorkSort.CREATED).size)
                check((tagged("work_selection_count") as TextView).text.toString() == "已选 $count 项")
                check((tagged("work_bulk_all") as TextView).text.toString() == "取消全选")
            }
            click("work_bulk_all")
            main {
                check((tagged("work_selection_count") as TextView).text.toString() == "已选 0 项")
                check((tagged("work_bulk_all") as TextView).text.toString() == "全选")
                check(!tagged("work_bulk_favorite").isEnabled && !tagged("work_bulk_delete").isEnabled)
            }
            click("work_bulk_done")
            await("work multi-selection completed") { find(checkNotNull(view), "work_selection_toolbar") == null }
            main { check(media.items.associateBy { it.id } == selectionBefore) { "Selection-only actions changed work/media records" } }
            click("work_filter_ALL")
            await("all works restored after selection check") { grid().adapter?.itemCount == WorkQueries.select(media.items, WorkFilter.ALL, WorkSort.CREATED).size }
            show(owned[0]); main { check(tagged("work_card_${owned[0].id}").performLongClick()) }
            main { check((tagged("work_selection_count") as TextView).text.toString() == "已选 1 项") }
            show(owned[1]); click("work_card_${owned[1].id}")
            main { check((tagged("work_selection_count") as TextView).text.toString() == "已选 2 项") }
            click("work_bulk_favorite")
            await("batch favorites persisted") { owned.take(2).all { media.get(it.id)?.favorite == true } }
            click("work_bulk_delete")
            main {
                val dialog = checkNotNull(view?.activeDialog)
                check(dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick())
            }
            await("batch records removed") { owned.take(2).all { media.get(it.id) == null } }
            main { check(!checkNotNull(view).exitSelection()) }
            main { check(protected.all { (key, item) -> media.get(key) == item }) }
            return "Creative grid excludes VIDEO; four filters/two sorts/creator callbacks/opened work and filtered viewer list: PASS\n" +
                "Recreated grid preserves scroll; filtered select-all/deselect-to-zero/Done are non-destructive; missing-file removal and owned-only multi-select favorite/delete preserve existing records: PASS"
        } finally {
            main {
                view?.activeDialog?.dismiss(); activity.setContentView(original)
                owned.forEach { media.delete(it.id) }; if (!activity.isFinishing) activity.finish()
            }
        }
    }
    private fun find(root: View, tag: String): View? {
        if (!root.isShown) return null
        if (root.tag == tag) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) find(root.getChildAt(index), tag)?.let { return it }
        return null
    }
}
