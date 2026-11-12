package cn.secret.downloader

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.os.Parcelable
import android.text.TextUtils
import android.util.SparseArray
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.Closeable

internal enum class WorkFilter { ALL, GIF, LIVE, FAVORITE }
internal enum class WorkSort { CREATED, SIZE }
internal object WorkQueries {
    fun select(items: List<MediaItem>, filter: WorkFilter, sort: WorkSort): List<MediaItem> = items.filter {
        it.type != MediaType.VIDEO && when (filter) {
            WorkFilter.ALL -> true
            WorkFilter.GIF -> it.type == MediaType.GIF
            WorkFilter.LIVE -> it.type == MediaType.LIVE_PHOTO
            WorkFilter.FAVORITE -> it.favorite
        }
    }.sortedWith((if (sort == WorkSort.CREATED) compareByDescending<MediaItem> { it.downloadedAt }
        else compareByDescending { it.sizeBytes }).thenBy { it.id })
}

/** The creative library observes saved works and can refresh the app's own output folders. */
class ConversionLibraryView(
    context: Context,
    private val theme: UiTheme,
    private val media: MediaRepository,
    private val tasks: TaskRepository,
    private val onCreate: (Boolean, View?) -> Unit,
    private val onTaskCenter: () -> Unit,
    private val onOpen: (MediaItem, List<MediaItem>, View?) -> Unit,
    private val onSettings: () -> Unit,
    private val scanOnAttach: Boolean = false,
) : FrameLayout(context) {
    internal var activeDialog: AlertDialog? = null
        private set
    private var observation: Closeable? = null
    private var taskObservation: Closeable? = null
    private var filter = WorkFilter.ALL
    private var sort = WorkSort.CREATED
    private var records = emptyList<MediaItem>()
    private val selected = linkedSetOf<String>()
    private var selecting = false
    private var receivedMedia = media.items.isNotEmpty()
    private val layout = GridLayoutManager(context, 3)
    private val adapter = WorkAdapter()
    private val springs = mutableMapOf<View, List<SpringAnimation>>()
    private var pendingMove: ViewTreeObserver.OnPreDrawListener? = null
    private val grid = RecyclerView(context).apply {
        id = R.id.conversion_work_grid; tag = "work_grid"
        layoutManager = layout; adapter = this@ConversionLibraryView.adapter
        itemAnimator = null
        clipToPadding = false; setPadding(theme.dp(12), theme.dp(4), theme.dp(12), theme.dp(16))
        addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(out: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                out.set(theme.dp(4), theme.dp(4), theme.dp(4), theme.dp(4))
            }
        })
    }
    private val filters = mutableListOf<TextView>()
    private val filterPill = View(context).apply { background = theme.shape(theme.primarySoft, 12) }
    private val filterSpring = SpringAnimation(filterPill, DynamicAnimation.TRANSLATION_X).apply {
        spring = SpringForce(0f).apply { dampingRatio = .78f; stiffness = 400f }
    }
    private val bannerText = label("", 13f, true).apply { setTextColor(theme.primaryText) }
    private val bannerProgress = JellyProgressBar(context, theme, 6)
    private val banner = LinearLayout(context).apply {
        tag = "work_conversion_banner"; orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
        background = theme.control(theme.primarySoft, 14); visibility = GONE
        setPadding(theme.dp(14), 0, theme.dp(14), 0)
        addView(bannerText); addView(bannerProgress, LinearLayout.LayoutParams(-1, theme.dp(12)))
        setOnClickListener { onTaskCenter() }; UiMotion.press(this)
    }
    private val selectionText = label("", 14f, true).apply { tag = "work_selection_count" }
    private val selectAll = action("全选", "work_bulk_all") { toggleAll() }
    private val bulkFavorite = action("收藏", "work_bulk_favorite") { selected.toList().forEach { media.setFavorite(it, true) } }
    private val bulkDelete = action("删除", "work_bulk_delete") { confirmDelete(selected.mapNotNull(media::get)) }
    private val selection = LinearLayout(context).apply {
        tag = "work_selection_toolbar"; orientation = LinearLayout.VERTICAL
        setPadding(theme.dp(16), 0, theme.dp(16), theme.dp(8)); visibility = GONE
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(selectionText, LinearLayout.LayoutParams(0, -2, 1f))
            addView(action("完成", "work_bulk_done") { leaveSelection() }, LinearLayout.LayoutParams(theme.dp(72), theme.dp(48)))
        }, LinearLayout.LayoutParams(-1, -2))
        addView(LinearLayout(context).apply {
            listOf(selectAll, bulkFavorite, bulkDelete).forEachIndexed { index, button ->
                addView(button, LinearLayout.LayoutParams(0, theme.dp(48), 1f).apply { if (index > 0) marginStart = theme.dp(8) })
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })
    }
    private val empty = LinearLayout(context).apply {
        tag = "work_empty"; orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        setPadding(theme.dp(20), theme.dp(16), theme.dp(20), theme.dp(16))
        addView(label("✧", 52f).apply { gravity = Gravity.CENTER; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO })
        addView(label("还没有作品，选一个视频试试吧", 16f, true).apply { gravity = Gravity.CENTER }, gap(10))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(action("制作 GIF", "work_empty_gif") { onCreate(true, null) })
            addView(action("制作实况", "work_empty_live") { onCreate(false, null) })
        }, gap(16))
    }

    init {
        id = R.id.conversion_library_view; tag = "tab_convert"
        setBackgroundColor(theme.background)
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.dp(16), theme.dp(14), theme.dp(12), theme.dp(6))
            addView(label("转换", 30f, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(action("排序", "work_sort") { showSort() }.apply { contentDescription = "作品排序" },
                LinearLayout.LayoutParams(theme.dp(72), theme.dp(48)).apply { marginEnd = theme.dp(8) })
            addView(ImageButton(context).apply {
                tag = "open_SETTINGS"; setImageResource(R.drawable.ic_settings)
                background = theme.control(theme.surface, 100); backgroundTintList = null
                contentDescription = "自定义下载设置"; setOnClickListener { onSettings() }; UiMotion.press(this)
            }, LinearLayout.LayoutParams(theme.dp(48), theme.dp(48)))
        })
        column.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(creator(true), LinearLayout.LayoutParams(0, theme.dp(88), 1f).apply { marginEnd = theme.dp(8) })
            addView(creator(false), LinearLayout.LayoutParams(0, theme.dp(88), 1f))
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(theme.dp(16), theme.dp(12), theme.dp(16), 0) })
        column.addView(banner, LinearLayout.LayoutParams(-1, theme.dp(58)).apply { setMargins(theme.dp(16), theme.dp(12), theme.dp(16), 0) })
        column.addView(FrameLayout(context).apply {
            addView(filterPill, LayoutParams(0, -1))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf("全部", "GIF", "实况", "收藏").forEachIndexed { index, text ->
                    val item = label(text, 13f, true).apply {
                        tag = "work_filter_${WorkFilter.entries[index].name}"; gravity = Gravity.CENTER; isFocusable = true
                        setOnClickListener { filter = WorkFilter.entries[index]; updateFilters(); reload(true) }
                        UiMotion.press(this)
                    }
                    filters += item; addView(item, LinearLayout.LayoutParams(0, -1, 1f))
                }
            }, LayoutParams(-1, -1))
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateFilters(false) }
        }, LinearLayout.LayoutParams(-1, theme.dp(48)).apply { setMargins(theme.dp(16), theme.dp(16), theme.dp(16), theme.dp(4)) })
        column.addView(selection, LinearLayout.LayoutParams(-1, -2))
        column.addView(MediaRefreshLayout(context, theme, media, scanOnAttach).apply {
            content(grid, empty)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(column, LayoutParams(-1, -1))
        updateFilters(false); reload(false)
    }

    private fun creator(gif: Boolean) = LinearLayout(context).apply {
        tag = if (gif) "open_CREATE_GIF" else "open_CREATE_LIVE"
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = theme.control(theme.surface, 18); setPadding(theme.dp(12), 0, theme.dp(8), 0)
        val icon = label(if (gif) "GIF" else "◎", if (gif) 14f else 30f, true).apply {
            gravity = Gravity.CENTER; setTextColor(theme.primaryText); background = theme.shape(theme.primarySoft, 14)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(icon, LinearLayout.LayoutParams(theme.dp(42), theme.dp(48)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(if (gif) "视频转 GIF" else "视频转实况", 13f, true))
            addView(label(if (gif) "留下循环的瞬间" else "让封面动起来", 10f), gap(5))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = theme.dp(8) })
        isFocusable = true; setOnClickListener { onCreate(gif, icon) }; UiMotion.press(this)
    }

    fun scrollToTop() {
        if (records.isNotEmpty()) { if (motionsEnabled()) grid.smoothScrollToPosition(0) else grid.scrollToPosition(0) }
    }
    fun exitSelection(): Boolean = if (selecting) { leaveSelection(); true } else false

    private fun updateFilters(animate: Boolean = true) {
        filters.forEachIndexed { index, item ->
            item.isSelected = filter.ordinal == index
            item.setTextColor(if (item.isSelected) theme.primaryText else theme.secondary)
        }
        val target = filters[filter.ordinal]
        if (target.width > 0) {
            if (filterPill.layoutParams.width != target.width) filterPill.layoutParams = filterPill.layoutParams.apply { width = target.width }
            if (animate && motionsEnabled()) filterSpring.animateToFinalPosition(target.left.toFloat())
            else { filterSpring.cancel(); filterPill.translationX = target.left.toFloat() }
        }
    }

    private fun reload(resetScroll: Boolean) {
        val next = WorkQueries.select(media.items, filter, sort)
        if (next == records && !resetScroll) { updateSelection(); return }
        val origins = (0 until grid.childCount).mapNotNull { index ->
            val view = grid.getChildAt(index)
            val position = grid.getChildAdapterPosition(view)
            if (position !in records.indices) return@mapNotNull null
            adapter.getItemId(position) to
                (view.left + view.translationX to view.top + view.translationY)
        }.toMap()
        clearMoves()
        val previous = records
        val changes = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = previous.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(old: Int, new: Int) = previous[old].id == next[new].id
            override fun areContentsTheSame(old: Int, new: Int) = previous[old] == next[new]
        })
        records = next; changes.dispatchUpdatesTo(adapter)
        if (media.isLoaded || receivedMedia || media.items.isNotEmpty()) { receivedMedia = true; selected.retainAll(next.mapTo(hashSetOf()) { it.id }) }
        empty.visibility = if (next.isEmpty()) VISIBLE else GONE; grid.visibility = if (next.isEmpty()) GONE else VISIBLE
        updateSelection()
        if (resetScroll && next.isNotEmpty()) grid.scrollToPosition(0)
        if (origins.isNotEmpty() && motionsEnabled()) {
            pendingMove = object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    grid.viewTreeObserver.removeOnPreDrawListener(this); pendingMove = null
                    if (!motionsEnabled()) return true
                    for (index in 0 until grid.childCount) {
                        val view = grid.getChildAt(index)
                        val position = grid.getChildAdapterPosition(view)
                        if (position !in records.indices) continue
                        val from = origins[adapter.getItemId(position)] ?: continue
                        view.translationX = from.first - view.left; view.translationY = from.second - view.top
                        val moving = listOf(DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y).map { property ->
                            SpringAnimation(view, property, 0f).apply { spring = SpringForce(0f).apply { dampingRatio = .82f; stiffness = 420f } }
                        }
                        springs[view] = moving; moving.forEach { it.start() }
                    }
                    return true
                }
            }.also { grid.viewTreeObserver.addOnPreDrawListener(it) }
        }
    }

    private fun clearMoves() {
        pendingMove?.let { if (grid.viewTreeObserver.isAlive) grid.viewTreeObserver.removeOnPreDrawListener(it) }; pendingMove = null
        springs.keys.toList().forEach(::cancelMove)
    }
    private fun cancelMove(view: View) {
        springs.remove(view)?.forEach { it.cancel() }; view.translationX = 0f; view.translationY = 0f
    }
    private fun toggle(item: MediaItem) {
        selecting = true
        if (!selected.add(item.id)) selected.remove(item.id)
        updateSelection(); adapter.notifyItemRangeChanged(0, records.size, "selection")
    }
    private fun leaveSelection() {
        selecting = false; selected.clear(); updateSelection(); adapter.notifyItemRangeChanged(0, records.size, "selection")
    }
    private fun toggleAll() {
        val ids = records.map { it.id }
        if (ids.isNotEmpty() && selected.containsAll(ids)) selected.clear() else selected.addAll(ids)
        updateSelection(); adapter.notifyItemRangeChanged(0, records.size, "selection")
    }
    private fun updateSelection() {
        selection.visibility = if (selecting) VISIBLE else GONE; selectionText.text = "已选 ${selected.size} 项"
        selectAll.text = if (records.isNotEmpty() && selected.size == records.size) "取消全选" else "全选"
        selectAll.contentDescription = "${selectAll.text}当前筛选的 ${records.size} 项作品"
        selectAll.isEnabled = records.isNotEmpty()
        bulkFavorite.isEnabled = selected.isNotEmpty(); bulkDelete.isEnabled = selected.isNotEmpty()
        listOf(selectAll, bulkFavorite, bulkDelete).forEach { it.alpha = if (it.isEnabled) 1f else .4f }
    }
    private fun open(item: MediaItem, cover: View) {
        if (!item.exists) {
            show(AlertDialog.Builder(context).setTitle("文件不存在").setMessage("文件可能已被删除或移动。")
                .setNegativeButton("取消", null).setPositiveButton("移除记录") { _, _ -> media.delete(item.id) }.create())
        } else onOpen(item, records.toList(), cover)
    }
    private fun showSort() {
        show(AlertDialog.Builder(context).setTitle("作品排序").setSingleChoiceItems(arrayOf("最近创建", "文件大小"), sort.ordinal) { dialog, index ->
            sort = WorkSort.entries[index]; reload(true); dialog.dismiss()
        }.setNegativeButton("取消", null).create())
    }
    private fun confirmDelete(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val deleteFile = CheckBox(context).apply {
            tag = "work_delete_file"; text = "同时删除本地文件"; isEnabled = items.all(MediaDeletion::canDeleteFile)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(theme.dp(24), theme.dp(8), theme.dp(24), 0)
            addView(label("移除 ${items.size} 条作品记录？", 15f))
            addView(deleteFile)
            addView(label(if (deleteFile.isEnabled) "勾选后会删除本地文件；未勾选只移除记录。" else "部分文件暂不支持直接删除，只会移除记录。", 12f))
        }
        show(AlertDialog.Builder(context).setTitle("删除作品").setView(content).setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                MediaDeletion.delete(context, media, items, deleteFile.isChecked) { failed ->
                    selected.removeAll(items.filter { media.get(it.id) == null }.map { it.id }.toSet())
                    if (selecting && selected.isEmpty()) leaveSelection()
                    if (failed > 0) Toast.makeText(context, "$failed 个文件未能删除，记录已保留", Toast.LENGTH_LONG).show()
                }
            }.create())
    }
    private fun show(dialog: AlertDialog) {
        activeDialog?.dismiss(); activeDialog = dialog
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }; dialog.show()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observation = media.observeAll { reload(false) }
        taskObservation = tasks.observeAll { values ->
            val active = values.filter { it.type != TaskType.DOWNLOAD && (it.status == TaskStatus.QUEUED || it.status == TaskStatus.PARSING || it.status == TaskStatus.RUNNING) }
            banner.visibility = if (active.isEmpty()) GONE else VISIBLE
            val title = "${active.size} 个作品转换中  ›"
            if (bannerText.text != title) bannerText.text = title
            if (active.isNotEmpty()) bannerProgress.setProgress(if (active.any { it.progress == null }) null else
                active.map { it.progress!! }.average().toFloat(), TaskStatus.RUNNING, active.joinToString { "${it.id}:${it.attempt}" })
        }
    }
    override fun onDetachedFromWindow() {
        observation?.close(); observation = null; taskObservation?.close(); taskObservation = null
        clearMoves(); filterSpring.cancel(); activeDialog?.dismiss(); super.onDetachedFromWindow()
    }
    override fun dispatchSaveInstanceState(container: SparseArray<Parcelable>) = dispatchFreezeSelfOnly(container)
    override fun dispatchRestoreInstanceState(container: SparseArray<Parcelable>) = dispatchThawSelfOnly(container)
    override fun onSaveInstanceState(): Parcelable = Bundle().apply {
        putParcelable("super", super.onSaveInstanceState()); putParcelable("grid", layout.onSaveInstanceState())
        putString("filter", filter.name); putString("sort", sort.name); putBoolean("selecting", selecting)
        putStringArrayList("selected", ArrayList(selected))
    }
    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is Bundle) { super.onRestoreInstanceState(state); return }
        @Suppress("DEPRECATION") super.onRestoreInstanceState(state.getParcelable("super"))
        filter = WorkFilter.entries.firstOrNull { it.name == state.getString("filter") } ?: WorkFilter.ALL
        sort = WorkSort.entries.firstOrNull { it.name == state.getString("sort") } ?: WorkSort.CREATED
        selecting = state.getBoolean("selecting"); selected.clear(); selected.addAll(state.getStringArrayList("selected").orEmpty())
        updateFilters(false); reload(false)
        adapter.notifyItemRangeChanged(0, records.size, "selection")
        @Suppress("DEPRECATION") val saved = state.getParcelable<Parcelable>("grid")
        if (saved != null) layout.onRestoreInstanceState(saved)
    }

    private inner class WorkAdapter : RecyclerView.Adapter<WorkHolder>() {
        private val appeared = hashSetOf<String>()
        init { setHasStableIds(true) }
        override fun getItemCount() = records.size
        override fun getItemId(position: Int) = VideoLibraryUi.stableId(records[position].id)
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = WorkHolder()
        override fun onBindViewHolder(holder: WorkHolder, position: Int) = holder.bind(records[position])
        override fun onViewAttachedToWindow(holder: WorkHolder) {
            val index = holder.bindingAdapterPosition
            if (index in records.indices && appeared.add(records[index].id)) UiMotion.enter(holder.content, index % 3)
        }
        override fun onViewRecycled(holder: WorkHolder) { cancelMove(holder.itemView); holder.recycle() }
    }
    private inner class Square : FrameLayout(context) {
        override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(w), MeasureSpec.EXACTLY))
    }
    private inner class LiveGlyph : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = theme.dp(1).toFloat(); color = Color.WHITE }
        override fun onDraw(canvas: Canvas) {
            val radius = minOf(width, height) * .38f
            canvas.drawCircle(width / 2f, height / 2f, radius, paint)
            canvas.drawCircle(width / 2f, height / 2f, radius * .68f, paint)
            canvas.drawCircle(width / 2f, height / 2f, radius * .32f, paint)
        }
    }
    private inner class WorkHolder : RecyclerView.ViewHolder(LinearLayout(context)) {
        val root = itemView as LinearLayout
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private var current: MediaItem? = null
        private val cover = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val title = label("", 11f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        private val detail = label("", 10f).apply { maxLines = 1 }
        private val gif = label("GIF", 10f, true).apply {
            setTextColor(Color.WHITE); background = theme.shape(0x99000000.toInt(), 6)
            setPadding(theme.dp(5), theme.dp(2), theme.dp(5), theme.dp(2))
        }
        private val live = LiveGlyph().apply { background = theme.shape(0x55000000, 100); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        private val check = label("✓", 15f, true).apply { gravity = Gravity.CENTER; setTextColor(theme.onPrimary); background = theme.shape(theme.primary, 100) }
        private val missing = label("文件不存在", 10f, true).apply { gravity = Gravity.CENTER; background = theme.shape(theme.muted, 5) }
        init {
            root.orientation = LinearLayout.VERTICAL; root.background = theme.control(theme.surface, 16); root.clipToOutline = true
            root.layoutParams = GridLayoutManager.LayoutParams(-1, -2); root.isFocusable = true
            root.addView(content, LinearLayout.LayoutParams(-1, -2))
            content.addView(Square().apply {
                addView(cover, LayoutParams(-1, -1))
                addView(gif, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { marginEnd = theme.dp(6); bottomMargin = theme.dp(6) })
                addView(live, LayoutParams(theme.dp(24), theme.dp(24), Gravity.TOP or Gravity.START).apply { leftMargin = theme.dp(6); topMargin = theme.dp(6) })
                addView(check, LayoutParams(theme.dp(24), theme.dp(24), Gravity.TOP or Gravity.END).apply { rightMargin = theme.dp(6); topMargin = theme.dp(6) })
                addView(missing, LayoutParams(-1, -2, Gravity.CENTER))
            })
            content.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(theme.dp(8), theme.dp(7), theme.dp(8), theme.dp(8))
                addView(title); addView(detail, gap(3))
            })
            root.setOnClickListener { current?.let { if (selecting) toggle(it) else open(it, cover) } }
            root.setOnLongClickListener { current?.let(::toggle); UiMotion.haptic(root); true }; UiMotion.press(root)
        }
        fun recycle() { MediaThumbnails.clear(cover) }
        fun bind(item: MediaItem) {
            MediaThumbnails.bind(cover, item, VideoCoverDrawable(item, theme))
            current = item; root.tag = "work_card_${item.id}"; cover.tag = "work_cover_${item.id}"
            cover.alpha = if (item.exists) 1f else .5f
            gif.visibility = if (item.type == MediaType.GIF) VISIBLE else GONE
            live.visibility = if (item.type == MediaType.LIVE_PHOTO) VISIBLE else GONE
            missing.visibility = if (item.exists) GONE else VISIBLE
            check.visibility = if (selecting) VISIBLE else GONE; check.text = if (item.id in selected) "✓" else "○"
            root.isSelected = selecting && item.id in selected
            title.text = item.title; detail.text = "${if (item.favorite) "♥ " else ""}${TaskUi.bytes(item.sizeBytes)}"
            root.contentDescription = "${item.title}，${if (item.type == MediaType.GIF) "GIF" else "实况"}，${detail.text}${if (!item.exists) "，文件不存在" else ""}"
        }
    }
    private fun label(value: String, size: Float, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(if (bold) theme.text else theme.secondary)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    private fun action(text: String, tagValue: String, click: () -> Unit) = label(text, 15f, true).apply {
        tag = tagValue; gravity = Gravity.CENTER; minHeight = theme.dp(48); minWidth = theme.dp(48)
        setPadding(theme.dp(10), theme.dp(6), theme.dp(10), theme.dp(6)); setTextColor(theme.primaryText)
        background = theme.control(theme.primarySoft, 14)
        isFocusable = true; setOnClickListener { click() }; UiMotion.press(this)
    }
    private fun gap(dp: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(dp) }
    private fun motionsEnabled() = if (android.os.Build.VERSION.SDK_INT >= 26) android.animation.ValueAnimator.areAnimatorsEnabled()
        else android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
}
