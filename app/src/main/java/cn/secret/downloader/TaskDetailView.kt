package cn.secret.downloader

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.Closeable

internal class TaskDetailView(
    context: Context, private val theme: UiTheme, private val repository: TaskRepository,
    private val taskId: String, private val onBack: () -> Unit, private val onOpen: (Task) -> Unit
) : FrameLayout(context) {
    private var observation: Closeable? = null
    private var current: Task? = null
    private var missing = false
    private var missingHandled = false
    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(110))
    }
    private val cover = text("", 22f, true).apply {
        tag = "detail_cover"
        gravity = Gravity.CENTER
        background = theme.shape(theme.primarySoft, 20)
        setTextColor(theme.primaryText)
    }
    private val title = text("任务详情", 21f, true).apply { maxLines = 2 }
    private val type = text("", 12f, true)
    private val state = text("", 12f, true)
    private val error = card().apply { background = theme.shape(theme.errorSoft); visibility = GONE }
    private val errorMessage = text("", 15f).apply { setTextColor(theme.error) }
    private val progressCard = card()
    private val percent = RollingPercentView(context, theme)
    private val progress = JellyProgressBar(context, theme, 12).apply { tag = "detail_progress" }
    private val bytes = text("", 13f)
    private val stages = TaskStagesView(context, theme)
    private val values = mutableListOf<TextView>()
    private val parameters = card()
    private val parameterText = text("", 14f)
    private val actions = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(12), dp(20), dp(12))
        setBackgroundColor(theme.surface)
        elevation = dp(8).toFloat()
    }

    init {
        tag = "route_TASK_DETAIL"
        setBackgroundColor(theme.background)
        val header = row()
        header.addView(ImageButton(context).apply {
            tag = "route_back"
            setImageResource(R.drawable.ic_back)
            imageTintList = android.content.res.ColorStateList.valueOf(theme.text)
            background = theme.control(theme.surface, 100)
            backgroundTintList = null
            contentDescription = "返回"
            setOnClickListener { onBack() }
            UiMotion.press(this)
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(text("任务详情", 22f, true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
        header.addView(button("⋯") { showMenu() }.apply { contentDescription = "任务更多操作" }, LinearLayout.LayoutParams(dp(48), dp(48)))
        body.addView(header)
        body.addView(card().apply {
            addView(row().apply {
                addView(cover, LinearLayout.LayoutParams(dp(80), dp(80)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(title)
                    addView(row().apply {
                        addView(type)
                        addView(state, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
                    }, gap())
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
            })
        }, gap())
        error.addView(errorMessage)
        error.addView(button("展开日志  ›") { showLogs() }, gap())
        body.addView(error, gap())
        progressCard.addView(percent, LinearLayout.LayoutParams(-1, dp(74)))
        progressCard.addView(progress, LinearLayout.LayoutParams(-1, dp(24)).apply { topMargin = dp(8) })
        progressCard.addView(bytes, gap())
        body.addView(progressCard, gap())
        body.addView(card().apply {
            addView(text("处理阶段", 15f, true))
            addView(stages, LinearLayout.LayoutParams(-1, dp(76)).apply { topMargin = dp(8) })
        }, gap())
        val labels = listOf("速度", "剩余时间", "已用时间", "文件大小", "画质 / 格式", "保存位置")
        for (index in labels.indices step 2) {
            body.addView(row().apply {
                for (column in 0..1) {
                    addView(card().apply {
                        addView(text(labels[index + column], 12f))
                        val value = text("—", 15f, true).apply { maxLines = 3 }
                        values += value
                        addView(value, gap(8))
                    }, LinearLayout.LayoutParams(0, -1, 1f).apply { if (column == 1) marginStart = dp(12) })
                }
            }, gap())
        }
        parameters.addView(text("创建参数", 15f, true))
        parameters.addView(parameterText, gap())
        body.addView(parameters, gap())
        addView(ScrollView(context).apply {
            id = R.id.task_detail_scroll
            tag = "detail_scroll"
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(body)
        }, LayoutParams(-1, -1))
        addView(actions, LayoutParams(-1, -2, Gravity.BOTTOM))
        actions.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val bottom = actions.height + dp(20)
            if (body.paddingBottom != bottom) body.setPadding(dp(20), dp(20), dp(20), bottom)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observation = repository.observeTask(taskId) { task ->
            missing = task == null
            if (task == null) leaveMissingTask() else bind(task)
        }
    }

    override fun onDetachedFromWindow() {
        observation?.close(); observation = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && missing) leaveMissingTask()
    }

    private fun leaveMissingTask() {
        // Repository delivery may happen inside addView or while a viewer covers this detail.
        post {
            if (isAttachedToWindow && isShown && missing && !missingHandled) {
                missingHandled = true
                onBack()
            }
        }
    }

    private fun bind(task: Task) {
        val previous = current
        val elapsed = ((task.finishedAt ?: System.currentTimeMillis()) - task.createdAt).coerceAtLeast(0) / 1000
        values[2].setTextIfChanged(if (elapsed < 60) "$elapsed 秒" else "${elapsed / 60} 分 ${elapsed % 60} 秒")
        if (previous == task) return
        current = task
        title.setTextIfChanged(task.title)
        if (previous?.type != task.type) {
            cover.setTextIfChanged(when (task.type) { TaskType.DOWNLOAD -> "↓"; TaskType.GIF -> "GIF"; TaskType.LIVE_PHOTO -> "◉" })
            chip(type, TaskUi.typeLabel(task.type), theme.primaryText, theme.primarySoft)
            parameters.visibility = if (task.type == TaskType.DOWNLOAD) GONE else VISIBLE
        }
        if (previous?.status != task.status || previous?.stage != task.stage) {
            val color = TaskUi.color(task, theme)
            chip(state, TaskUi.statusLabel(task), color, translucent(color))
        }
        progress.bind(task)
        percent.bind(task)
        bytes.setTextIfChanged("${TaskUi.bytes(task.bytesDone)} / ${task.bytesTotal?.let(TaskUi::bytes) ?: "总量未知"}")
        stages.bind(task)
        values[0].setTextIfChanged(TaskUi.speed(task.speedBps))
        values[1].setTextIfChanged(TaskUi.eta(task.etaSeconds))
        values[3].setTextIfChanged(task.bytesTotal?.let(TaskUi::bytes) ?: "未知")
        values[5].setTextIfChanged(task.outputUri ?: if (task.status == TaskStatus.COMPLETED) "演示作品" else "完成后保存")
        if (previous?.params != task.params || previous?.type != task.type) {
            val params = runCatching { JSONObject(task.params) }.getOrDefault(JSONObject())
            values[4].setTextIfChanged(params.optString("quality").ifBlank {
                params.optString("format").ifBlank { if (task.type == TaskType.DOWNLOAD) "原画 / 自动识别" else TaskUi.typeLabel(task.type) }
            })
            parameterText.setTextIfChanged(params.keys().asSequence().joinToString("\n") { "$it：${params.opt(it)}" }.ifBlank { "使用默认参数" })
        }
        errorMessage.setTextIfChanged(task.errorMessage ?: "任务未能完成，请重试。")
        if (previous?.status != task.status) {
            error.visibility = if (task.status == TaskStatus.FAILED) VISIBLE else GONE
            progressCard.background = theme.shape(if (task.status == TaskStatus.COMPLETED) theme.successSoft else theme.surface)
            rebuildActions(task)
            if (previous != null) UiMotion.enter(progressCard, 0)
        }
    }

    private fun rebuildActions(task: Task) {
        actions.removeAllViews()
        val entries: List<Pair<String, () -> Unit>> = when (task.status) {
            TaskStatus.QUEUED, TaskStatus.PARSING, TaskStatus.RUNNING -> listOf(
                "暂停" to { repository.pause(task.id) }, "取消" to { repository.cancel(task.id) })
            TaskStatus.PAUSED -> listOf("继续" to { repository.resume(task.id) }, "取消" to { repository.cancel(task.id) })
            TaskStatus.INTERRUPTED -> listOf("继续" to { repository.resume(task.id) }, "重试" to { repository.retry(task.id) }, "取消" to { repository.cancel(task.id) })
            TaskStatus.FAILED -> listOf("重试" to { repository.retry(task.id) })
            TaskStatus.COMPLETED -> listOf("打开" to { onOpen(task) }, "分享" to { share(task) }, "删除" to { repository.delete(task.id) })
            TaskStatus.CANCELED -> listOf("重试" to { repository.retry(task.id) }, "删除" to { repository.delete(task.id) })
        }
        entries.forEachIndexed { index, (title, action) ->
            actions.addView(button(title, action).apply { tag = "task_action_$title" }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                if (index > 0) marginStart = dp(10)
            })
        }
    }

    private fun showMenu() {
        AlertDialog.Builder(context).setItems(arrayOf("复制链接", "删除记录", "查看日志")) { _, index ->
            when (index) {
                0 -> {
                    val url = current?.sourceUrl
                    if (url.isNullOrBlank()) toast("这项本地转换没有来源链接") else {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("任务链接", url))
                        toast("链接已复制")
                    }
                }
                1 -> repository.delete(taskId)
                2 -> showLogs()
            }
        }.show()
    }

    private fun showLogs() {
        val message = repository.logs(taskId).joinToString("\n").ifBlank { "暂无日志" }
        AlertDialog.Builder(context).setTitle("任务日志").setMessage(message).setPositiveButton("关闭", null).show()
    }

    private fun share(task: Task) {
        val uri = task.outputUri?.let(Uri::parse)
        if (uri == null) { toast("当前为演示任务，尚未生成可分享的文件"); return }
        runCatching {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = context.contentResolver.getType(uri) ?: when (task.type) {
                    TaskType.DOWNLOAD -> "application/octet-stream"; TaskType.GIF -> "image/gif"; TaskType.LIVE_PHOTO -> "image/jpeg"
                }
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("作品", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "分享作品"))
        }.onFailure { toast("无法打开分享面板") }
    }

    private fun chip(view: TextView, value: String, color: Int, fill: Int) {
        view.setTextIfChanged(value)
        view.setTextColor(color)
        view.background = theme.shape(fill, 100)
        view.setPadding(dp(8), dp(4), dp(8), dp(4))
    }
    private fun translucent(color: Int) = (color and 0x00ffffff) or 0x18000000
    private fun dp(value: Int) = theme.dp(value)
    private fun toast(value: String) = Toast.makeText(context, value, Toast.LENGTH_SHORT).show()
    private fun gap(value: Int = 14) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(value) }
    private fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size
        setTextColor(if (bold) theme.text else theme.secondary)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    private fun button(value: String, action: () -> Unit) = text(value, 15f, true).apply {
        gravity = Gravity.CENTER; minHeight = dp(48)
        setTextColor(theme.primaryText); background = theme.control(theme.primarySoft)
        setPadding(dp(8), dp(6), dp(8), dp(6)); isFocusable = true
        setOnClickListener { UiMotion.haptic(this); action() }; UiMotion.press(this)
    }
    private fun card() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = theme.shape(theme.surface)
        setPadding(dp(20), dp(20), dp(20), dp(20))
    }
}

