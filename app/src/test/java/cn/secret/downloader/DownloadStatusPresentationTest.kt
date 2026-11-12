package cn.secret.downloader

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadStatusPresentationTest {
    @Test fun presentsExistingServiceMessagesWithoutHidingPartialFailures() {
        val cases = mapOf(
            "粘贴链接或分享文字，即可下载。" to DownloadStatusPresentation.Tone.NEUTRAL,
            "下载中 62%" to DownloadStatusPresentation.Tone.ACTIVE,
            "正在合并视频与音频…" to DownloadStatusPresentation.Tone.ACTIVE,
            "已加入下载队列，共 3 个任务" to DownloadStatusPresentation.Tone.ACTIVE,
            "下载完成：2 个文件\n已保存到 Download/不能说的秘密" to DownloadStatusPresentation.Tone.SUCCESS,
            "已保存 1 个文件；下载失败：服务器返回 HTTP 403" to DownloadStatusPresentation.Tone.ERROR,
            "下载失败：连接超时" to DownloadStatusPresentation.Tone.ERROR,
            "转换完成：1 个文件" to DownloadStatusPresentation.Tone.SUCCESS,
            "转换失败：无法解码视频" to DownloadStatusPresentation.Tone.ERROR,
            "系统已暂停长时间后台下载，请回到应用重新开始" to DownloadStatusPresentation.Tone.WARNING,
        )
        cases.forEach { (message, expected) -> assertEquals(message, expected, DownloadStatusPresentation.from(message).tone) }
    }
}
