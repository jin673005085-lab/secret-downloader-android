package cn.secret.downloader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskFixtureCleanupTest {
    @Test fun removesOnlyKnownOutputFreeInstrumentationRecords() {
        val fixture = Task("fixture", TaskType.GIF, "测试 · 列表 17", sourceUrl = "https://example.invalid/task-smoke")
        assertTrue(isLegacyTestTask(fixture))
        assertFalse(isLegacyTestTask(fixture.copy(sourceUrl = "https://www.douyin.com/video/123")))
        assertFalse(isLegacyTestTask(fixture.copy(sourceUrl = null)))
        assertFalse(isLegacyTestTask(fixture.copy(title = "我的作品")))
        assertFalse(isLegacyTestTask(fixture.copy(outputUri = "content://media/external/images/media/1")))
    }
}
