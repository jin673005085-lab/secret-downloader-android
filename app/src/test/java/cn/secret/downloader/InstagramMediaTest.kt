package cn.secret.downloader

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InstagramMediaTest {
    private val url = "https://www.instagram.com/p/Abc_123/"
    private val photo = JSONObject("""{"id":"11","formats":[],"thumbnails":[
        {"width":50,"height":50,"url":"https://a.cdninstagram.com/small.jpg"},
        {"width":100,"height":100,"url":"https://a.cdninstagram.com/full.jpg"}]}""")
    private val video = JSONObject("""{"id":"22","webpage_url":"https://www.instagram.com/p/Abc_123/",
        "thumbnail":"https://a.cdninstagram.com/poster.jpg","formats":[{"url":"https://a.fbcdn.net/clip.mp4"}]}""")

    @Test fun retriesRateLimitOnceAndNeverEchoesSecrets() {
        var attempts = 0
        var waits = 0
        val result = InstagramMedia.retryRateLimit({}, { waits++ }) {
            attempts++
            if (attempts == 1) throw java.io.IOException("HTTP Error 429: secret")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, attempts)
        assertEquals(1, waits)
        attempts = 0
        val error = assertThrows(java.io.IOException::class.java) {
            InstagramMedia.retryRateLimit({}, {}) { attempts++; throw java.io.IOException("HTTP Error 429: secret") }
        }
        assertEquals(2, attempts)
        assertFalse(error.message!!.contains("secret"))
        attempts = 0
        assertThrows(java.io.IOException::class.java) {
            InstagramMedia.retryRateLimit({}, {}) { attempts++; throw java.io.IOException("login secret") }
        }
        assertEquals(1, attempts)
        assertTrue(InstagramMedia.failure("").contains("空内容"))
        assertTrue(InstagramMedia.failure("No address associated with hostname").contains("域名"))
    }

    @Test fun acceptsOnlySecureInstagramCookieRows() {
        val valid = "#HttpOnly_.instagram.com\tTRUE\t/\tTRUE\t0\tsessionid\tfake"
        val header = "# Netscape HTTP Cookie File\n"
        val foreign = valid.replace(".instagram.com", ".instagram.com.example.org")
        val insecure = valid.replace("/\tTRUE", "/\tFALSE")
        val broad = valid.replace(".instagram.com", ".com")
        assertEquals(header + valid + "\n", InstagramCookies.sanitize(header + listOf(valid, foreign, insecure, broad).joinToString("\n")))
        assertThrows(IllegalArgumentException::class.java) { InstagramCookies.sanitize(header + foreign) }
    }

    @Test fun stripsCredentialsFromNestedVideoMetadata() {
        val item = JSONObject(video.toString()).put("http_headers", JSONObject().put("Cookie", "secret"))
            .put("cookies", "secret")
        item.getJSONArray("formats").getJSONObject(0).put("http_headers", JSONObject().put("Cookie", "secret"))
            .put("fragments", JSONArray().put(JSONObject().put("Authorization", "secret")))
        val sanitized = InstagramMedia.sources(item, url).single().videoInfo.toString()
        assertFalse(sanitized.contains("secret"))
        assertFalse(sanitized.contains("http_headers"))
    }

    @Test fun validatesPostAndShortShareLinks() {
        listOf(url, "https://instagram.com/reel/Ab33/?igsh=a", "https://www.instagram.com/share/reel/BAb3/").forEach {
            assertEquals(Platform.INSTAGRAM, InputParser.parse("分享：$it").platform)
        }
        listOf("https://instagram.com/profile/", "https://instagram.com/stories/person/123/",
            "https://instagram.com.evil.test/p/Abc/", "https://www.instagram.com:9876/p/Abc/").forEach {
            assertThrows(IllegalArgumentException::class.java) { InputParser.parse(it) }
        }
    }

    @Test fun carouselKeepsOrderAndOnlySelectedItems() {
        val rows = JSONObject().put("_type", "playlist").put("entries", JSONArray(listOf(photo, video)))
        val items = InstagramMedia.sources(rows, url)
        assertEquals(listOf("图片", "视频"), items.map { it.kind })
        assertEquals("https://a.cdninstagram.com/full.jpg", items[0].urls.first())
        assertEquals("https://a.cdninstagram.com/small.jpg", items[0].previewCandidates.first())
        assertEquals(listOf("https://a.cdninstagram.com/poster.jpg"), items[1].previewCandidates)
        assertFalse(items[1].videoInfo!!.has("webpage_url"))
        assertEquals(listOf(items[1]), selectMedia(items, listOf(items[1].selectionKey)))
        assertEquals(items, selectMedia(items, null))
        assertThrows(java.io.IOException::class.java) { selectMedia(items, listOf("stale")) }
        val reordered = InstagramMedia.sources(rows.put("entries", JSONArray(listOf(video, photo))), url)
        assertThrows(java.io.IOException::class.java) { selectMedia(reordered, listOf(items[1].selectionKey)) }
        assertEquals("视频", InstagramMedia.sources(video, "https://www.instagram.com/reel/Abc_123/").single().kind)
        assertEquals("图片", InstagramMedia.sources(photo, url).single().kind)
    }

    @Test fun refusesEmptyAndMisleadingMetadata() {
        assertThrows(IllegalStateException::class.java) { InstagramMedia.sources(JSONObject("""{"_type":"playlist","entries":[]}"""), url) }
        val missingVideo = JSONObject("""{"id":"22","duration":8,"thumbnail":"https://a.cdninstagram.com/poster.jpg"}""")
        assertThrows(IllegalStateException::class.java) { InstagramMedia.sources(missingVideo, url) }
        assertThrows(IllegalArgumentException::class.java) { selectMedia(InstagramMedia.sources(photo, url), emptyList()) }
    }
}
