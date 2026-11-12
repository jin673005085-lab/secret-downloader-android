package cn.secret.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadSettingsTest {
    @Test fun defaultsToUncappedBestAndFourThreads() {
        assertEquals("best", DownloadSettings().quality)
        assertEquals(4, DownloadSettings().threads)
    }

    @Test fun acceptsLimitsAndRejectsInvalidSettings() {
        DownloadSettings.QUALITIES.forEach { DownloadSettings(it, 1); DownloadSettings(it, 16) }
        assertThrows(IllegalArgumentException::class.java) { DownloadSettings("bad", 4) }
        assertThrows(IllegalArgumentException::class.java) { DownloadSettings("best", 0) }
        assertThrows(IllegalArgumentException::class.java) { DownloadSettings("best", 17) }
    }
}
