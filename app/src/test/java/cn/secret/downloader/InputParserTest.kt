package cn.secret.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InputParserTest {
    @Test fun extractsDouyinShareTextAndPunctuation() {
        val result = InputParser.parse("7.33 复制打开抖音，看看作品（https://v.douyin.com/_gLnLoY2XlE/）。")
        assertEquals("https://v.douyin.com/_gLnLoY2XlE/", result.url)
        assertEquals(Platform.DOUYIN, result.platform)
    }

    @Test fun recognizesAllSupportedPlatformsByHost() {
        mapOf(
            "https://www.iesdouyin.com/share/slides/123/" to Platform.DOUYIN,
            "https://b23.tv/AbCd" to Platform.BILIBILI,
            "https://www.bilibili.com/video/BV123?p=2" to Platform.BILIBILI,
            "https://youtu.be/abc" to Platform.YOUTUBE,
            "https://m.youtube.com/watch?v=abc&t=20" to Platform.YOUTUBE,
            "https://X.COM/name/status/123/photo/2" to Platform.TWITTER,
            "https://mobile.twitter.com/name/status/123" to Platform.TWITTER,
            "https://pbs.twimg.com/media/abc.jpg?name=orig" to Platform.TWITTER,
            "https://video.twimg.com/a.mp4" to Platform.TWITTER,
            "https://vm.tiktok.com/abc/" to Platform.TIKTOK,
            "https://www.pornhub.org/view_video.php?viewkey=abc" to Platform.PORNHUB,
        ).forEach { (url, platform) -> assertEquals(platform, InputParser.parse(url).platform) }
    }

    @Test fun stripsSurroundingSharePunctuationButPreservesQuery() {
        assertEquals("https://www.youtube.com/watch?v=test&list=abc", InputParser.extractUrl("分享：https://www.youtube.com/watch?v=test&list=abc！"))
        assertEquals("https://x.com/user/status/123", InputParser.extractUrl("[https://x.com/user/status/123](https://example.com)"))
    }

    @Test fun selectsFirstSupportedLinkInShareText() {
        val parsed = InputParser.parse("来源 https://example.org/ 分享 https://v.douyin.com/abc/ 更多 https://x.com/user/status/123")
        assertEquals("https://v.douyin.com/abc/", parsed.url)
        assertEquals(Platform.DOUYIN, parsed.platform)
    }

    @Test fun rejectsLookalikeHostsAndCredentials() {
        listOf("https://x.com.evil.test/a", "https://notdouyin.com/a", "https://evil.test/?url=https://x.com/abc",
            "https://twitter.com@evil.test/a", "https://guest@x.com/a").forEach {
            assertThrows(IllegalArgumentException::class.java) { InputParser.parse(it) }
        }
    }

    @Test fun rejectsMissingOrNonWebUrls() {
        listOf("", "分享一段文字", "file:///sdcard/a.mp4", "javascript:alert(1)", "ftp://x.com/a").forEach {
            assertThrows(IllegalArgumentException::class.java) { InputParser.parse(it) }
        }
    }
}
