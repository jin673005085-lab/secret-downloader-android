package cn.secret.downloader

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PornhubMediaTest {
    private val page = "https://cn.pornhub.com/view_video.php?viewkey=abc123"

    @Test fun suppliesOnlyScopedCookiesAndSignedMediaAddressesWithoutExtractingThePageAgain() {
        val direct = "https://media.example/720P_1500K_movie.mp4?token=keep%2Fexact"
        val hls = "https://other.example/1080/variant.m3u8?expires=123"
        val asked = mutableListOf<String>()
        val result = PornhubMedia.info(page, JSONArray().put(JSONObject().put("videoUrl", direct).put("quality", "720"))
            .put(JSONObject().put("videoUrl", hls).put("quality", 1080)).put(JSONObject().put("videoUrl", direct)), "Browser UA") { url ->
            asked += url
            if (url == direct) "session=for-media-only" else null
        }
        assertEquals(listOf(direct, hls), asked)
        assertEquals("abc123", result.getString("id"))
        assertEquals("Pornhub_abc123", result.getString("title"))
        assertFalse(result.has("webpage_url"))
        assertFalse(result.getJSONObject("http_headers").has("Cookie"))
        val formats = result.getJSONArray("formats")
        assertEquals(2, formats.length())
        val mp4 = formats.getJSONObject(0)
        assertEquals(direct, mp4.getString("url"))
        assertEquals("https", mp4.getString("protocol"))
        assertEquals(720, mp4.getInt("height"))
        assertEquals("session=for-media-only", mp4.getJSONObject("http_headers").getString("Cookie"))
        val manifest = formats.getJSONObject(1)
        assertEquals("m3u8", manifest.getString("protocol"))
        assertEquals(1080, manifest.getInt("height"))
        assertFalse(manifest.getJSONObject("http_headers").has("Cookie"))
        assertEquals("https://cn.pornhub.com", manifest.getJSONObject("http_headers").getString("Origin"))
        assertEquals("https://cn.pornhub.com/", manifest.getJSONObject("http_headers").getString("Referer"))
    }

    @Test fun rejectsApisImagesUnsafeAddressesAndEmptyMediaBeforeAskingForCookies() {
        for (url in listOf(
            "https://www.pornhub.com/video/get_media?s=test", "https://cdn.example/cover.jpg", "blob:https://cdn.example/video.mp4",
            "file:///video.mp4", "https://user@cdn.example/video.mp4", "https://cdn.example:8443/video.mp4",
            "http://localhost/video.mp4", "https://a.localhost/video.mp4", "https://router.local/video.mp4",
            "https://127.0.0.1/video.mp4", "https://10.0.0.1/video.mp4", "https://192.168.1.1/video.mp4",
            "https://172.16.0.1/video.mp4", "https://169.254.1.1/video.mp4", "https://100.64.0.1/video.mp4",
            "https://127.1/video.mp4", "https://2130706433/video.mp4", "https://0177.0.0.1/video.mp4", "https://0x7f000001/video.mp4",
            "https://[::1]/video.mp4", "https://[::]/video.mp4", "https://[fe80::1]/video.mp4", "https://[fc00::1]/video.mp4",
            "https://[::ffff:127.0.0.1]/video.mp4",
        )) {
            assertThrows(url, IOException::class.java) {
                PornhubMedia.info(page, JSONArray().put(JSONObject().put("videoUrl", url).put("format", "mp4")), "UA") { fail("Invalid URL reached cookies: $url"); null }
            }
        }
        assertThrows(IOException::class.java) { PornhubMedia.info(page, JSONArray(), "UA") { null } }
    }

    @Test fun validatesThePageAndSupportsEmbedIdsAndPublicIpMedia() {
        val definitions = JSONArray().put(JSONObject().put("videoUrl", "https://8.8.8.8/public.mp4").put("quality", "1080p"))
        assertEquals("abc123", PornhubMedia.info("https://www.pornhub.org/embed/abc123", definitions, "UA") { null }.getString("id"))
        for (invalid in listOf("https://pornhub.com.evil.example/view_video.php?viewkey=abc", "https://user@pornhub.com/view_video.php?viewkey=abc",
            "https://pornhub.com/view_video.php?viewkey=../../bad", "https://pornhub.com/model/example", "https://pornhub.com:8443/embed/abc")) {
            assertThrows(IllegalArgumentException::class.java) { PornhubMedia.info(invalid, definitions, "UA") { null } }
        }
        assertThrows(IllegalArgumentException::class.java) { PornhubMedia.info(page, definitions, "UA\r\nInjected: value") { null } }
    }
}
