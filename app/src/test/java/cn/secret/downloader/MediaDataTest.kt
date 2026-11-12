package cn.secret.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDataTest {
    private val fixtures = FakeMediaItems.create(2_000_000_000_000L)
    private fun query(search: String = "", sort: MediaSort = MediaSort.RECENT_DOWNLOAD, filter: MediaFilter = MediaFilter.ALL,
                      type: MediaType = MediaType.VIDEO, offset: Int = 0, limit: Int = 40) =
        MediaQueries.query(fixtures, search, sort, filter, type, offset, limit)

    @Test fun twentyVideoFixturesCoverAspectWatchAndMissingStatesWithoutRealFiles() {
        val videos = fixtures.filter { it.type == MediaType.VIDEO }
        assertEquals(20, videos.size)
        assertEquals(fixtures.size, fixtures.map { it.id }.toSet().size)
        assertEquals(18, videos.count { it.exists })
        assertEquals(2, videos.count { !it.exists })
        assertEquals(1, videos.count { !it.exists && it.sourceUrl != null })
        assertEquals(1, videos.count { !it.exists && it.sourceUrl == null })
        assertTrue(fixtures.all { it.localUri == null && it.coverUri == null })
        assertTrue(videos.all { it.width.toDouble() / it.height in .75..(16.0 / 9.0) })
        assertTrue(videos.any { it.title.length > 40 })
        assertTrue(videos.any { it.isNew })
        assertTrue(videos.any { it.isPartiallyWatched })
        assertTrue(videos.any { it.isFinished })
    }

    @Test fun queryAlwaysSeparatesVideoFromGifAndLivePhoto() {
        assertEquals(20, query().size)
        assertTrue(query().all { it.type == MediaType.VIDEO })
        assertEquals(listOf("media-gif-01"), query(type = MediaType.GIF).map { it.id })
        assertEquals(listOf("media-live-01"), query(type = MediaType.LIVE_PHOTO).map { it.id })
    }

    @Test fun searchNormalizesCaseAndWidthAndMatchesAuthorPlatformAndAllWords() {
        assertTrue(query("ＹＯＵＴＵＢＥ").isNotEmpty())
        assertTrue(query("ＹＯＵＴＵＢＥ").all { it.platform == "YouTube" })
        assertEquals(listOf("media-video-03"), query("  东京   LENA ").map { it.id })
        assertTrue(query("小林").all { it.author == "小林的镜头" })
        assertTrue(query("不存在的检索内容").isEmpty())
    }

    @Test fun filtersKeepUnseenAndFinishedOutOfPartiallyWatched() {
        assertEquals(5, query(filter = MediaFilter.FAVORITES).size)
        assertTrue(query(filter = MediaFilter.FAVORITES).all { it.favorite })
        assertEquals(4, query(filter = MediaFilter.WATCH_LATER).size)
        val unfinished = query(filter = MediaFilter.UNFINISHED)
        assertEquals(10, unfinished.size)
        assertTrue(unfinished.all { it.lastPositionMs > 0 && it.lastPositionMs < it.durationMs })
        assertFalse(unfinished.any { it.isNew || it.isFinished })
    }

    @Test fun sortsAreDescendingWithStableTiesAndPagingDoesNotDuplicateItems() {
        val first = query(limit = 5)
        val second = query(offset = 5, limit = 5)
        assertEquals(query(limit = 10), first + second)
        assertEquals(10, (first + second).map { it.id }.toSet().size)
        for (sort in MediaSort.entries) {
            val sorted = query(sort = sort)
            val values = sorted.map { when (sort) {
                MediaSort.RECENT_DOWNLOAD -> it.downloadedAt
                MediaSort.RECENT_PLAYED -> it.lastPlayedAt ?: Long.MIN_VALUE
                MediaSort.DURATION -> it.durationMs
                MediaSort.SIZE -> it.sizeBytes
            } }
            assertEquals(values.sortedDescending(), values)
        }
        val tied = listOf(MediaItem("z", title = "同一时刻", downloadedAt = 1), MediaItem("a", title = "同一时刻", downloadedAt = 1))
        assertEquals(listOf("a", "z"), MediaQueries.query(tied, "", MediaSort.RECENT_DOWNLOAD,
            MediaFilter.ALL, MediaType.VIDEO, 0, 40).map { it.id })
        assertTrue(query(offset = Int.MAX_VALUE).isEmpty())
        assertTrue(query(limit = 0).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { query(offset = -1) }
        assertThrows(IllegalArgumentException::class.java) { query(limit = -1) }
    }

    @Test fun playbackClampsPositionAndCountsStartsWithoutCountingEveryPositionUpdate() {
        val item = MediaItem("video", title = "播放测试", durationMs = 10_000)
        val started = MediaQueries.playback(item, 4_000, 100, true)
        assertEquals(1, started.playCount)
        assertEquals(100L, started.lastPlayedAt)
        assertTrue(started.isPartiallyWatched)
        assertFalse(started.isNew)
        val completed = MediaQueries.playback(started, 20_000, 200, false)
        assertEquals(1, completed.playCount)
        assertEquals(10_000L, completed.lastPositionMs)
        assertTrue(completed.isFinished)
        assertEquals(0L, MediaQueries.playback(item, -1, 100, false).lastPositionMs)
        assertEquals(Int.MAX_VALUE, MediaQueries.playback(item.copy(playCount = Int.MAX_VALUE), 0, 100, true).playCount)
        assertFalse(item.copy(lastPositionMs = 8_999).isFinished)
        assertTrue(item.copy(lastPositionMs = 9_000).isFinished)
        assertFalse(item.copy(lastPositionMs = 9_000).isPartiallyWatched)
    }

    @Test fun metadataSerializationKeepsOptionalUrisLargeSizesAndPlaybackFlags() {
        val item = MediaItem("媒体", taskId = "关联任务", title = "引号\"与换行\n", author = "作者",
            platform = "X", sourceUrl = "https://example.com/video", localUri = "content://media/1",
            coverUri = "content://media/2", durationMs = 900_000, width = 1920, height = 1080,
            sizeBytes = 9_000_000_000, downloadedAt = 2_000_000_000_000, playCount = 8,
            lastPlayedAt = 2_000_000_010_000, lastPositionMs = 600_000, favorite = true, watchLater = true, exists = false)
        assertEquals(item, MediaCodec.decode(MediaCodec.encode(item)))
        val unknown = MediaItem("unknown", title = "未播放")
        assertEquals(unknown, MediaCodec.decode(MediaCodec.encode(unknown)))
    }
}
