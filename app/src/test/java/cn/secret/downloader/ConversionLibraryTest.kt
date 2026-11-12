package cn.secret.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversionLibraryTest {
    private val entries = listOf(
        MediaItem("video", title = "下载视频", downloadedAt = 90, sizeBytes = 900, favorite = true),
        MediaItem("gif-b", title = "动图 B", type = MediaType.GIF, downloadedAt = 20, sizeBytes = 300),
        MediaItem("live", title = "实况", type = MediaType.LIVE_PHOTO, downloadedAt = 30, sizeBytes = 200, favorite = true),
        MediaItem("gif-a", title = "动图 A", type = MediaType.GIF, downloadedAt = 20, sizeBytes = 100, favorite = true),
    )

    @Test fun everyWorkFilterAlwaysExcludesDownloadedVideos() {
        val expected = mapOf(WorkFilter.ALL to listOf("live", "gif-a", "gif-b"),
            WorkFilter.GIF to listOf("gif-a", "gif-b"), WorkFilter.LIVE to listOf("live"),
            WorkFilter.FAVORITE to listOf("live", "gif-a"))
        for ((filter, ids) in expected) assertEquals(ids, WorkQueries.select(entries, filter, WorkSort.CREATED).map { it.id })
    }

    @Test fun fileSizeSortingIsDescendingAndCreationTiesStayStable() {
        assertEquals(listOf("gif-b", "live", "gif-a"), WorkQueries.select(entries, WorkFilter.ALL, WorkSort.SIZE).map { it.id })
        assertEquals(listOf("gif-a", "gif-b"), WorkQueries.select(entries.reversed(), WorkFilter.GIF, WorkSort.CREATED).map { it.id })
    }

    @Test fun emptyAndVideoOnlySourcesKeepAnEmptyCreativeLibrary() {
        assertTrue(WorkQueries.select(emptyList(), WorkFilter.ALL, WorkSort.CREATED).isEmpty())
        assertTrue(WorkQueries.select(entries.take(1), WorkFilter.FAVORITE, WorkSort.SIZE).isEmpty())
    }
}
