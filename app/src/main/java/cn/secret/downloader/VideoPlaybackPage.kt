package cn.secret.downloader

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** One retained player serves the current video and its related videos. */
internal class VideoPlaybackPage(
    private val activity: Activity,
    private val theme: UiTheme,
    private val media: MediaRepository,
    tasks: TaskRepository,
    initial: MediaItem,
    private val onBack: () -> Unit,
    onConvert: (MediaItem, Boolean, Long) -> Unit,
    private val onPresentationChanged: () -> Unit,
    initialStartMs: Long? = null,
) : FrameLayout(activity) {
    private val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private lateinit var information: VideoInfoView
    private val infoScroll = ScrollView(activity).apply { id = R.id.video_info_scroll; isFillViewport = false }
    private var inPip = false
    val player: LocalVideoPlayerView = LocalVideoPlayerView(activity, theme, media, onBack, { showMore(player.currentItem ?: initial) },
        onItemChanged = { information.bind(it) },
        onRedownload = { item ->
            item.sourceUrl?.let { tasks.create(TaskType.DOWNLOAD, item.title, it) }
            Toast.makeText(activity, "已创建下载任务", Toast.LENGTH_SHORT).show()
        },
        onFullscreenChanged = { updatePresentation(); onPresentationChanged() })
    val isImmersive: Boolean get() = player.isFullscreen || inPip

    init {
        tag = "route_PLAY_VIDEO"
        setBackgroundColor(theme.background)
        information = VideoInfoView(activity, theme, media, initial, ::switchVideo, onConvert, ::showMore, player::positionMs)
        infoScroll.addView(information)
        column.addView(player, LinearLayout.LayoutParams(-1, 1))
        column.addView(infoScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(column, LayoutParams(-1, -1))
        player.setMedia(initial)
        if (initialStartMs != null) player.post { player.playbackEngine?.seekTo(initialStartMs.coerceIn(0, initial.durationMs)) }
    }

    private fun switchVideo(item: MediaItem) {
        player.setMedia(item)
        information.bind(item)
        infoScroll.smoothScrollTo(0, 0)
        UiMotion.enter(player, 0)
    }

    private fun updatePresentation() {
        infoScroll.visibility = if (isImmersive) View.GONE else View.VISIBLE
        // Fullscreen must be pure black edge to edge; a light page colour is what showed beside the notch.
        val background = if (isImmersive) android.graphics.Color.BLACK else theme.background
        column.setBackgroundColor(background)
        setBackgroundColor(background)
        val desiredHeight = if (isImmersive) LayoutParams.MATCH_PARENT else (width * 9f / 16f).toInt().coerceAtLeast(1)
        if (player.layoutParams?.height != desiredHeight) player.layoutParams = LinearLayout.LayoutParams(-1, desiredHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updatePresentation()
    }

    fun onConfigurationChanged() { player.onHostConfigurationChanged(); updatePresentation() }
    fun onPipChanged(value: Boolean) {
        inPip = value
        player.onPictureInPictureModeChanged(value)
        updatePresentation()
        onPresentationChanged()
    }

    private fun showMore(item: MediaItem) {
        AlertDialog.Builder(activity).setTitle(item.title).setItems(arrayOf("分享视频文件", "复制来源链接", "用其他应用打开")) { _, index ->
            when (index) {
                0 -> information.shareFile(item)
                1 -> item.sourceUrl?.let {
                    (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("来源链接", it))
                    Toast.makeText(activity, "已复制来源链接", Toast.LENGTH_SHORT).show()
                } ?: Toast.makeText(activity, "没有来源链接", Toast.LENGTH_SHORT).show()
                2 -> runCatching {
                    val source = Uri.parse(requireNotNull(item.localUri) { "文件尚未关联" })
                    val uri = if (source.scheme == "file") {
                        val file = File(requireNotNull(source.path)).canonicalFile
                        require(file.path.startsWith(activity.cacheDir.canonicalPath + File.separator)) { "请从系统文件选择器打开此视频" }
                        FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
                    } else source.also { require(it.scheme == "content") { "该文件不能外部打开" } }
                    activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri(item.title, uri) })
                }.onFailure { Toast.makeText(activity, it.message ?: "没有可用的播放器", Toast.LENGTH_SHORT).show() }
            }
        }.setNegativeButton("取消", null).show()
    }
}