private class TaskStagesView(context: Context, private val theme: UiTheme) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val downloadStages = listOf(TaskStage.PARSING, TaskStage.DOWNLOADING, TaskStage.MERGING, TaskStage.SAVING)
    private val conversionStages = listOf(TaskStage.READING, TaskStage.ENCODING, TaskStage.SAVING)
    private var task: Task? = null
    private var phase = 0f
    private var pop = 1f
    private val breath = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1500; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }
    private val bounce = androidx.dynamicanimation.animation.SpringAnimation(
        androidx.dynamicanimation.animation.FloatValueHolder(1f)).apply {
        spring = androidx.dynamicanimation.animation.SpringForce(1f).apply { dampingRatio = .55f; stiffness = 400f }
        addUpdateListener { _, value, _ -> pop = value; invalidate() }
    }
    private val motion = ProgressMotion(this) { allowed ->
        updateBreath(allowed)
        if (!allowed) {
            bounce.cancel()
            pop = 1f
            phase = 0f
            invalidate()
        }
    }
    fun bind(value: Task) {
        val changed = task?.stage != value.stage || task?.status != value.status || task?.type != value.type
        task = value
        if (changed) {
            contentDescription = "${if (value.type == TaskType.DOWNLOAD) "解析、下载、合并、保存" else "读取、编码、保存"}，当前${TaskUi.statusLabel(value)}"
            motion.refresh()
            if (motion.allowed) {
                bounce.cancel(); bounce.setStartValue(.65f); bounce.start()
            }
            updateBreath(motion.allowed); invalidate()
        }
    }
    private fun updateBreath(allowed: Boolean) {
        val status = task?.status
        val running = status == TaskStatus.QUEUED || status == TaskStatus.PARSING || status == TaskStatus.RUNNING
        if (allowed && running) { if (!breath.isStarted) breath.start() } else breath.cancel()
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); motion.attach() }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isAttachedToWindow) motion.refresh()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttachedToWindow) motion.refresh()
    }
    override fun onDetachedFromWindow() { motion.detach(); breath.cancel(); bounce.cancel(); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val value = task ?: return
        val items = if (value.type == TaskType.DOWNLOAD) downloadStages else conversionStages
        val current = items.indexOf(value.stage).coerceAtLeast(0)
        val segment = width.toFloat() / items.size
        val y = theme.dp(22).toFloat()
        paint.strokeWidth = theme.dp(2).toFloat()
        for (index in 0 until items.lastIndex) {
            paint.color = if (index < current || value.status == TaskStatus.COMPLETED) theme.success else theme.muted
            canvas.drawLine(segment * (index + .5f), y, segment * (index + 1.5f), y, paint)
        }
        items.forEachIndexed { index, item ->
            val done = index < current || value.status == TaskStatus.COMPLETED
            val active = index == current && value.status != TaskStatus.COMPLETED
            val x = segment * (index + .5f)
            paint.color = if (done) theme.success else if (active) TaskUi.color(value, theme) else theme.secondary
            val radius = theme.dp(9) * if (done) pop else if (active) 1f + phase * .12f else .6f
            canvas.drawCircle(x, y, radius, paint)
            if (done) {
                paint.color = theme.onPrimary; paint.textSize = theme.dp(12).toFloat(); paint.textAlign = Paint.Align.CENTER
                canvas.drawText("✓", x, y + theme.dp(4), paint)
            }
            paint.textSize = 12 * resources.displayMetrics.scaledDensity
            paint.textAlign = Paint.Align.CENTER; paint.color = if (done || active) theme.text else theme.secondary
            val title = when (item) { TaskStage.PARSING -> "解析"; TaskStage.DOWNLOADING -> "下载"; TaskStage.MERGING -> "合并"; TaskStage.SAVING -> "保存"; TaskStage.READING -> "读取"; TaskStage.ENCODING -> "编码" }
            canvas.drawText(title, x, theme.dp(62).toFloat(), paint)
        }
    }
}

private fun TextView.setTextIfChanged(value: CharSequence) {
    if (!TextUtils.equals(text, value)) text = value
}
