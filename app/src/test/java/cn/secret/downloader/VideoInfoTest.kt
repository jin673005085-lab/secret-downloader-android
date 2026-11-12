package cn.secret.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoInfoTest {
    @Test fun relatedVideosPreferSameAuthorAndThenRecentDownloadsWithoutOtherFormatsOrCurrentItem() {
        val current = MediaItem("current", title = "当前", author = "作者", downloadedAt = 100)
        val items = listOf(current,
            MediaItem("old-author", title = "较早同作者", author = "作者", downloadedAt = 1),
            MediaItem("recent", title = "最新其他作者", author = "其他", downloadedAt = 300),
            MediaItem("new-author", title = "较新同作者", author = "作者", downloadedAt = 2),
            MediaItem("gif", title = "动图", author = "作者", type = MediaType.GIF, downloadedAt = 500),
            MediaItem("live", title = "实况", author = "作者", type = MediaType.LIVE_PHOTO, downloadedAt = 600))
        assertEquals(listOf("new-author", "old-author", "recent"), VideoInfoQueries.related(items, current).map { it.id })
        assertEquals(listOf("new-author"), VideoInfoQueries.related(items, current, 1).map { it.id })
        assertEquals(emptyList<MediaItem>(), VideoInfoQueries.related(items, current, 0))
        assertEquals(listOf("recent", "new-author", "old-author"),
            VideoInfoQueries.related(items, current.copy(author = null)).map { it.id })
    }

    @Test fun sourceActionsAcceptOnlyWebLinksAndKeepUnsafeSchemesOutOfSharingAndBrowserActions() {
        assertEquals("https://example.com/video?a=1", VideoInfoQueries.source(" https://example.com/video?a=1 "))
        assertEquals("HTTP://example.com/video", VideoInfoQueries.source("HTTP://example.com/video"))
        for (source in listOf(null, "", "file:///data/local/video.mp4", "content://media/1", "javascript:alert(1)",
            "https:relative", "https://user:password@example.com/video", "https://example.com\\@evil.test")) {
            assertNull(source, VideoInfoQueries.source(source))
        }
    }
}
