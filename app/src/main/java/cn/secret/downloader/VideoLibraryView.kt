package cn.secret.downloader

import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.util.SparseArray
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import java.io.Closeable
import kotlin.math.roundToInt

/** Paged, recycled videos with optional read-only folder import. */
class VideoLibraryView(
    context: Context,
    private val theme: UiTheme,
    private val media: MediaRepository,
    private val tasks: TaskRepository,
    private val onDownload: () -> Unit,
    private val onTaskCenter: () -> Unit,
    private val onPlay: (MediaItem, View?) -> Unit,
    private val onConvert: (MediaItem, Boolean) -> Unit,
    private val onSettings: () -> Unit,
    private val scanOnAttach: Boolean = false,
) : FrameLayout(context) {
    internal var activeDialog: Dialog? = null
        private set
    private var subscription: Closeable? = null
    private var taskSubscription: Closeable? = null
    private var filter = MediaFilter.ALL
    private var sort = MediaSort.RECENT_DOWNLOAD
    private var query = ""
    private var pageLimit = 40
    private var more = false
    private var searchOpen = false
    private var restoring = false
    private var selectionMode = false
    private var receivedMedia = media.items.isNotEmpty()
    private val selected = linkedSetOf<String>()
    private var matchingIds = emptySet<String>()
    private var items = emptyList<MediaItem>()
    private val handler = Handler(Looper.getMainLooper())
    private val searchUpdate = Runnable { query = search.text.toString(); pageLimit = 40; reload(true) }
    private val gridLayout = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
    private val cards = VideoAdapter()
    private val grid = RecyclerView(context).apply {
        id = R.id.video_grid
        tag = "video_grid"
        layoutManager = gridLayout
        adapter = cards
        clipToPadding = false
        setPadding(0, 0, 0, theme.dp(16))
        itemAnimator = CardSpringAnimator()
        addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(out: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                val span = (view.layoutParams as? StaggeredGridLayoutManager.LayoutParams)?.spanIndex ?: 0
                out.set(theme.dp(if (span == 0) 16 else 6), theme.dp(12), theme.dp(if (span == 0) 6 else 16), 0)
            }
        })
        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && more && gridLayout.findLastVisibleItemPositions(null).maxOrNull().orEmptyIndex() >= items.size - 8) {
                    pageLimit += 40
                    reload(false)
                }
            }
        })
    }
    private val search: EditText = EditText(context).apply {
        id = R.id.video_search_input
        tag = "video_search_input"
        hint = "标题、作者或平台"
        textSize = 15f
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT
        setTextColor(theme.text); setHintTextColor(theme.secondary)
        background = theme.shape(theme.muted, 16)
        setPadding(theme.dp(14), 0, theme.dp(14), 0)
        visibility = GONE
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!restoring) { handler.removeCallbacks(searchUpdate); handler.postDelayed(searchUpdate, 180) }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }
    private val heading = text("视频库", 30f, true)
    private val filterButtons = mutableListOf<TextView>()
    private val filterPill = View(context).apply { background = theme.shape(theme.primarySoft, 14) }
    private val filterMotion = SpringAnimation(filterPill, DynamicAnimation.TRANSLATION_X).apply {
        spring = SpringForce(0f).apply { dampingRatio = .75f; stiffness = 400f }
    }
    private val bannerLabel = text("", 13f, true).apply { setTextColor(theme.primaryText) }
    private val bannerProgress = JellyProgressBar(context, theme, 6)
    private val banner = LinearLayout(context).apply {
        tag = "video_download_banner"
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        background = theme.control(theme.primarySoft, 14)
        setPadding(theme.dp(14), 0, theme.dp(14), 0)
        addView(bannerLabel)
        addView(bannerProgress, LinearLayout.LayoutParams(-1, theme.dp(12)))
        visibility = GONE
        setOnClickListener { onTaskCenter() }
        UiMotion.press(this)
    }
    private val selectionLabel = text("", 14f, true).apply {
        tag = "video_selection_count"
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val selectAll = button("全选", "video_bulk_all") { toggleAll() }
    private val bulkFavorite = button("收藏", "video_bulk_favorite") {
        selected.toList().forEach { media.setFavorite(it, true) }; toast("已收藏所选视频")
    }
    private val bulkDelete = button("删除", "video_bulk_delete") { confirmDelete(selected.mapNotNull(media::get)) }
    private val selectionBar = LinearLayout(context).apply {
        tag = "video_selection_toolbar"
        orientation = LinearLayout.VERTICAL
        visibility = GONE
        setPadding(theme.dp(16), 0, theme.dp(16), 0)
    }
    private val empty = LinearLayout(context).apply {
        tag = "video_library_empty"
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(theme.dp(24), theme.dp(20), theme.dp(24), theme.dp(20))
        addView(text("▷", 64f).apply { gravity = Gravity.CENTER; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO })
        addView(text("还没有视频，去下载一个吧", 18f, true).apply { gravity = Gravity.CENTER }, gap(10))
        addView(button("去下载", "video_empty_download") { onDownload() }, gap(20))
    }

    init {
        id = R.id.video_library_view
        tag = "tab_library"
        setBackgroundColor(theme.background)
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.dp(16), theme.dp(14), theme.dp(12), theme.dp(6))
            addView(FrameLayout(context).apply {
                addView(heading, LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
                addView(search, LayoutParams(-1, theme.dp(48), Gravity.CENTER_VERTICAL))
            }, LinearLayout.LayoutParams(0, theme.dp(48), 1f))
            addView(icon("⌕", "搜索视频", "video_search_toggle") { toggleSearch(!searchOpen) }, LinearLayout.LayoutParams(theme.dp(48), theme.dp(48)))
            addView(button("排序", "video_sort") { showSort() }.apply { contentDescription = "视频排序" },
                LinearLayout.LayoutParams(theme.dp(72), theme.dp(48)).apply { marginEnd = theme.dp(6) })
            addView(ImageButton(context).apply {
                tag = "open_SETTINGS"
                setImageResource(R.drawable.ic_settings)
                background = theme.control(theme.surface, 100); backgroundTintList = null
                contentDescription = "自定义下载设置"
                setOnClickListener { onSettings() }; UiMotion.press(this)
            }, LinearLayout.LayoutParams(theme.dp(48), theme.dp(48)))
        }
        body.addView(header)
        val filters = FrameLayout(context)
        filters.addView(filterPill, LayoutParams(0, -1))
        filters.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf("全部", "收藏", "稍后看", "未看完").forEachIndexed { index, label ->
                val target = MediaFilter.entries[index]
                val view = text(label, 13f, true).apply {
                    tag = "video_filter_${target.name}"
                    gravity = Gravity.CENTER
                    isFocusable = true
                    setOnClickListener {
                        if (filter != target) { filter = target; pageLimit = 40; updateFilters(); reload(true) }
                    }
                    UiMotion.press(this)
                }
                filterButtons += view
                addView(view, LinearLayout.LayoutParams(0, theme.dp(48), 1f))
            }
        }, LayoutParams(-1, -1))
        filters.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateFilters(false) }
        body.addView(filters, LinearLayout.LayoutParams(-1, theme.dp(48)).apply { setMargins(theme.dp(16), theme.dp(12), theme.dp(16), 0) })
        body.addView(banner, LinearLayout.LayoutParams(-1, theme.dp(58)).apply { setMargins(theme.dp(16), theme.dp(12), theme.dp(16), 0) })
        selectionBar.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(selectionLabel, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("完成", "video_bulk_done") { leaveSelection() }, LinearLayout.LayoutParams(theme.dp(72), theme.dp(48)))
        }, LinearLayout.LayoutParams(-1, -2))
        selectionBar.addView(LinearLayout(context).apply {
            listOf(selectAll, bulkFavorite, bulkDelete).forEachIndexed { index, button ->
                addView(button, LinearLayout.LayoutParams(0, theme.dp(48), 1f).apply { if (index > 0) marginStart = theme.dp(8) })
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(6) })
        body.addView(selectionBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })
        body.addView(MediaRefreshLayout(context, theme, media, scanOnAttach).apply {
            content(grid, empty)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(body, LayoutParams(-1, -1))
        updateFilters(false)
        reload(false)
    }

    fun scrollToTop() {
        if (items.isNotEmpty()) { if (animationsEnabled()) grid.smoothScrollToPosition(0) else grid.scrollToPosition(0) }
    }

    fun exitSelection(): Boolean {
        if (selectionMode) { leaveSelection(); return true }
        if (searchOpen) { search.text.clear(); toggleSearch(false); return true }
        return false
    }

    private fun toggleSearch(open: Boolean) {
        searchOpen = open
        search.visibility = if (open) VISIBLE else GONE
        heading.visibility = if (open) GONE else VISIBLE
        if (open) {
            UiMotion.enter(search, 0)
            if (animationsEnabled()) {
                search.pivotX = 0f; search.scaleX = .9f
                SpringAnimation(search, DynamicAnimation.SCALE_X, 1f).start()
            }
            search.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        } else {
            search.clearFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
        }
    }

    private fun updateFilters(animate: Boolean = true) {
        filterButtons.forEachIndexed { index, button ->
            val selected = MediaFilter.entries[index] == filter
            button.isSelected = selected
            button.setTextColor(if (selected) theme.primaryText else theme.secondary)
        }
        val target = filterButtons[filter.ordinal]
        if (target.width > 0) {
            if (filterPill.layoutParams.width != target.width) filterPill.layoutParams = filterPill.layoutParams.apply { width = target.width }
            if (animate && animationsEnabled()) filterMotion.animateToFinalPosition(target.left.toFloat())
            else { filterMotion.cancel(); filterPill.translationX = target.left.toFloat() }
        }
    }

    private fun reload(resetScroll: Boolean) {
        // Selection covers every current result, including cards beyond the loaded page.
        val matching = media.query(query, sort, filter, MediaType.VIDEO, 0, Int.MAX_VALUE)
        matchingIds = matching.mapTo(linkedSetOf()) { it.id }
        val next = matching.take(pageLimit)
        more = matching.size > next.size
        if (media.isLoaded || receivedMedia || media.items.isNotEmpty()) {
            receivedMedia = true
            selected.retainAll(matchingIds)
        }
        val previous = items
        val changes = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = previous.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(old: Int, new: Int) = previous[old].id == next[new].id
            override fun areContentsTheSame(old: Int, new: Int) = previous[old] == next[new]
        })
        items = next
        changes.dispatchUpdatesTo(cards)
        empty.visibility = if (next.isEmpty()) VISIBLE else GONE
        grid.visibility = if (next.isEmpty()) GONE else VISIBLE
        updateSelection()
        if (resetScroll && next.isNotEmpty()) grid.scrollToPosition(0)
    }

    private fun toggleSelected(item: MediaItem) {
        val entering = !selectionMode
        selectionMode = true
        if (!selected.add(item.id)) selected.remove(item.id)
        updateSelection()
        if (entering) cards.notifyItemRangeChanged(0, items.size, "selection")
        else items.indexOfFirst { it.id == item.id }.takeIf { it >= 0 }?.let { cards.notifyItemChanged(it, "selection") }
    }

    private fun toggleAll() {
        if (matchingIds.isNotEmpty() && selected.containsAll(matchingIds)) selected.clear()
        else selected.addAll(matchingIds)
        updateSelection()
        cards.notifyItemRangeChanged(0, items.size, "selection")
    }

    private fun leaveSelection() {
        selectionMode = false; selected.clear(); updateSelection()
        cards.notifyItemRangeChanged(0, items.size, "selection")
    }

    private fun updateSelection() {
        selectionBar.visibility = if (selectionMode) VISIBLE else GONE
        selectionLabel.text = "已选 ${selected.size} 项"
        selectAll.text = if (matchingIds.isNotEmpty() && selected.containsAll(matchingIds)) "取消全选" else "全选"
        selectAll.contentDescription = "${selectAll.text}当前筛选和搜索的 ${matchingIds.size} 个视频"
        selectAll.isEnabled = matchingIds.isNotEmpty()
        listOf(bulkFavorite, bulkDelete).forEach { it.isEnabled = selected.isNotEmpty(); it.alpha = if (it.isEnabled) 1f else .45f }
        bulkFavorite.contentDescription = "收藏所选 ${selected.size} 个视频"
        bulkDelete.contentDescription = "删除所选 ${selected.size} 个视频"
    }

    private fun play(item: MediaItem, source: View?) {
        if (!item.exists) { missing(item); return }
        onPlay(item, source)
    }

    private fun missing(item: MediaItem) {
        val dialog = AlertDialog.Builder(context).setTitle("文件已不存在")
            .setMessage("可以移除这条记录${if (item.sourceUrl != null) "，或从原链接重新下载" else ""}。")
            .setNegativeButton("取消", null)
            .setNeutralButton("移除记录") { _, _ -> media.delete(item.id) }
        if (!item.sourceUrl.isNullOrBlank()) dialog.setPositiveButton("重新下载") { _, _ -> redownload(item) }
        show(dialog.create())
    }

    private fun redownload(item: MediaItem) {
        if (item.sourceUrl.isNullOrBlank()) return
        tasks.create(TaskType.DOWNLOAD, item.title, item.sourceUrl)
        toast("已加入下载任务")
    }

    private fun showSort() {
        val labels = arrayOf("最近下载", "最近观看", "时长", "大小")
        show(AlertDialog.Builder(context).setTitle("排序方式").setSingleChoiceItems(labels, sort.ordinal) { dialog, index ->
            sort = MediaSort.entries[index]; pageLimit = 40; reload(true); dialog.dismiss()
        }.setNegativeButton("取消", null).create())
    }

    private fun showMenu(item: MediaItem, source: View?) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = theme.shape(theme.surface, 28)
            setPadding(theme.dp(20), theme.dp(12), theme.dp(20), theme.dp(24))
            addView(View(context).apply { background = theme.shape(theme.secondary, 100) },
                LinearLayout.LayoutParams(theme.dp(34), theme.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = theme.dp(18) })
            addView(text(item.title, 18f, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        }
        val entries = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun entry(title: String, tag: String, action: () -> Unit) {
            entries.addView(button(title, "video_action_$tag") { dialog.dismiss(); action() }.apply {
                gravity = Gravity.CENTER_VERTICAL; background = theme.control(theme.surface, 12)
            }, LinearLayout.LayoutParams(-1, theme.dp(48)))
        }
        entry("播放", "play") { play(item, source) }
        entry(if (item.favorite) "取消收藏" else "收藏", "favorite") { media.setFavorite(item.id, !item.favorite) }
        entry(if (item.watchLater) "移出稍后看" else "稍后看", "watch_later") { media.setWatchLater(item.id, !item.watchLater) }
        entry("制作 GIF", "gif") { if (item.exists) onConvert(item, true) else missing(item) }
        entry("制作实况", "live") { if (item.exists) onConvert(item, false) else missing(item) }
        entry("分享", "share") { share(item) }
        entry("重命名", "rename") { rename(item) }
        entry("视频信息", "info") { info(item) }
        entry("删除", "delete") { confirmDelete(listOf(item)) }
        body.addView(ScrollView(context).apply { isFillViewport = false; addView(entries) },
            LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = theme.dp(8) })
        dialog.setContentView(body)
        show(dialog) {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setGravity(Gravity.BOTTOM)
                setLayout(-1, (resources.displayMetrics.heightPixels * .78f).roundToInt())
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.apply { dimAmount = .35f }
            }
            if (animationsEnabled()) {
                body.translationY = theme.dp(48).toFloat()
                SpringAnimation(body, DynamicAnimation.TRANSLATION_Y, 0f).apply {
                    spring = SpringForce(0f).apply { dampingRatio = .78f; stiffness = 400f }; start()
                }
                body.alpha = 0f
                SpringAnimation(body, DynamicAnimation.ALPHA, 1f).apply {
                    setMinValue(0f); setMaxValue(1f); start()
                }
            }
        }
    }

    private fun rename(item: MediaItem) {
        val input = EditText(context).apply { setText(item.title); setSelectAllOnFocus(true); isSingleLine = true; tag = "video_rename_input" }
        val dialog = AlertDialog.Builder(context).setTitle("重命名").setView(input)
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        show(dialog) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                if (value.isEmpty()) input.error = "标题不能为空" else { media.rename(item.id, value); dialog.dismiss() }
            }
        }
    }

    private fun info(item: MediaItem) {
        show(AlertDialog.Builder(context).setTitle("视频信息").setMessage(listOf(
            item.title, "作者：${item.author ?: "未知"}", "平台：${item.platform}",
            "时长：${VideoLibraryUi.duration(item.durationMs)}", "分辨率：${item.width} × ${item.height}",
            "大小：${TaskUi.bytes(item.sizeBytes)}", "下载时间：${VideoLibraryUi.downloaded(item.downloadedAt)}",
            "播放次数：${item.playCount}", "文件：${item.localUri ?: "尚未关联本地文件"}",
            "来源：${item.sourceUrl ?: "本地视频"}"
        ).joinToString("\n")).setPositiveButton("关闭", null).create())
    }

    private fun share(item: MediaItem) {
        val intent = Intent(Intent.ACTION_SEND)
        if (item.localUri != null && item.exists) {
            val uri = Uri.parse(item.localUri)
            intent.type = "video/*"; intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.clipData = ClipData.newRawUri(item.title, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            intent.type = "text/plain"; intent.putExtra(Intent.EXTRA_TEXT, listOfNotNull(item.title, item.sourceUrl).joinToString("\n"))
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "分享视频")) }.onFailure { toast("无法打开分享面板") }
    }

    private fun confirmDelete(records: List<MediaItem>) {
        if (records.isEmpty()) return
        val deleteFile = CheckBox(context).apply {
            tag = "video_delete_file"; text = "同时删除本地文件"
            isEnabled = records.all(MediaDeletion::canDeleteFile)
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.dp(24), theme.dp(10), theme.dp(24), 0)
            addView(text("移除 ${records.size} 条视频库记录？", 15f))
            addView(deleteFile)
            addView(text(if (deleteFile.isEnabled) "勾选后会删除本地文件；未勾选只移除记录。" else "部分文件暂不支持直接删除，只会移除记录。", 12f))
        }
        show(AlertDialog.Builder(context).setTitle("删除视频").setView(body).setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                MediaDeletion.delete(context, media, records, deleteFile.isChecked) { failed ->
                    selected.removeAll(records.filter { media.get(it.id) == null }.map { it.id }.toSet())
                    if (selectionMode && selected.isEmpty()) leaveSelection()
                    if (failed > 0) toast("$failed 个文件未能删除，记录已保留")
                }
            }.create())
    }

    private fun show(dialog: Dialog, afterShow: (() -> Unit)? = null) {
        activeDialog?.dismiss()
        activeDialog = dialog
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
        dialog.show()
        afterShow?.invoke()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        subscription = media.observeAll { reload(false) }
        taskSubscription = tasks.observeAll { values ->
            val active = values.filter { it.type == TaskType.DOWNLOAD &&
                (it.status == TaskStatus.QUEUED || it.status == TaskStatus.PARSING || it.status == TaskStatus.RUNNING) }
            banner.visibility = if (active.isNotEmpty()) VISIBLE else GONE
            val value = "${active.size} 个视频下载中  ›"
            if (bannerLabel.text != value) bannerLabel.text = value
            if (active.isNotEmpty()) bannerProgress.setProgress(
                if (active.any { it.progress == null }) null else active.map { it.progress!! }.average().toFloat(),
                TaskStatus.RUNNING, active.joinToString { "${it.id}:${it.attempt}" })
        }
    }

    override fun onDetachedFromWindow() {
        subscription?.close(); subscription = null; taskSubscription?.close(); taskSubscription = null
        handler.removeCallbacks(searchUpdate); filterMotion.cancel(); activeDialog?.dismiss()
        grid.itemAnimator?.endAnimations()
        super.onDetachedFromWindow()
    }

    override fun onSaveInstanceState(): Parcelable = Bundle().apply {
        putParcelable("super", super.onSaveInstanceState()); putParcelable("grid", gridLayout.onSaveInstanceState())
        putString("query", search.text.toString()); putString("sort", sort.name); putString("filter", filter.name)
        putBoolean("search_open", searchOpen); putInt("limit", pageLimit)
        putBoolean("selection_mode", selectionMode); putStringArrayList("selected", ArrayList(selected))
    }

    // Search and grid state are restored together; child EditText restoration must not reset the viewport.
    override fun dispatchSaveInstanceState(container: SparseArray<Parcelable>) = dispatchFreezeSelfOnly(container)
    override fun dispatchRestoreInstanceState(container: SparseArray<Parcelable>) = dispatchThawSelfOnly(container)

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is Bundle) { super.onRestoreInstanceState(state); return }
        @Suppress("DEPRECATION") super.onRestoreInstanceState(state.getParcelable("super"))
        sort = MediaSort.entries.firstOrNull { it.name == state.getString("sort") } ?: MediaSort.RECENT_DOWNLOAD
        filter = MediaFilter.entries.firstOrNull { it.name == state.getString("filter") } ?: MediaFilter.ALL
        query = state.getString("query").orEmpty(); pageLimit = state.getInt("limit", 40).coerceAtLeast(40)
        restoring = true; search.setText(query); restoring = false
        searchOpen = state.getBoolean("search_open"); search.visibility = if (searchOpen) VISIBLE else GONE
        heading.visibility = if (searchOpen) GONE else VISIBLE
        selectionMode = state.getBoolean("selection_mode"); selected.clear(); selected.addAll(state.getStringArrayList("selected").orEmpty())
        updateFilters(false); reload(false)
        @Suppress("DEPRECATION") val layout = state.getParcelable<Parcelable>("grid")
        if (layout != null) gridLayout.onRestoreInstanceState(layout)
    }

    private inner class VideoAdapter : RecyclerView.Adapter<VideoHolder>() {
        private val appeared = hashSetOf<String>()
        init { setHasStableIds(true) }
        override fun getItemCount() = items.size
        override fun getItemId(position: Int) = VideoLibraryUi.stableId(items[position].id)
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = VideoHolder()
        override fun onBindViewHolder(holder: VideoHolder, position: Int) = holder.bind(items[position])
        override fun onViewRecycled(holder: VideoHolder) { holder.recycle() }
        override fun onViewAttachedToWindow(holder: VideoHolder) {
            val position = holder.bindingAdapterPosition
            if (position in items.indices && appeared.add(items[position].id)) UiMotion.enter(holder.content, position % 4)
        }
    }

    /** RecyclerView owns layout; springs only translate/fade its recycled cards during a diff. */
    private inner class CardSpringAnimator : SimpleItemAnimator() {
        private inner class Motion(val holder: RecyclerView.ViewHolder, val kind: Int) {
            var start: Runnable? = null
            val springs = mutableListOf<SpringAnimation>()
        }
        private val motions = linkedMapOf<RecyclerView.ViewHolder, Motion>()
        init { supportsChangeAnimations = false }

        override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
            endAnimation(holder)
            if (!animationsEnabled()) { dispatchAddFinished(holder); return false }
            holder.itemView.alpha = 0f
            holder.itemView.translationY = theme.dp(18).toFloat()
            motions[holder] = Motion(holder, 0)
            return true
        }
        override fun animateRemove(holder: RecyclerView.ViewHolder): Boolean {
            endAnimation(holder)
            if (!animationsEnabled()) { dispatchRemoveFinished(holder); return false }
            motions[holder] = Motion(holder, 1)
            return true
        }
        override fun animateMove(holder: RecyclerView.ViewHolder, fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean {
            val x = holder.itemView.translationX + fromX - toX
            val y = holder.itemView.translationY + fromY - toY
            endAnimation(holder)
            if (!animationsEnabled()) { dispatchMoveFinished(holder); return false }
            if (x == 0f && y == 0f) { dispatchMoveFinished(holder); return false }
            holder.itemView.translationX = x; holder.itemView.translationY = y
            motions[holder] = Motion(holder, 2)
            return true
        }
        override fun animateChange(old: RecyclerView.ViewHolder, new: RecyclerView.ViewHolder?,
            fromLeft: Int, fromTop: Int, toLeft: Int, toTop: Int): Boolean {
            endAnimation(old); dispatchChangeFinished(old, true)
            if (new != null && new !== old) { endAnimation(new); dispatchChangeFinished(new, false) }
            return false
        }
        override fun runPendingAnimations() {
            motions.values.filter { it.start == null }.forEachIndexed { index, motion ->
                val view = motion.holder.itemView
                val start = Runnable {
                    if (motions[motion.holder] !== motion) return@Runnable
                    if (!animationsEnabled()) { finish(motion); return@Runnable }
                    when (motion.kind) {
                        0 -> dispatchAddStarting(motion.holder)
                        1 -> dispatchRemoveStarting(motion.holder)
                        else -> dispatchMoveStarting(motion.holder)
                    }
                    val targets = if (motion.kind == 1) listOf(DynamicAnimation.ALPHA to 0f) else
                        listOf(DynamicAnimation.TRANSLATION_X to 0f, DynamicAnimation.TRANSLATION_Y to 0f, DynamicAnimation.ALPHA to 1f)
                    var left = targets.size
                    targets.forEach { (property, target) ->
                        val spring = SpringAnimation(view, property, target).apply {
                            spring = SpringForce(target).apply { dampingRatio = .82f; stiffness = 420f }
                            if (property == DynamicAnimation.ALPHA) { setMinValue(0f); setMaxValue(1f) }
                            addEndListener { _, canceled, _, _ ->
                                if (!canceled && --left == 0 && motions[motion.holder] === motion) finish(motion)
                            }
                        }
                        motion.springs += spring
                        spring.start()
                    }
                }
                motion.start = start
                view.postDelayed(start, if (motion.kind == 0) (index % 4 * 24L) else 0L)
            }
        }
        private fun finish(motion: Motion) {
            if (motions[motion.holder] !== motion) return
            motions.remove(motion.holder)
            motion.start?.let(motion.holder.itemView::removeCallbacks)
            motion.springs.forEach { it.cancel() }
            motion.holder.itemView.apply { alpha = 1f; translationX = 0f; translationY = 0f }
            when (motion.kind) {
                0 -> dispatchAddFinished(motion.holder)
                1 -> dispatchRemoveFinished(motion.holder)
                else -> dispatchMoveFinished(motion.holder)
            }
            if (motions.isEmpty()) dispatchAnimationsFinished()
        }
        override fun endAnimation(holder: RecyclerView.ViewHolder) { motions[holder]?.let(::finish) }
        override fun endAnimations() { motions.values.toList().forEach(::finish) }
        override fun isRunning() = motions.isNotEmpty()
    }

    private inner class CoverFrame : FrameLayout(context) {
        var aspect = 16f / 9f
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((width / aspect).roundToInt(), MeasureSpec.EXACTLY))
        }
    }

    private inner class VideoHolder : RecyclerView.ViewHolder(LinearLayout(context)) {
        val root = itemView as LinearLayout
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private var current: MediaItem? = null
        private val cover = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val coverFrame = CoverFrame()
        private val duration = text("", 11f, true).apply {
            setTextColor(Color.WHITE); background = theme.shape(0x99000000.toInt(), 7)
            setPadding(theme.dp(6), theme.dp(3), theme.dp(6), theme.dp(3))
        }
        private val marker = text("", 9f, true).apply {
            background = theme.shape(theme.primarySoft, 7)
            setTextColor(theme.primaryText); setPadding(theme.dp(6), theme.dp(3), theme.dp(6), theme.dp(3))
        }
        private val check = text("✓", 18f, true).apply {
            gravity = Gravity.CENTER; setTextColor(theme.onPrimary); background = theme.shape(theme.primary, 100)
        }
        private val progress = JellyProgressBar(context, theme, 3)
        private val title = text("", 14f, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        private val author = text("", 11f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        private val platform = text("", 9f, true).apply {
            maxLines = 1
            setTextColor(theme.secondary); background = theme.shape(theme.muted, 6)
            setPadding(theme.dp(4), theme.dp(2), theme.dp(4), theme.dp(2))
        }
        private val missingLabel = text("文件不存在", 12f, true).apply {
            gravity = Gravity.CENTER; setTextColor(theme.secondary); background = theme.shape(theme.muted, 8)
        }
        private val details = text("", 10f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        private val downloaded = text("", 10f)
        private val menu = icon("⋯", "视频更多操作", "") { current?.let { showMenu(it, cover) } }

        init {
            root.orientation = LinearLayout.VERTICAL
            root.background = theme.control(theme.surface, 20)
            root.clipToOutline = true
            root.layoutParams = StaggeredGridLayoutManager.LayoutParams(-1, -2)
            root.isFocusable = true
            root.setOnClickListener { current?.let { if (selectionMode) toggleSelected(it) else play(it, cover) } }
            root.setOnLongClickListener { current?.let { toggleSelected(it); UiMotion.haptic(root) }; true }
            UiMotion.press(root)
            root.addView(content, LinearLayout.LayoutParams(-1, -2))
            coverFrame.addView(cover, LayoutParams(-1, -1))
            coverFrame.addView(duration, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(theme.dp(8), theme.dp(8), theme.dp(8), theme.dp(10)) })
            coverFrame.addView(missingLabel, LayoutParams(-2, -2, Gravity.CENTER).apply { leftMargin = theme.dp(6); rightMargin = theme.dp(6) })
            coverFrame.addView(check, LayoutParams(theme.dp(28), theme.dp(28), Gravity.TOP or Gravity.END).apply { topMargin = theme.dp(8); marginEnd = theme.dp(8) })
            coverFrame.addView(progress, LayoutParams(-1, theme.dp(3), Gravity.BOTTOM))
            content.addView(coverFrame, LinearLayout.LayoutParams(-1, -2))
            content.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(theme.dp(10), theme.dp(10), theme.dp(10), theme.dp(6))
                addView(title)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(author, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(platform, LinearLayout.LayoutParams(-2, -2).apply { marginStart = theme.dp(4) })
                    addView(marker, LinearLayout.LayoutParams(-2, -2).apply { marginStart = theme.dp(4) })
                }, gap(5))
                addView(details, gap(4))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(downloaded, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(menu, LinearLayout.LayoutParams(theme.dp(48), theme.dp(48)))
                }, LinearLayout.LayoutParams(-1, -2))
            }, LinearLayout.LayoutParams(-1, -2))
        }

        fun recycle() { MediaThumbnails.clear(cover) }
        fun bind(item: MediaItem) {
            current = item
            root.tag = "video_card_${item.id}"
            cover.tag = "video_cover_${item.id}"
            menu.tag = "video_menu_${item.id}"
            val aspect = VideoLibraryUi.aspect(item)
            if (coverFrame.aspect != aspect) { coverFrame.aspect = aspect; coverFrame.requestLayout() }
            MediaThumbnails.bind(cover, item, VideoCoverDrawable(item, theme))
            cover.alpha = if (item.exists) 1f else .55f
            missingLabel.visibility = if (item.exists) GONE else VISIBLE
            duration.text = VideoLibraryUi.duration(item.durationMs)
            marker.text = when { item.isFinished -> "已看完"; item.isNew -> "新"; else -> "" }
            marker.background = theme.shape(if (item.isFinished) theme.muted else theme.primarySoft, 7)
            marker.setTextColor(if (item.isFinished) theme.secondary else theme.primaryText)
            marker.visibility = if (marker.text.isEmpty()) GONE else VISIBLE
            check.visibility = if (selectionMode) VISIBLE else GONE
            check.text = if (item.id in selected) "✓" else "○"
            check.setTextColor(if (item.id in selected) theme.onPrimary else Color.WHITE)
            check.background = theme.shape(if (item.id in selected) theme.primary else 0x99000000.toInt(), 100)
            root.isSelected = selectionMode && item.id in selected
            val fraction = if (item.durationMs > 0) (item.lastPositionMs.toDouble() / item.durationMs).toFloat().coerceIn(0f, 1f) else 0f
            progress.visibility = if (item.isPartiallyWatched) VISIBLE else GONE
            progress.setProgress(fraction, TaskStatus.RUNNING, item.id)
            title.text = item.title
            author.text = item.author?.takeIf { it.isNotBlank() } ?: item.platform
            platform.text = item.platform
            details.text = "▶ ${item.playCount}  ▱ ${TaskUi.bytes(item.sizeBytes)}"
            downloaded.text = VideoLibraryUi.relativeDownloaded(item.downloadedAt)
            root.contentDescription = "${item.title}，${author.text}，${duration.text}${if (!item.exists) "，文件缺失" else ""}" +
                if (selectionMode) "，${if (item.id in selected) "已选择" else "未选择"}" else "，长按多选"
        }
    }

    private fun icon(symbol: String, description: String, tagValue: String, action: () -> Unit) = text(symbol, 25f, true).apply {
        tag = tagValue; gravity = Gravity.CENTER; contentDescription = description
        isFocusable = true; setOnClickListener { action() }; UiMotion.press(this)
    }
    private fun button(label: String, tagValue: String, action: () -> Unit) = text(label, 15f, true).apply {
        tag = tagValue; gravity = Gravity.CENTER; minHeight = theme.dp(48); minWidth = theme.dp(48)
        setPadding(theme.dp(12), theme.dp(8), theme.dp(12), theme.dp(8))
        setTextColor(theme.primaryText); background = theme.control(theme.primarySoft, 12)
        isFocusable = true; setOnClickListener { UiMotion.haptic(this); action() }; UiMotion.press(this)
    }
    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        setTextColor(if (bold) theme.text else theme.secondary)
    }
    private fun gap(dp: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(dp) }
    private fun toast(value: String) = Toast.makeText(context, value, Toast.LENGTH_SHORT).show()
    private fun Int?.orEmptyIndex() = this ?: -1
    private fun animationsEnabled() = if (android.os.Build.VERSION.SDK_INT >= 26) android.animation.ValueAnimator.areAnimatorsEnabled()
        else android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
}
