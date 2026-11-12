package cn.secret.downloader

/** Presentation of the existing service message; does not own task state. */
internal data class DownloadStatusPresentation(val label: String, val tone: Tone) {
    enum class Tone { NEUTRAL, ACTIVE, SUCCESS, WARNING, ERROR }

    companion object {
        fun from(message: String): DownloadStatusPresentation = when {
            "下载失败" in message || "转换失败" in message -> DownloadStatusPresentation("失败", Tone.ERROR)
            "超时" in message || "暂停" in message || "时限" in message -> DownloadStatusPresentation("需留意", Tone.WARNING)
            message.startsWith("下载完成") || message.startsWith("转换完成") -> DownloadStatusPresentation("已完成", Tone.SUCCESS)
            message.startsWith("粘贴链接") -> DownloadStatusPresentation("等待链接", Tone.NEUTRAL)
            else -> DownloadStatusPresentation("处理中", Tone.ACTIVE)
        }
    }
}
