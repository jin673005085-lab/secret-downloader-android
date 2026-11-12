package cn.secret.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCoverActivityTest {
    @Test fun frameSelectionIncludesTheWholeVideoWithoutSelectingItsEof() {
        val tenMinutes = 600_000_000L
        assertEquals(0L, VideoCoverActivity.frameTimeUs(0, 600_000, tenMinutes))
        assertEquals(299_999_500L, VideoCoverActivity.frameTimeUs(300_000, 600_000, tenMinutes))
        assertEquals(tenMinutes - 1000, VideoCoverActivity.frameTimeUs(600_000, 600_000, tenMinutes))
        // The seek range still covers long durations beyond Int milliseconds.
        val month = 31L * 24 * 60 * 60 * 1_000_000
        val end = VideoCoverActivity.frameTimeUs(Int.MAX_VALUE, Int.MAX_VALUE, month)
        assertEquals(month - 1000, end)
        assertTrue(end > Int.MAX_VALUE.toLong() * 1000)
        assertEquals(0L, VideoCoverActivity.frameTimeUs(1, 1, 500))
        assertThrows(IllegalArgumentException::class.java) { VideoCoverActivity.frameTimeUs(-1, 100, tenMinutes) }
    }

    @Test fun previewStaysSmallAndPreservesPortraitAndLandscapeProportions() {
        assertEquals(960 to 540, VideoCoverActivity.previewSize(7680, 4320))
        assertEquals(540 to 960, VideoCoverActivity.previewSize(2160, 3840))
        assertEquals(320 to 240, VideoCoverActivity.previewSize(320, 240))
        assertEquals(1 to 960, VideoCoverActivity.previewSize(1, Int.MAX_VALUE))
        assertThrows(IllegalArgumentException::class.java) { VideoCoverActivity.previewSize(0, 100) }
    }
}
