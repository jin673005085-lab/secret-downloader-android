package cn.secret.downloader

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoLibraryUiTest {
    @Test fun relativeDownloadAgeUsesStableTimeBoundariesAndClampsFutureTimestamps() {
        val now = 2_000_000_000_000L
        assertEquals("下载于 刚刚", VideoLibraryUi.relativeDownloaded(now + 60_000, now))
        assertEquals("下载于 刚刚", VideoLibraryUi.relativeDownloaded(now - 59_999, now))
        assertEquals("下载于 1 分钟前", VideoLibraryUi.relativeDownloaded(now - 60_000, now))
        assertEquals("下载于 6 小时前", VideoLibraryUi.relativeDownloaded(now - 6 * 3_600_000, now))
        assertEquals("下载于 2 天前", VideoLibraryUi.relativeDownloaded(now - 2 * 86_400_000, now))
        assertEquals("下载于 ${VideoLibraryUi.downloaded(now - 40L * 86_400_000).substringBefore(' ')}",
            VideoLibraryUi.relativeDownloaded(now - 40L * 86_400_000, now))
    }
}
