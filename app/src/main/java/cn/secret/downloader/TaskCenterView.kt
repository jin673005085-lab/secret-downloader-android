package cn.secret.downloader

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Parcelable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.Closeable

/** A recycled list observes the same repository as the home card and task detail. */
class TaskCenterView(
    context: Context,
    private val theme: UiTheme,
    private val repository: TaskRepository,
    private val onTaskClick: (Task) -> Unit,
    private val onOpen: (Task) -> Unit,
) : LinearLayout(context) {
    internal var activeDialog: AlertDialog? = null
        private set
    private var completed = false
    private var type = TaskTypeFilter.ALL
    private var observation: Closeable? = null
    private var allTasks = repository.tasks
    private var rows = emptyList<Task>()
    private var selecting = false
    private val selected = linkedSetOf<String>()
    private var coveredViewport: Pair<String, Int>? = null
    private val segmentButtons = mutableListOf<TextView>()
    private val typeButtons = linkedMapOf<TaskTypeFilter, TextView>()
    private val taskAdapter = TaskAdapter()
    private val list = ListView(context).apply {
        id = R.id.task_center_list
        tag = "task_center_list"
        adapter = taskAdapter
        divider = ColorDrawable(theme.background)
        dividerHeight = theme.dp(10)
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        setPadding(0, theme.dp(12), 0, theme.dp(20))
    }
    private val emptyLabel = text("", 14f).apply { gravity = Gravity.CENTER }
    private val selectionCount = text("", 14f, true).apply {
        tag = "task_selection_count"
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val selectAll = selectionButton("全选", "task_bulk_all") { toggleAll() }
    private val deleteSelected = selectionButton("删除记录", "task_bulk_delete") { confirmDelete() }
    private val selectionBar = LinearLayout(context).apply {
        tag = "task_selection_toolbar"
        orientation = VERTICAL
        visibility = GONE
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(selectionCount, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(selectionButton("完成", "task_bulk_done") { exitSelection() }, LayoutParams(theme.dp(72), theme.dp(48)))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(LinearLayout(context).apply {
            addView(selectAll, LayoutParams(0, theme.dp(48), 1f))
            addView(deleteSelected, LayoutParams(0, theme.dp(48), 1f).apply { marginStart = theme.dp(8) })
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = theme.dp(6) })
    }

    init {
        id = R.id.task_center_content
        orientation = VERTICAL
        setPadding(theme.dp(20), 0, theme.dp(20), 0)
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = theme.shape(theme.muted, 18)
            setPadding(theme.dp(4), theme.dp(4), theme.dp(4), theme.dp(4))
            listOf("进行中", "已完成").forEachIndexed { index, title ->
                val button = text(title, 14f, true).apply {
                    tag = "task_segment_$index"
                    gravity = Gravity.CENTER
                    isFocusable = true
                    setOnClickListener {
                        if (completed != (index == 1)) {
                            completed = index == 1
                            updateFilters()
                            render(allTasks, resetScroll = true)
                        }
                    }
                    UiMotion.press(this)
                }
                segmentButtons += button
                addView(button, LayoutParams(0, theme.dp(48), 1f))
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, theme.dp(56)))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            listOf("全部" to TaskTypeFilter.ALL, "下载" to TaskTypeFilter.DOWNLOAD,
                "转换" to TaskTypeFilter.CONVERSION).forEachIndexed { index, (title, filter) ->
                val button = text(title, 12f).apply {
                    tag = "task_filter_${filter.name}"
                    gravity = Gravity.CENTER
                    isFocusable = true
                    setOnClickListener {
                        if (type != filter) {
                            type = filter
                            updateFilters()
                            render(allTasks, resetScroll = true)
                        }
                    }
                    UiMotion.press(this)
                }
                typeButtons[filter] = button
                addView(button, LayoutParams(0, theme.dp(48), 1f).apply {
                    if (index > 0) marginStart = theme.dp(8)
                })
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = theme.dp(12) })
        addView(selectionBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = theme.dp(8) })
        val empty = LinearLayout(context).apply {
            tag = "task_center_empty"
            orientation = VERTICAL
            gravity = Gravity.CENTER
            addView(text("☷", 50f).apply { gravity = Gravity.CENTER; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO },
                LayoutParams(LayoutParams.MATCH_PARENT, theme.dp(70)))
            addView(emptyLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(FrameLayout(context).apply {
            addView(list, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(empty, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            list.emptyView = empty
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        updateFilters()
        render(allTasks)
    }

    private fun updateFilters() {
        segmentButtons.forEachIndexed { index, button ->
            val selected = completed == (index == 1)
            button.isSelected = selected
            button.setTextColor(if (selected) theme.primaryText else theme.secondary)
            button.background = theme.control(if (selected) theme.surface else theme.muted, 14)
        }
        typeButtons.forEach { (filter, button) ->
            val selected = filter == type
            button.isSelected = selected
            button.setTextColor(if (selected) theme.primaryText else theme.secondary)
            button.background = theme.control(if (selected) theme.primarySoft else theme.muted, 12)
        }
        emptyLabel.text = if (completed) "还没有已完成的任务" else "还没有进行中的任务"
    }

    fun filterDownloads() {
        completed = false
        type = TaskTypeFilter.DOWNLOAD
        updateFilters()
        render(allTasks, resetScroll = true)
    }

    fun filterConversions() {
        completed = false; type = TaskTypeFilter.CONVERSION
        updateFilters(); render(allTasks, resetScroll = true)
    }

    private fun render(tasks: List<Task>, resetScroll: Boolean = false) {
        allTasks = tasks
        val next = TaskUi.filter(tasks, completed, type)
        if (repository.isLoaded || tasks.isNotEmpty()) selected.retainAll(next.mapTo(hashSetOf()) { it.id })
        val sameOrder = rows.size == next.size && rows.indices.all { rows[it].id == next[it].id }
        if (sameOrder) {
            rows = next
            for (index in 0 until list.childCount) {
                val position = list.firstVisiblePosition + index
                rows.getOrNull(position)?.let { (list.getChildAt(index) as? TaskRow)?.bind(it) }
            }
        } else {
            val first = list.firstVisiblePosition
            val firstId = rows.getOrNull(first)?.id
            val top = list.getChildAt(0)?.top ?: list.paddingTop
            rows = next
            taskAdapter.notifyDataSetChanged()
            if (resetScroll) list.setSelection(0) else if (next.isNotEmpty()) {
                val anchor = next.indexOfFirst { it.id == firstId }.takeIf { it >= 0 }
                    ?: first.coerceIn(next.indices)
                list.setSelectionFromTop(anchor, top - list.paddingTop)
            }
        }
        updateSelection()
        if (resetScroll && sameOrder) list.setSelection(0)
    }

    private fun toggle(task: Task) {
        selecting = true
        if (!selected.add(task.id)) selected.remove(task.id)
        updateSelection()
        refreshSelection()
    }

    private fun toggleAll() {
        if (rows.isNotEmpty() && rows.all { it.id in selected }) selected.clear()
        else selected.addAll(rows.map { it.id })
        updateSelection()
        refreshSelection()
    }

    fun exitSelection(): Boolean {
        if (!selecting) return false
        selecting = false
        selected.clear()
        updateSelection()
        refreshSelection()
        return true
    }

    private fun refreshSelection() {
        for (index in 0 until list.childCount) {
            rows.getOrNull(list.firstVisiblePosition + index)?.let { (list.getChildAt(index) as? TaskRow)?.bind(it) }
        }
    }

    private fun updateSelection() {
        selectionBar.visibility = if (selecting) VISIBLE else GONE
        selectionCount.text = "已选 ${selected.size} 项"
        val allSelected = rows.isNotEmpty() && rows.all { it.id in selected }
        selectAll.text = if (allSelected) "取消全选" else "全选"
        selectAll.contentDescription = "${selectAll.text}当前筛选的 ${rows.size} 个任务"
        selectAll.isEnabled = rows.isNotEmpty()
        deleteSelected.isEnabled = selected.isNotEmpty()
        deleteSelected.alpha = if (deleteSelected.isEnabled) 1f else .45f
        deleteSelected.contentDescription = "删除所选 ${selected.size} 条任务记录"
    }

    private fun confirmDelete() {
        val ids = rows.filter { it.id in selected }.map { it.id }
        if (ids.isEmpty()) return
        val dialog = AlertDialog.Builder(context).setTitle("删除 ${ids.size} 条任务记录？")
            .setMessage("仅删除任务记录，已保存的视频和作品会保留。未完成的任务将停止，并清理临时文件。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除记录") { _, _ ->
                ids.forEach(repository::delete)
                exitSelection()
            }.create()
        activeDialog?.dismiss()
        activeDialog = dialog
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
        dialog.show()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observation = repository.observeAll { render(it) }
    }

    override fun onDetachedFromWindow() {
        activeDialog?.dismiss()
        observation?.close()
        observation = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) {
            rows.getOrNull(list.firstVisiblePosition)?.let { task ->
                list.getChildAt(0)?.let { coveredViewport = task.id to (it.top - list.paddingTop) }
            }
        } else coveredViewport?.let { (id, top) ->
            list.post {
                if (isShown) {
                    val index = rows.indexOfFirst { it.id == id }
                    if (index >= 0) list.setSelectionFromTop(index, top)
                    coveredViewport = null
                }
            }
        }
    }

    override fun onSaveInstanceState(): Parcelable = Bundle().apply {
        putParcelable("super", super.onSaveInstanceState())
        putBoolean("completed", completed)
        putString("type", type.name)
        putBoolean("selecting", selecting)
        putStringArrayList("selected", ArrayList(selected))
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is Bundle) { super.onRestoreInstanceState(state); return }
        @Suppress("DEPRECATION")
        super.onRestoreInstanceState(state.getParcelable("super"))
        completed = state.getBoolean("completed")
        type = state.getString("type")?.let { name -> TaskTypeFilter.entries.firstOrNull { it.name == name } }
            ?: TaskTypeFilter.ALL
        selecting = state.getBoolean("selecting")
        selected.clear()
        selected.addAll(state.getStringArrayList("selected").orEmpty())
        updateFilters()
        render(repository.tasks)
    }

    private inner class TaskAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        // Stable across recreation so ListView can restore its first visible task.
        override fun getItemId(position: Int) = rows[position].id.fold(1_125_899_906_842_597L) { hash, char ->
            31 * hash + char.code
        }
        override fun hasStableIds() = true
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (convertView as? TaskRow ?: TaskRow()).apply { bind(rows[position]) }
    }

    private inner class TaskRow : LinearLayout(context) {
        private var task: Task? = null
        private val cover = text("", 19f, true).apply {
            gravity = Gravity.CENTER
            background = theme.shape(theme.muted, 15)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val title = text("", 14f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        private val status = text("", 11f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        private val progress = JellyProgressBar(context, theme, 6)
        private val action = text("", 22f, true).apply {
            gravity = Gravity.CENTER
            background = theme.control(theme.primarySoft, UiTheme.PILL_RADIUS)
            isFocusable = true
            setOnClickListener {
                task?.let { current ->
                    UiMotion.haptic(this)
                    if (selecting) { toggle(current); return@setOnClickListener }
                    when (current.status) {
                        TaskStatus.QUEUED, TaskStatus.PARSING, TaskStatus.RUNNING -> repository.pause(current.id)
                        TaskStatus.PAUSED, TaskStatus.INTERRUPTED -> repository.resume(current.id)
                        TaskStatus.FAILED, TaskStatus.CANCELED -> repository.retry(current.id)
                        TaskStatus.COMPLETED -> onOpen(current)
                    }
                }
            }
            UiMotion.press(this)
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = theme.control(theme.surface, 22)
            setPadding(theme.dp(14), theme.dp(14), theme.dp(10), theme.dp(14))
            isFocusable = true
            setOnClickListener { task?.let { if (selecting) toggle(it) else onTaskClick(it) } }
            setOnLongClickListener { task?.let(::toggle); UiMotion.haptic(this); true }
            UiMotion.press(this)
            addView(cover, LayoutParams(theme.dp(56), theme.dp(56)))
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = theme.dp(4) })
                addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, theme.dp(10)).apply { topMargin = theme.dp(5) })
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = theme.dp(12); marginEnd = theme.dp(8) })
            addView(action, LayoutParams(theme.dp(48), theme.dp(48)))
        }

        fun bind(value: Task) {
            val previous = task
            val checked = selecting && value.id in selected
            if (previous == value && action.isSelected == selecting && isSelected == checked) return
            task = value
            isSelected = checked
            action.isSelected = selecting
            background = theme.control(if (checked) theme.primarySoft else theme.surface, 22)
            if (previous?.id != value.id) {
                tag = "task_row_${value.id}"
                cover.tag = "task_cover_${value.id}"
                progress.tag = "task_progress_${value.id}"
                action.tag = "task_action_${value.id}"
            }
            if (previous?.type != value.type) {
                cover.setTextIfChanged(when (value.type) {
                    TaskType.DOWNLOAD -> "↓"
                    TaskType.LIVE_PHOTO -> "◉"
                    TaskType.GIF -> "GIF"
                })
            }
            title.setTextIfChanged(value.title)
            val summary = TaskUi.summary(value)
            status.setTextIfChanged(summary)
            progress.bind(value)
            if (previous?.status != value.status) {
                status.setTextColor(TaskUi.color(value, theme))
                action.setTextColor(TaskUi.color(value, theme))
            }
            if (selecting) {
                action.setTextIfChanged(if (checked) "✓" else "○")
                action.setTextColor(theme.primaryText)
                action.contentDescription = "${if (checked) "取消选择" else "选择"}：${value.title}"
            } else {
                val (symbol, description) = when (value.status) {
                    TaskStatus.QUEUED, TaskStatus.PARSING, TaskStatus.RUNNING -> "Ⅱ" to "暂停"
                    TaskStatus.PAUSED, TaskStatus.INTERRUPTED -> "▶" to "继续"
                    TaskStatus.FAILED, TaskStatus.CANCELED -> "↻" to "重试"
                    TaskStatus.COMPLETED -> "↗" to "打开"
                }
                action.setTextIfChanged(symbol)
                action.setTextColor(TaskUi.color(value, theme))
                action.contentDescription = "$description：${value.title}"
            }
            contentDescription = "${value.title}，${TaskUi.typeLabel(value.type)}，$summary" +
                if (selecting) "，${if (checked) "已选择" else "未选择"}" else "，长按多选"
        }
    }

    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        setTextColor(if (bold) theme.text else theme.secondary)
    }

    private fun selectionButton(label: String, tagValue: String, click: () -> Unit) = text(label, 15f, true).apply {
        tag = tagValue
        gravity = Gravity.CENTER
        minHeight = theme.dp(48)
        minWidth = theme.dp(48)
        setPadding(theme.dp(12), theme.dp(8), theme.dp(12), theme.dp(8))
        setTextColor(theme.primaryText)
        background = theme.control(theme.primarySoft, 14)
        isFocusable = true
        setOnClickListener { UiMotion.haptic(this); click() }
        UiMotion.press(this)
    }
}

private fun TextView.setTextIfChanged(value: CharSequence) {
    if (!TextUtils.equals(text, value)) text = value
}
