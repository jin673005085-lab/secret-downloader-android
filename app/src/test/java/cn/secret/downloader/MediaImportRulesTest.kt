package cn.secret.downloader

import org.junit.Assert.*
import org.junit.Test

class MediaImportRulesTest {
    @Test fun importsOnlyDesignatedFoldersAndMediaTypes() {
        assertTrue(MediaImportRules.matches(MediaType.VIDEO, "Download/不能说的秘密/", "v.mp4", "video/mp4"))
        assertTrue(MediaImportRules.matches(MediaType.VIDEO, "Download/不能说的秘密/sub/", "v.webm", "video/webm"))
        assertFalse(MediaImportRules.matches(MediaType.VIDEO, "Download/不能说的秘密备份/", "v.mp4", "video/mp4"))
        assertFalse(MediaImportRules.matches(MediaType.VIDEO, "Download/不能说的秘密/", "x.gif", "image/gif"))
        assertTrue(MediaImportRules.matches(MediaType.GIF, "Pictures/不能说的秘密/GIF/", "x.gif", "image/gif"))
        assertFalse(MediaImportRules.matches(MediaType.GIF, "Pictures/不能说的秘密/GIF_backup/", "x.gif", "image/gif"))
        assertTrue(MediaImportRules.matches(MediaType.LIVE_PHOTO, "Pictures/不能说的秘密/", "x_MP.jpg", "image/jpeg"))
        assertFalse(MediaImportRules.matches(MediaType.LIVE_PHOTO, "Pictures/不能说的秘密/", "ordinary.jpg", "image/jpeg"))
        assertFalse(MediaImportRules.matches(MediaType.LIVE_PHOTO, "Pictures/不能说的秘密/GIF/", "x_MP.jpg", "image/jpeg"))
        assertFalse(MediaImportRules.matches(MediaType.LIVE_PHOTO, "DCIM/Camera/", "x_MP.jpg", "image/jpeg"))
    }

    @Test fun refreshPreservesLatestUserStateAndProvenance() {
        val current = MediaItem("same", taskId = "task", title = "我改的名字", sourceUrl = "https://example.com/source",
            coverUri = "content://chosen-cover", downloadedAt = 100, favorite = true, watchLater = true,
            playCount = 7, lastPlayedAt = 90, lastPositionMs = 4200, params = "{\"quality\":1080}")
        val scanned = MediaItem("scan-id", title = "磁盘文件名", localUri = "content://file", durationMs = 9000,
            width = 1080, height = 1920, sizeBytes = 5000, coverUri = "file:///cache/thumbnail", motionVideoUri = "content://motion")
        val merged = MediaImportRules.merge(current, scanned)
        assertEquals(current.copy(durationMs = 9000, width = 1080, height = 1920, sizeBytes = 5000,
            motionVideoUri = "content://motion"), merged)
    }
}
