package cn.secret.downloader

import android.app.Instrumentation
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.util.Collections
import java.util.Locale

/** Exercises only the fake engine; all repository mutations belong to IDs created by this run. */
object TaskSystemSmokeTest {
    fun run(instrumentation: Instrumentation): String {
        fun <T> main(action: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(action) }
            return checkNotNull(result).getOrThrow()
        }
        fun await(description: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 5_000
            do {
                instrumentation.waitForIdleSync()
                if (main(condition)) return
                SystemClock.sleep(32)
            } while (SystemClock.uptimeMillis() < deadline)
            error("Timed out: $description")
        }

        val context = instrumentation.targetContext
        val repository = TaskRepository.get(context)
        main { repository.startFakeSource() }
        val owned = mutableListOf<String>()
        val report = mutableListOf<String>()
        val serviceCounts = DownloadService.activeDownloadCount to DownloadService.activeConversionCount
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ) as MainActivity
        fun view(tag: String): View = checkNotNull(find(activity.window.decorView, tag)) { "Missing visible view: $tag" }
        fun click(tag: String) = main { check(view(tag).performClick()) { "Not clickable: $tag" } }
        fun ready(route: String) = await("route $route") {
            find(activity.window.decorView, "route_$route")?.let { it.alpha > .99f } == true
        }
        fun create(type: TaskType, title: String): Task = main {
            repository.create(type, "测试 · $title", "https://example.invalid/task-smoke",
                "{\"fakeTask\":true,\"fps\":15,\"width\":480,\"cover\":\"视频第 2 秒\"}").also { owned += it.id }
        }
        fun current(id: String) = checkNotNull(repository.get(id))
        fun list() = view("task_center_list") as ListView
        fun showTask(id: String) {
            main {
                val list = list()
                val position = (0 until list.count).firstOrNull { (list.adapter.getItem(it) as Task).id == id }
                    ?: error("Task $id missing from the selected filter")
                if (position < list.firstVisiblePosition || position > list.lastVisiblePosition)
                    list.setSelectionFromTop(position, 0)
            }
            await("recycled row $id") { find(activity.window.decorView, "task_row_$id") != null }
        }
        fun openTask(id: String) {
            showTask(id)
            click("task_row_$id")
            ready("TASK_DETAIL")
            main { check(find(activity.window.decorView, "bottom_tabs") == null) { "Bottom bar is visible over task detail" } }
        }
        fun centerBack() {
            click("route_back")
            ready("TASK_CENTER")
        }
        fun assertActions(expected: Set<String>) = main {
            val detail = view("route_TASK_DETAIL")
            val actual = mutableSetOf<String>()
            visit(detail) { child ->
                val tag = child.tag as? String
                if (child.isShown && tag?.startsWith("task_action_") == true) actual += tag.removePrefix("task_action_")
            }
            check(actual == expected) { "Detail actions $actual, expected $expected" }
        }
        fun assertFilter(completed: Boolean, type: TaskTypeFilter) = main {
            val expected = TaskUi.filter(repository.tasks, completed, type).map { it.id }
            val list = list()
            val actual = (0 until list.count).map { (list.adapter.getItem(it) as Task).id }
            check(actual == expected) { "Task center filter differs from the repository" }
        }

        try {
            val probe = create(TaskType.DOWNLOAD, "暂停、继续与重试")
            await("fake engine running after repository load") { current(probe.id).status == TaskStatus.RUNNING }
            val paused = main { repository.pause(probe.id); current(probe.id) }
            check(paused.status == TaskStatus.PAUSED && paused.progress != null)
            SystemClock.sleep(300)
            main { check(current(probe.id).progress == paused.progress) { "Paused task advanced" } }
            main { repository.resume(probe.id); check(current(probe.id).progress == paused.progress) }
            await("resume advances from retained progress") { (current(probe.id).progress ?: 0f) > paused.progress!! }
            main {
                repository.cancel(probe.id)
                check(current(probe.id).status == TaskStatus.CANCELED)
                repository.retry(probe.id)
                val retried = current(probe.id)
                check(retried.status == TaskStatus.QUEUED && retried.progress == 0f && retried.attempt == 1)
                repository.pause(probe.id)
            }
            report += "Fake engine updates, pause stability, resume continuity, cancel and retry reset: PASS"

            val failed = create(TaskType.GIF, "失败详情")
            val pausedLive = create(TaskType.LIVE_PHOTO, "暂停实况")
            val completed = TaskType.entries.map { create(it, "完成 ${it.name}") }
            val canceled = create(TaskType.DOWNLOAD, "已取消")
            val recycled = List(30) { create(if (it % 2 == 0) TaskType.DOWNLOAD else TaskType.GIF, "列表 $it") }
            main {
                repository.updateFromFake(listOf(
                    current(failed.id).copy(status = TaskStatus.FAILED, progress = .63f, errorMessage = "测试编码失败"),
                    current(pausedLive.id).copy(status = TaskStatus.PAUSED, progress = .46f),
                    current(canceled.id).copy(status = TaskStatus.CANCELED, progress = .2f)
                ) + completed.map { current(it.id).copy(status = TaskStatus.COMPLETED, progress = 1f,
                    bytesDone = 1_024_000, bytesTotal = 1_024_000, finishedAt = System.currentTimeMillis()) } +
                    recycled.map { current(it.id).copy(status = TaskStatus.PAUSED, progress = .25f) })
            }
            click("open_task_center")
            ready("TASK_CENTER")
            assertFilter(false, TaskTypeFilter.ALL)
            click("task_filter_DOWNLOAD")
            assertFilter(false, TaskTypeFilter.DOWNLOAD)
            click("task_filter_CONVERSION")
            assertFilter(false, TaskTypeFilter.CONVERSION)
            click("task_segment_1")
            assertFilter(true, TaskTypeFilter.CONVERSION)
            click("task_filter_ALL")
            assertFilter(true, TaskTypeFilter.ALL)
            main { check((0 until list().count).any { (list().adapter.getItem(it) as Task).id == canceled.id }) }
            click("task_segment_0")

            click("task_filter_CONVERSION")
            assertFilter(false, TaskTypeFilter.CONVERSION)
            showTask(pausedLive.id)
            val selectionBefore = main { repository.tasks.associateBy { it.id } }
            main { check(view("task_row_${pausedLive.id}").performLongClick()) }
            await("task selection starts with one owned task") { (view("task_selection_count") as TextView).text.toString() == "已选 1 项" }
            click("task_bulk_all")
            main {
                val count = TaskUi.filter(repository.tasks, false, TaskTypeFilter.CONVERSION).size
                check(count == list().count && count < repository.tasks.size)
                check((view("task_selection_count") as TextView).text.toString() == "已选 $count 项")
                check((view("task_bulk_all") as TextView).text.toString() == "取消全选")
            }
            click("task_bulk_all")
            main {
                check((view("task_selection_count") as TextView).text.toString() == "已选 0 项")
                check((view("task_bulk_all") as TextView).text.toString() == "全选")
                check(!view("task_bulk_delete").isEnabled)
            }
            click("task_bulk_done")
            await("task multi-selection completed") { find(activity.window.decorView, "task_selection_toolbar") == null }
            main {
                check(selectionBefore.all { (id, original) -> repository.get(id)?.let {
                    it.type == original.type && it.title == original.title && it.params == original.params && it.sourceUrl == original.sourceUrl
                } == true }) { "Selection-only actions changed or removed a task record" }
            }
            click("task_filter_ALL")
            assertFilter(false, TaskTypeFilter.ALL)
            report += "Filtered task select-all, deselect-all to zero and Done exit preserve every task record: PASS"

            main { list().setSelectionFromTop(12, list().paddingTop) }
            await("list scrolls beyond initial rows") { list().firstVisiblePosition >= 10 }
            val scrollAnchor = main { list().firstVisiblePosition to list().getChildAt(0).top }
            val visibleTask = main { list().adapter.getItem(list().firstVisiblePosition) as Task }
            main { repository.updateFromFake(listOf(current(visibleTask.id).copy(progress = .35f))) }
            instrumentation.waitForIdleSync()
            main {
                check(list().firstVisiblePosition == scrollAnchor.first && list().getChildAt(0).top == scrollAnchor.second) {
                    "Progress-only update moved the list"
                }
            }
            openTask(visibleTask.id)
            centerBack()
            await("detail return restores list viewport ${scrollAnchor.first}") {
                list().firstVisiblePosition == scrollAnchor.first && list().getChildAt(0)?.top == scrollAnchor.second
            }
            report += "Task center filters, canceled history, recycled rows and progress-only scroll stability: PASS"
            report += scrollFrames(instrumentation, activity, main { list() })

            openTask(pausedLive.id)
            assertActions(setOf("继续", "取消"))
            main { check(view("detail_progress").contentDescription.contains("46%")) }
            main {
                repository.updateFromFake(listOf(current(pausedLive.id).copy(status = TaskStatus.PARSING, progress = null)))
                check(view("detail_progress").contentDescription.contains("进度未知"))
                repository.pause(pausedLive.id)
            }
            assertActions(setOf("继续", "取消"))
            main {
                repository.updateFromFake(listOf(current(pausedLive.id).copy(status = TaskStatus.INTERRUPTED)))
            }
            assertActions(setOf("继续", "重试", "取消"))
            centerBack()
            openTask(failed.id)
            assertActions(setOf("重试"))
            main { check(hasText(view("route_TASK_DETAIL"), "测试编码失败")) }
            click("task_action_重试")
            main {
                check(current(failed.id).attempt == 1 && current(failed.id).progress == 0f)
                repository.pause(failed.id)
            }
            assertActions(setOf("继续", "取消"))
            centerBack()
            report += "Details show paused/unknown/failed/interrupted states and correct retry actions: PASS"

            click("task_segment_1")
            for (task in completed) {
                openTask(task.id)
                assertActions(setOf("打开", "分享", "删除"))
                main { check(view("detail_progress").contentDescription.contains("100%")) }
                click("task_action_打开")
                ready(when (task.type) {
                    TaskType.DOWNLOAD -> "PLAY_VIDEO"
                    TaskType.GIF -> "VIEW_GIF"
                    TaskType.LIVE_PHOTO -> "VIEW_LIVE"
                })
                click("route_back")
                ready("TASK_DETAIL")
                centerBack()
            }
            val previewFile = File.createTempFile("task-preview-", ".png", context.cacheDir)
            val previewTask = completed.first()
            val original = current(previewTask.id)
            try {
                val bitmap = android.graphics.Bitmap.createBitmap(12, 12, android.graphics.Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(android.graphics.Color.BLUE)
                    previewFile.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
                val address = android.net.Uri.fromFile(previewFile).toString()
                val picture = TaskOutput(address, "photo.png", TaskOutputKind.IMAGE)
                main { repository.updateFromFake(listOf(original.copy(outputs = listOf(picture,
                    TaskOutput("content://not-a-real-source/movie", "movie.mp4", TaskOutputKind.VIDEO))))) }
                openTask(previewTask.id)
                val tile = main { view("task_media_tile_0") as ViewGroup }
                await("saved album thumbnail decoded") {
                    val thumbnail = tile.getChildAt(0) as android.widget.ImageView
                    (thumbnail.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.getPixel(0, 0) == android.graphics.Color.BLUE
                }
                main { check(view("task_media_tile_1").contentDescription.contains("视频")) }
                click("task_media_tile_0")
                val deadline = SystemClock.uptimeMillis() + 5_000
                var displayed = false
                do {
                    val screen = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try { displayed = screen.getPixel(screen.width / 2, screen.height / 2) == android.graphics.Color.BLUE }
                    finally { screen.recycle() }
                    if (!displayed) SystemClock.sleep(100)
                } while (!displayed && SystemClock.uptimeMillis() < deadline)
                check(displayed) { "Full-screen task image did not render" }
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                ready("TASK_DETAIL")
                centerBack()
                report += "Completed album thumbnails and full-screen local photo: PASS"
            } finally {
                main { repository.updateFromFake(listOf(original)) }
                previewFile.delete()
            }
            main {
                check(owned.all { current(it).outputUri == null }) { "Fake engine created a real output URI" }
                check(DownloadService.activeDownloadCount == serviceCounts.first &&
                    DownloadService.activeConversionCount == serviceCounts.second) { "Fake actions started a real service task" }
                check(repository.persistenceError == null) { "Task persistence failed: ${repository.persistenceError}" }
            }
            report += "Completed download/GIF/live tasks open their viewer routes; no real download/conversion task or output: PASS"
            report += databaseRecovery(context)
            return report.joinToString("\n")
        } finally {
            main {
                owned.forEach(repository::delete)
                if (!activity.isFinishing) activity.finish()
            }
        }
    }

    private fun scrollFrames(instrumentation: Instrumentation, activity: Activity, list: ListView): String {
        val thread = HandlerThread("task-scroll-metrics").apply { start() }
        val samples = Collections.synchronizedList(mutableListOf<Long>())
        val listener = Window.OnFrameMetricsAvailableListener { _, frame, _ ->
            val duration = frame.getMetric(FrameMetrics.TOTAL_DURATION)
            if (duration > 0) samples += duration
        }
        var refreshRate = 0f
        try {
            instrumentation.runOnMainSync {
                @Suppress("DEPRECATION")
                refreshRate = activity.windowManager.defaultDisplay.refreshRate
                activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
                list.smoothScrollBy((activity.resources.displayMetrics.density * 1_400).toInt(), 900)
            }
            SystemClock.sleep(1_000)
        } finally {
            instrumentation.runOnMainSync { activity.window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
            thread.join(500)
        }
        val sorted = synchronized(samples) { samples.sorted() }
        check(sorted.size >= 3) { "No meaningful frame samples were captured while scrolling" }
        val p95Ms = sorted[((sorted.size - 1) * .95).toInt()] / 1_000_000.0
        check(p95Ms < 200) { "Repeated long scroll stalls: p95=$p95Ms ms" }
        return String.format(Locale.US, "Recycled-list rapid scroll: %d frames, p95 TOTAL_DURATION %.1f ms, display %.0f Hz (instrumented): PASS",
            sorted.size, p95Ms, refreshRate)
    }

    private fun databaseRecovery(context: Context): String {
        val folder = File(context.cacheDir, "task-db-smoke-${System.nanoTime()}")
        check(folder.mkdirs())
        val isolated = object : ContextWrapper(context) {
            override fun getDatabasePath(name: String) = File(folder, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?) =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath, factory, errorHandler)
        }
        try {
            val original = TaskStatus.entries.map { status ->
                Task("database-$status", TaskType.GIF, "数据库 $status", status = status, progress = .47f,
                    errorMessage = if (status == TaskStatus.FAILED) "保留失败原因" else null,
                    params = "{\"fps\":15}", attempt = 2)
            }
            TaskDatabase(isolated).use { db -> db.save(original.map { StoredTask(it, listOf("测试日志")) }) }
            val restored = TaskDatabase(isolated).use { it.loadAndRestore() }
            for (task in original) {
                val stored = restored.single { it.task.id == task.id }
                check(stored.task == TaskTransitions.interruptedOnRestart(task)) { "Persisted state lost: ${task.status}" }
                check(stored.logs.first() == "测试日志")
            }
            val secondLoad = TaskDatabase(isolated).use { it.loadAndRestore() }
            check(restored == secondLoad) { "Repeated restoration duplicated logs or changed completed/failed history" }
            return "Isolated SQLite close/reopen preserves fields/logs, marks unfinished tasks interrupted, and keeps completed/failed history: PASS"
        } finally {
            folder.deleteRecursively()
        }
    }

    private fun find(view: View, tag: String): View? {
        if (!view.isShown) return null
        if (view.tag == tag) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index), tag)?.let { return it }
        return null
    }

    private fun visit(view: View, action: (View) -> Unit) {
        action(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index), action)
    }

    private fun hasText(view: View, text: String): Boolean {
        var found = false
        visit(view) { if (it.isShown && it is TextView && it.text.contains(text)) found = true }
        return found
    }
}
