package cn.secret.downloader

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

private const val PICK_INSTAGRAM_COOKIES = 740

class MainActivity : Activity() {
    private lateinit var navigation: NavigationShell
    private lateinit var input: EditText
    private lateinit var theme: UiTheme
    private lateinit var repository: TaskRepository
    private lateinit var mediaRepository: MediaRepository
    private lateinit var currentTasks: LinearLayout
    private lateinit var storageNumber: TextView
    private lateinit var storageDetail: TextView
    private lateinit var storageFill: JellyProgressBar
    private var tasksSubscription: java.io.Closeable? = null
    private var mediaDialog: AlertDialog? = null
    private var previewJob: DownloadJob? = null
    private val taskRows = linkedMapOf<String, Triple<TextView, TextView, JellyProgressBar>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme = UiTheme(this)
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            (if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0))
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        @Suppress("DEPRECATION")
        run { window.statusBarColor = android.graphics.Color.TRANSPARENT; window.navigationBarColor = android.graphics.Color.TRANSPARENT }
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        repository = TaskRepository.get(this)
        mediaRepository = MediaRepository.get(this)
        val tabs = listOf(buildDownloadPage(), buildConversionPage(), buildLibraryPage())
        navigation = NavigationShell(this, theme, tabs, ::buildSecondaryPage)
        setContentView(navigation)
        navigation.restoreState(savedInstanceState?.getBundle("navigation"))
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (!navigation.back()) finish()
            }
        }
        if (savedInstanceState == null) receiveIntent(intent)
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 701)
    }

    @Deprecated("Older Android versions dispatch Back through this callback")
    @SuppressLint("GestureBackNavigation") // API 33+ uses the OnBackInvokedDispatcher registered above.
    override fun onBackPressed() {
        if (!navigation.back()) super.onBackPressed()
    }

    private fun pageContent() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(UiTheme.PAGE_PADDING), dp(UiTheme.PAGE_PADDING), dp(UiTheme.PAGE_PADDING), dp(UiTheme.PAGE_PADDING))
        clipChildren = false
    }

    private fun scrollPage(content: LinearLayout, pageTag: String) = ScrollView(this).apply {
        tag = pageTag
        isFillViewport = true
        clipToPadding = false
        isVerticalScrollBarEnabled = false
        setBackgroundColor(theme.background)
        addView(content)
    }

    private fun buildDownloadPage(): ScrollView {
        val layout = pageContent()
        layout.addView(pageHeader("下载仪表盘"))

        val hero = card()
        hero.addView(pill("下载与转换 · 实时任务进度", theme.primaryText, theme.primarySoft),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        hero.addView(row().apply {
            gravity = Gravity.TOP
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("从一个链接开始", UiTheme.HEADING, true))
                addView(label("粘贴链接，或接收分享文字", UiTheme.LABEL), smallSpaced())
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(ImageButton(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_download)
                    background = theme.control(theme.primary, UiTheme.PILL_RADIUS)
                    backgroundTintList = null
                    contentDescription = "开始下载"
                    elevation = dp(UiTheme.ACTION_ELEVATION).toFloat()
                    setOnClickListener { UiMotion.haptic(this); submit(input.text.toString()) }
                    UiMotion.press(this)
                }, LinearLayout.LayoutParams(dp(UiTheme.PRIMARY_ACTION), dp(UiTheme.PRIMARY_ACTION)))
                addView(label("开始下载", UiTheme.LABEL).apply {
                    setTextColor(theme.primaryText)
                    gravity = Gravity.CENTER
                }, smallSpaced())
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(UiTheme.SMALL_GAP)
            })
        }, spaced())
        input = EditText(this).apply {
            id = R.id.share_input
            hint = "粘贴图片、视频链接或整段分享文字…"
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            textSize = UiTheme.BODY
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(theme.text)
            setHintTextColor(theme.secondary)
            background = theme.shape(theme.muted, UiTheme.CONTROL_RADIUS)
            val padding = dp(UiTheme.CONTROL_PADDING)
            setPaddingRelative(padding, padding, padding + dp(UiTheme.TOUCH_TARGET), padding)
            contentDescription = "下载链接或分享文字"
        }
        hero.addView(FrameLayout(this).apply {
            addView(input, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            addView(ImageButton(this@MainActivity).apply {
                setImageResource(R.drawable.ic_close)
                background = theme.control(theme.muted, UiTheme.PILL_RADIUS)
                backgroundTintList = null
                contentDescription = "清空链接输入"
                setOnClickListener {
                    UiMotion.haptic(this)
                    input.text.clear()
                    input.error = null
                }
                UiMotion.press(this)
            }, FrameLayout.LayoutParams(dp(UiTheme.TOUCH_TARGET), dp(UiTheme.TOUCH_TARGET), Gravity.TOP or Gravity.END).apply {
                marginEnd = dp(UiTheme.SMALL_GAP)
                topMargin = dp(UiTheme.SMALL_GAP)
            })
        }, spaced())
        layout.addView(hero, spaced())

        val status = card()
        status.addView(row().apply {
            addView(label("当前任务", UiTheme.BODY, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(routeButton("全部任务  ›", ScreenRoute.TASK_CENTER).apply {
                tag = "open_task_center"
                setPadding(dp(12), dp(6), dp(12), dp(6))
            })
        })
        currentTasks = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status.addView(currentTasks, spaced())
        layout.addView(status, spaced())

        val storage = card()
        storage.addView(label("本机存储", UiTheme.BODY, true))
        storage.addView(row().apply {
            storageNumber = label("—", UiTheme.NUMBER, true)
            addView(storageNumber)
            addView(label("GB 可用", UiTheme.LABEL), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(UiTheme.SMALL_GAP) })
        }, smallSpaced())
        storageFill = JellyProgressBar(this, theme, 6)
        storage.addView(storageFill, LinearLayout.LayoutParams(-1, dp(16)).apply { topMargin = dp(10) })
        storageDetail = label("", UiTheme.LABEL)
        storage.addView(storageDetail, smallSpaced())
        layout.addView(storage, spaced())
        layout.addView(label("保存到 ${MediaPublisher.DISPLAY_PATH}", UiTheme.LABEL).apply { setTextIsSelectable(true) }, spaced())
        return scrollPage(layout, "tab_download")
    }

    private fun pageHeader(title: String) = row().apply {
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("不能说的秘密", UiTheme.LABEL))
            addView(label(title, UiTheme.TITLE, true))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(ImageButton(this@MainActivity).apply {
            tag = "open_SETTINGS"
            setImageResource(R.drawable.ic_settings)
            background = theme.control(theme.surface, UiTheme.PILL_RADIUS)
            backgroundTintList = null
            contentDescription = "自定义下载设置"
            setOnClickListener { UiMotion.haptic(this); openRoute(ScreenRoute.SETTINGS) }
            UiMotion.press(this)
        }, LinearLayout.LayoutParams(dp(UiTheme.TOUCH_TARGET), dp(UiTheme.TOUCH_TARGET)))
    }

    private fun buildConversionPage(): View = ConversionLibraryView(this, theme, mediaRepository, repository,
        onCreate = { gif, source -> navigation.openOnTab(1, if (gif) ScreenRoute.CREATE_GIF else ScreenRoute.CREATE_LIVE,
            shared = source?.let { listOf(it to "creator_cover") }.orEmpty()) },
        onTaskCenter = { openRoute(ScreenRoute.TASK_CENTER, "CONVERSION") },
        onOpen = ::openWork, onSettings = { openRoute(ScreenRoute.SETTINGS) }, scanOnAttach = true)

    private fun buildLibraryPage(): View = VideoLibraryView(this, theme, mediaRepository, repository,
        onDownload = { navigation.showDownloads() },
        onTaskCenter = { openTaskCenter(downloadOnly = true) },
        onPlay = ::openVideo,
        onConvert = { item, gif -> openCreation(item, gif, 0) },
        onSettings = { openRoute(ScreenRoute.SETTINGS) }, scanOnAttach = true)

    private fun openVideo(item: MediaItem, cover: View?) {
        navigation.openOnTab(2, ScreenRoute.PLAY_VIDEO, "media:${item.id}",
            cover?.let { listOf(it to "video_player_cover") }.orEmpty())
    }

    private fun openWork(item: MediaItem, playlist: List<MediaItem>, cover: View?) {
        val args = org.json.JSONObject().apply {
            put("id", item.id); put("ids", org.json.JSONArray(playlist.map { it.id }))
        }
        navigation.openOnTab(1, if (item.type == MediaType.GIF) ScreenRoute.VIEW_GIF else ScreenRoute.VIEW_LIVE, "work:$args",
            cover?.let { listOf(it to "work_viewer_cover") }.orEmpty())
    }

    private fun actionButton(title: String, action: () -> Unit) = label(title, UiTheme.BODY, true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(UiTheme.TOUCH_TARGET)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setTextColor(theme.primaryText)
        background = theme.control(theme.primarySoft)
        isFocusable = true
        setOnClickListener { UiMotion.haptic(this); action() }
        UiMotion.press(this)
    }

    private fun routeButton(title: String, route: ScreenRoute) = actionButton(title) { openRoute(route) }.apply {
        tag = "open_${route.name}"
    }

    internal fun openTaskCenter(downloadOnly: Boolean = false) = openRoute(ScreenRoute.TASK_CENTER, if (downloadOnly) "DOWNLOAD" else null)

    private fun openTask(task: Task) {
        val shared = listOf("task_cover_${task.id}" to "detail_cover", "task_progress_${task.id}" to "detail_progress")
            .mapNotNull { (source, target) -> visibleTagged(window.decorView, source)?.let { it to target } }
        navigation.open(ScreenRoute.TASK_DETAIL, taskId = task.id, shared = shared)
    }

    private fun openOutput(task: Task) {
        mediaRepository.items.firstOrNull { it.taskId == task.id }?.let { item ->
            if (item.type == MediaType.VIDEO) openVideo(item, null)
            else openWork(item, mediaRepository.items.filter { it.type != MediaType.VIDEO }, null)
            return
        }
        task.outputUri?.let { address ->
            val uri = android.net.Uri.parse(address)
            val mime = runCatching { contentResolver.getType(uri) }.getOrNull() ?: "image/*"
            runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.onFailure { toast("未找到可打开这个文件的应用") }
            return
        }
        val route = when (task.type) {
            TaskType.DOWNLOAD -> ScreenRoute.PLAY_VIDEO
            TaskType.GIF -> ScreenRoute.VIEW_GIF
            TaskType.LIVE_PHOTO -> ScreenRoute.VIEW_LIVE
        }
        navigation.openOnTab(if (task.type == TaskType.DOWNLOAD) 2 else 1, route, task.id)
    }

    private fun openTaskMedia(output: TaskOutput) {
        val item = output.mediaId?.let(mediaRepository::get)
        when (item?.type) {
            MediaType.VIDEO -> navigation.open(ScreenRoute.PLAY_VIDEO, taskId = "media:${item.id}")
            MediaType.GIF, MediaType.LIVE_PHOTO -> {
                val args = JSONObject().put("id", item.id).put("ids", JSONArray().put(item.id))
                navigation.open(if (item.type == MediaType.GIF) ScreenRoute.VIEW_GIF else ScreenRoute.VIEW_LIVE, taskId = "work:$args")
            }
            null -> {
                val uri = android.net.Uri.parse(output.uri)
                val mime = when (output.kind) {
                    TaskOutputKind.VIDEO -> "video/*"
                    TaskOutputKind.GIF -> "image/gif"
                    else -> "image/*"
                }
                runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.onFailure { toast("未找到可预览这个文件的应用") }
            }
        }
    }

    private fun visibleTagged(view: View, tag: String): View? {
        if (!view.isShown) return null
        if (view.tag == tag) return view
        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
            visibleTagged(view.getChildAt(index), tag)?.let { return it }
        }
        return null
    }

    private fun secondaryHeader(title: String) = row().apply {
        addView(ImageButton(this@MainActivity).apply {
            tag = "route_back"
            setImageResource(R.drawable.ic_back)
            imageTintList = android.content.res.ColorStateList.valueOf(theme.text)
            background = theme.control(theme.surface, UiTheme.PILL_RADIUS)
            backgroundTintList = null
            contentDescription = "返回"
            setOnClickListener { UiMotion.haptic(this); navigation.back() }
            UiMotion.press(this)
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(label(title, UiTheme.HEADING, true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14) })
    }

    private fun renderTasks(tasks: List<Task>) {
        val active = tasks.filter { it.isOngoing }
        navigation.updateBadges(active.count { it.type == TaskType.DOWNLOAD }, active.count { it.type != TaskType.DOWNLOAD })
        val preview = active.take(3)
        if (taskRows.keys.toList() != preview.map { it.id } || currentTasks.childCount == 0) {
            currentTasks.removeAllViews()
            taskRows.clear()
            if (preview.isEmpty()) currentTasks.addView(label("粘贴链接或分享文字，即可添加任务。", UiTheme.BODY))
            preview.forEach { task ->
                val title = label(task.title, UiTheme.BODY, true).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                }
                val status = label("", UiTheme.LABEL)
                val progress = JellyProgressBar(this, theme, 6).apply { tag = "task_progress_${task.id}" }
                currentTasks.addView(LinearLayout(this).apply {
                    tag = "home_task_${task.id}"
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                    isFocusable = true
                    setOnClickListener { repository.get(task.id)?.let(::openTask) }
                    UiMotion.press(this)
                    addView(title)
                    addView(status, smallSpaced())
                    addView(progress, LinearLayout.LayoutParams(-1, dp(16)))
                })
                taskRows[task.id] = Triple(title, status, progress)
            }
        }
        preview.forEach { task ->
            taskRows[task.id]?.let { (title, status, progress) ->
                if (title.text.toString() != task.title) title.text = task.title
                val summary = TaskUi.summary(task)
                if (status.text.toString() != summary) status.text = summary
                progress.bind(task)
            }
        }
    }

    private fun openRoute(route: ScreenRoute, taskId: String? = null) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        navigation.open(route, taskId = taskId)
    }

    private fun buildSecondaryPage(route: ScreenRoute, taskId: String?): View {
        if (route == ScreenRoute.TASK_DETAIL && taskId != null) {
            return TaskDetailView(this, theme, repository, taskId, { navigation.back() }, ::openOutput, ::openTaskMedia)
        }
        if (route == ScreenRoute.TASK_CENTER) {
            return LinearLayout(this).apply {
                tag = "route_TASK_CENTER"
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(theme.background)
                addView(secondaryHeader(route.title), LinearLayout.LayoutParams(-1, -2).apply {
                    setMargins(dp(20), dp(20), dp(20), dp(14))
                })
                addView(TaskCenterView(this@MainActivity, theme, repository, ::openTask, ::openOutput).apply {
                    if (taskId == "DOWNLOAD") filterDownloads()
                    if (taskId == "CONVERSION") filterConversions()
                }, LinearLayout.LayoutParams(-1, 0, 1f))
            }
        }
        val params = taskId?.takeIf { it.startsWith("conversion:") || it.startsWith("play:") || it.startsWith("work:") }
            ?.substringAfter(':')?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
        val editWork = taskId?.takeIf { it.startsWith("edit:") }?.removePrefix("edit:")?.let(mediaRepository::get)
        val selectedVideo = (taskId?.takeIf { it.startsWith("media:") }?.removePrefix("media:")
            ?: params?.optString("mediaId")?.takeIf { it.isNotBlank() } ?: editWork?.sourceMediaId)?.let(mediaRepository::get)
        if (route == ScreenRoute.CREATE_GIF || route == ScreenRoute.CREATE_LIVE) {
            return ConversionCreatorView(this, theme, mediaRepository, repository,
                if (route == ScreenRoute.CREATE_GIF) TaskType.GIF else TaskType.LIVE_PHOTO,
                selectedVideo, params?.optLong("startMs") ?: 0L, editWork, { navigation.back() }, { task ->
                    openTask(task)
                    requestNotificationPermission()
                })
        }
        if (route == ScreenRoute.VIEW_GIF || route == ScreenRoute.VIEW_LIVE) {
            val item = params?.optString("id")?.let(mediaRepository::get)
            if (item != null) {
                val ids = params.optJSONArray("ids")
                val playlist = if (ids == null) listOf(item) else (0 until ids.length()).mapNotNull { mediaRepository.get(ids.getString(it)) }
                return WorkViewerView(this, theme, mediaRepository, item, playlist, { navigation.back() },
                    { work -> navigation.openOnTab(1, if (work.type == MediaType.GIF) ScreenRoute.CREATE_GIF else ScreenRoute.CREATE_LIVE, "edit:${work.id}") },
                    { source, start -> navigation.openOnTab(2, ScreenRoute.PLAY_VIDEO,
                        "play:" + org.json.JSONObject().put("mediaId", source.id).put("startMs", start)) })
            }
        }
        if (route == ScreenRoute.PLAY_VIDEO && selectedVideo != null) {
            return VideoPlaybackPage(this, theme, mediaRepository, repository, selectedVideo,
                { navigation.back() }, { item, gif, position -> openCreation(item, gif, position) },
                { navigation.refreshPresentation() }, params?.takeIf { taskId.startsWith("play:") }?.optLong("startMs"))
        }
        val body = pageContent()
        body.addView(secondaryHeader(route.title))
        if (route == ScreenRoute.PLAY_VIDEO && selectedVideo != null) {
            body.addView(android.widget.ImageView(this).apply {
                tag = "video_player_cover"
                setImageDrawable(VideoCoverDrawable(selectedVideo, theme))
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                background = theme.shape(theme.muted, 20)
                clipToOutline = true
                contentDescription = selectedVideo.title
            }, LinearLayout.LayoutParams(-1, ((resources.displayMetrics.widthPixels - dp(40)) /
                (selectedVideo.width.toFloat() / selectedVideo.height.coerceAtLeast(1)).coerceIn(.75f, 16f / 9)).toInt()).apply { topMargin = dp(14) })
        }
        val content = card()
        if (route == ScreenRoute.SETTINGS) buildSettings(content) else {
            if (selectedVideo != null) content.addView(label("已选择：${selectedVideo.title}", UiTheme.BODY, true), smallSpaced())
            content.addView(pill("即将开放", theme.primaryText, theme.primarySoft))
            content.addView(label(route.title, UiTheme.HEADING, true), spaced())
            content.addView(label(when (route) {
                ScreenRoute.TASK_CENTER -> "在这里查看下载与转换任务。"
                ScreenRoute.TASK_DETAIL -> "在这里查看任务进度和保存信息。"
                ScreenRoute.CREATE_GIF -> "从视频开始，制作你的 GIF。"
                ScreenRoute.CREATE_LIVE -> "从视频开始，选择封面并制作实况。"
                ScreenRoute.VIEW_GIF -> "在这里查看和分享 GIF 作品。"
                ScreenRoute.VIEW_LIVE -> "在这里查看和分享实况作品。"
                ScreenRoute.PLAY_VIDEO -> "在这里观看下载的视频。"
                ScreenRoute.SETTINGS -> ""
            }, UiTheme.BODY), smallSpaced())
        }
        body.addView(content, spaced())
        return scrollPage(body, "route_${route.name}")
    }

    override fun onStart() {
        super.onStart()
        updateStorage()
        tasksSubscription = repository.observeAll(::renderTasks)
    }

    override fun onStop() {
        tasksSubscription?.close(); tasksSubscription = null
        super.onStop()
    }

    override fun onPause() {
        navigation.activeWorkViewer()?.stopForBackground()
        val player = navigation.activePlaybackPage()?.player
        if ((Build.VERSION.SDK_INT < 26 || !isInPictureInPictureMode) && player?.isEnteringPictureInPicture != true) {
            player?.pauseForBackground()
        }
        super.onPause()
    }

    override fun onDestroy() {
        mediaDialog?.dismiss()
        previewJob?.let { job -> Thread(job::cancel, "cancel-media-preview").start() }
        previewJob = null
        navigation.releasePlayers()
        super.onDestroy()
    }

    @Deprecated("The native document picker returns through this Activity callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == PICK_INSTAGRAM_COOKIES) {
            if (resultCode == RESULT_OK) data?.data?.let { uri ->
                Thread {
                    val result = runCatching { InstagramCookies.import(applicationContext, uri) }
                    runOnUiThread {
                        if (!isDestroyed) toast(result.fold({ "已导入 Instagram Cookie" }, {
                            if (it is IllegalArgumentException) it.message.orEmpty() else "无法导入 Instagram Cookie 文件"
                        }))
                    }
                }.start()
            }
            return
        }
        if (navigation.activeCreator()?.onActivityResult(requestCode, resultCode, data) == true) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        navigation.activePlaybackPage()?.onConfigurationChanged()
        navigation.requestApplyInsets()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        navigation.activePlaybackPage()?.onPipChanged(isInPictureInPictureMode)
    }

    private fun openCreation(item: MediaItem, gif: Boolean, position: Long) {
        val params = org.json.JSONObject().apply {
            put("mediaId", item.id); put("startMs", position.coerceAtLeast(0)); put("durationMs", if (gif) 5_000 else item.durationMs)
        }
        navigation.openOnTab(1, if (gif) ScreenRoute.CREATE_GIF else ScreenRoute.CREATE_LIVE, "conversion:$params")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle("navigation", navigation.saveState())
        super.onSaveInstanceState(outState)
    }

    private fun receiveIntent(incoming: Intent?) {
        incoming?.getStringExtra("task_id")?.let { id ->
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val open = object : Runnable {
                override fun run() {
                    if (isFinishing || isDestroyed) return
                    if (!repository.isLoaded || !mediaRepository.isLoaded) { handler.postDelayed(this, 100); return }
                    repository.get(id)?.let { task -> if (incoming.getBooleanExtra("open_output", false) && task.status == TaskStatus.COMPLETED)
                        openOutput(task) else openTask(task) }
                }
            }
            handler.post(open)
            return
        }
        val text = when (incoming?.action) {
            Intent.ACTION_SEND -> incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                ?: incoming.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            Intent.ACTION_VIEW -> incoming.dataString
            else -> null
        }?.takeIf { it.isNotBlank() } ?: return
        navigation.showDownloads()
        input.setText(text)
        submit(text)
    }

    private fun submit(text: String) {
        val parsed = try { InputParser.parse(text) } catch (error: IllegalArgumentException) {
            input.error = error.message
            toast(error.message ?: "没有找到可下载的链接")
            return
        }
        input.error = null
        if (parsed.platform !in setOf(Platform.DOUYIN, Platform.TWITTER, Platform.INSTAGRAM)) {
            enqueueDownload(parsed)
            return
        }
        if (mediaDialog?.isShowing == true) return
        val id = "preview-${UUID.randomUUID()}"
        val job = DownloadJob(id, onCancelNative = { DownloadEngine.cancelPreviewProcess(id) })
        previewJob = job
        val settings = DownloadSettings.load(this)
        val progress = AlertDialog.Builder(this).setTitle("正在解析媒体")
            .setMessage("正在获取作品中的图片和视频…")
            .setNegativeButton("取消") { dialog, _ -> dialog.cancel() }.create()
        progress.setOnCancelListener {
            previewJob = null
            Thread(job::cancel, "cancel-media-preview").start()
        }
        mediaDialog = progress
        progress.show()
        Thread({
            val result = runCatching {
                job.addThread(Thread.currentThread())
                try { DownloadEngine.resolveMedia(applicationContext, parsed, settings, job) }
                finally { job.removeThread(Thread.currentThread()) }
            }
            runOnUiThread {
                if (previewJob !== job || isFinishing || isDestroyed) return@runOnUiThread
                previewJob = null
                progress.dismiss()
                result.fold(onSuccess = { items ->
                    val only = items.singleOrNull()
                    if (only != null && only.kind == "视频") enqueueDownload(parsed, listOf(only.selectionKey))
                    else showMediaPicker(items) { selection -> enqueueDownload(parsed, selection) }
                }, onFailure = { error ->
                    val reason = error.message.orEmpty().ifBlank { "请检查网络和链接是否可公开访问" }.take(500)
                    val dialog = AlertDialog.Builder(this).setTitle("解析失败").setMessage(reason)
                        .setNegativeButton("关闭", null)
                    if (parsed.platform == Platform.TWITTER && error !is IllegalArgumentException &&
                        !java.net.URI(parsed.url).host.equals("pbs.twimg.com", true)) {
                        dialog.setMessage("$reason\n\n直接下载将沿用原流程，不支持勾选。")
                            .setPositiveButton("直接下载") { _, _ -> enqueueDownload(parsed) }
                    }
                    mediaDialog = dialog.show()
                })
            }
        }, "resolve-media-preview").start()
    }

    internal fun showMediaPicker(items: List<MediaSource>, onSelected: (List<String>) -> Unit): AlertDialog =
        MediaPicker(this, theme).show(items, onSelected).also { mediaDialog = it }

    private fun enqueueDownload(parsed: ParsedInput, selection: List<String>? = null) {
        val params = JSONObject().apply { if (selection != null) put("mediaSelection", JSONArray(selection)) }
        val task = repository.create(TaskType.DOWNLOAD, "${parsed.platform.name} 链接下载", parsed.url, params.toString())
        openTask(task)
        requestNotificationPermission()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 700)
    }

    private fun buildSettings(body: LinearLayout) {
        val current = DownloadSettings.load(this)
        body.addView(label("下载规格", UiTheme.BODY, true), spaced())
        val quality = Spinner(this).apply {
            id = R.id.setting_quality
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, DownloadSettings.QUALITY_LABELS)
            setSelection(DownloadSettings.QUALITIES.indexOf(current.quality))
            contentDescription = "下载规格"
            minimumHeight = dp(UiTheme.TOUCH_TARGET)
        }
        body.addView(quality)
        body.addView(label("按所选规格上限下载；不足时使用可用最高规格。", UiTheme.LABEL), spaced())
        val threadLabel = label("下载线程数：${current.threads}", UiTheme.BODY)
        body.addView(threadLabel, spaced())
        val threads = SeekBar(this).apply {
            id = R.id.setting_threads
            max = 15
            progress = current.threads - 1
            contentDescription = "下载线程数"
            minimumHeight = dp(UiTheme.TOUCH_TARGET)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    threadLabel.text = "下载线程数：${progress + 1}"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        body.addView(threads)
        body.addView(actionButton("保存设置") {
            DownloadSettings(DownloadSettings.QUALITIES[quality.selectedItemPosition], threads.progress + 1).save(this)
            toast("设置已保存，下次下载生效")
            navigation.back()
        }, spaced())
        body.addView(label("Instagram 登录 Cookie", UiTheme.BODY, true), spaced())
        body.addView(label("可选。选择自己账号导出的 Netscape 格式 Cookie 文件。", UiTheme.LABEL))
        body.addView(actionButton("导入 Instagram Cookie") {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*"), PICK_INSTAGRAM_COOKIES)
        }, smallSpaced())
        body.addView(actionButton("清除 Instagram Cookie") {
            Thread {
                InstagramCookies.clear(applicationContext)
                runOnUiThread { if (!isDestroyed) toast("已清除 Instagram Cookie") }
            }.start()
        }, smallSpaced())
    }

    private fun updateStorage() {
        try {
            @Suppress("DEPRECATION")
            val stats = StatFs(Environment.getExternalStorageDirectory().absolutePath)
            val fraction = (1f - stats.availableBytes.toFloat() / stats.totalBytes.coerceAtLeast(1)).coerceIn(0f, 1f)
            storageNumber.text = String.format(Locale.getDefault(), "%.1f", stats.availableBytes / 1_000_000_000.0)
            storageFill.setProgress(fraction, TaskStatus.RUNNING, "storage:$fraction")
            storageDetail.text = "已使用 ${(fraction * 100).toInt()}% · 与公共下载目录共用空间"
        } catch (_: IllegalArgumentException) {
            storageNumber.text = "—"
            storageFill.setProgress(0f, TaskStatus.PAUSED, "storage")
            storageDetail.text = "暂时无法读取存储空间"
        }
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = theme.shape(theme.surface)
        elevation = dp(UiTheme.CARD_ELEVATION).toFloat()
        setPadding(dp(UiTheme.CARD_PADDING), dp(UiTheme.CARD_PADDING), dp(UiTheme.CARD_PADDING), dp(UiTheme.CARD_PADDING))
    }

    private fun pill(value: String, color: Int, fill: Int) = label(value, UiTheme.LABEL).apply {
        setTextColor(color)
        background = theme.shape(fill, UiTheme.PILL_RADIUS)
        setPadding(dp(UiTheme.PILL_PADDING_X), dp(UiTheme.PILL_PADDING_Y), dp(UiTheme.PILL_PADDING_X), dp(UiTheme.PILL_PADDING_Y))
    }

    private fun label(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        setTextColor(if (bold) theme.text else theme.secondary)
    }
    private fun spaced() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(UiTheme.CARD_GAP) }
    private fun smallSpaced() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(UiTheme.SMALL_GAP) }
    private fun dp(value: Int) = theme.dp(value)
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
