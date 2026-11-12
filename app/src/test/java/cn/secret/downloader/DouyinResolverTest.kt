package cn.secret.downloader

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.URL

class DouyinResolverTest {
    private val id = "7672640152317292096"

    @Test fun officialSharePlaybackRequestsSourceResolutionAndKeepsOriginalBackup() {
        val original = "https://aweme.snssdk.com/aweme/v1/playwm/?video_id=abc%2Fdef&ratio=720p&line=0"
        val detail = JSONObject("""{"aweme_id":"$id","video":{"width":1080,"height":1920,"play_addr":{"url_list":["$original"]}}}""")
        val best = DouyinResolver.parseDetail(detail, id, "best").single().urls
        assertEquals("https://aweme.snssdk.com/aweme/v1/play/?video_id=abc%2Fdef&ratio=1080p&line=0", best.first())
        assertEquals(original, best.last())
        assertEquals(2, best.size)
        assertTrue(DouyinResolver.parseDetail(detail, id, "480").single().urls.first().contains("ratio=480p"))
        detail.getJSONObject("video").put("width", 7680).put("height", 4320)
        assertTrue(DouyinResolver.parseDetail(detail, id, "best").single().urls.first().contains("ratio=4320p"))
        detail.getJSONObject("video").remove("width")
        assertTrue(DouyinResolver.parseDetail(detail, id, "best").single().urls.first().contains("ratio=720p"))
        val direct = "https://cdn.example/original.mp4?sign=keep%2Fexact"
        detail.getJSONObject("video").getJSONObject("play_addr").getJSONArray("url_list").put(direct)
        val ordered = DouyinResolver.parseDetail(detail, id, "best").single().urls
        assertEquals(direct, ordered[1])
        assertEquals(original, ordered.last())
    }

    @Test fun playbackUpgradeNeverRewritesSignedCdnOrUnrelatedHosts() {
        for (url in listOf("https://cdn.example/aweme/v1/playwm/?ratio=720p&sign=keep%2Fexact", "https://aweme.snssdk.com.evil.example/aweme/v1/playwm/?ratio=720p", "https://aweme.snssdk.com/aweme/v1/play/?ratio=720p", "https://aweme.snssdk.com/other/playwm/?ratio=720p")) {
            val detail = JSONObject("""{"aweme_id":"$id","video":{"width":1080,"height":1080,"play_addr":{"url_list":["$url"]}}}""")
            assertEquals(listOf(url), DouyinResolver.parseDetail(detail, id, "best").single().urls)
        }
    }

    @Test fun mixedAlbumKeepsIndicesAndPairsLiveVideoWithOriginalCover() {
        val detail = JSONObject("""{"aweme_id":"$id","images":[
            {"url_list":["https://cdn.example/original","https://cdn.example/shared"],
             "download_url_list":["https://cdn.example/watermarked","https://cdn.example/shared","file:///bad"]},
            {"clip_type":3,"url_list":["https://cdn.example/cover","file:///bad"],
             "download_url_list":["https://cdn.example/cover","https://cdn.example/cover-backup","javascript:bad"],
             "video":{"play_addr_h264":{"url_list":["https://cdn.example/live.mp4"]}}}
        ],"video":{"play_addr":{"url_list":["https://cdn.example/background.mp3"]}}}""")
        val result = DouyinResolver.parseDetail(detail, id, "best")
        assertEquals(listOf("douyin_${id}_01.image", "douyin_${id}_02_live.mp4"), result.map { it.fileName })
        assertEquals(listOf("https://cdn.example/original", "https://cdn.example/shared", "https://cdn.example/watermarked"), result[0].urls)
        assertEquals(listOf("https://cdn.example/live.mp4"), result[1].urls)
        assertNull(result[0].livePhotoCoverUrls)
        assertEquals(listOf("https://cdn.example/cover", "https://cdn.example/cover-backup"), result[1].livePhotoCoverUrls)
        assertTrue(result.all { it.headers.containsKey("Referer") })
    }

    @Test fun liveWithoutCoverRemainsLiveAndKeepsLongVideo() {
        val detail = JSONObject("""{"aweme_id":"$id","images":[{"video":{"duration":3600000,
            "play_addr":{"url_list":["https://cdn.example/hour.mp4"]}}}]}""")
        val result = DouyinResolver.parseDetail(detail, id, "best").single()
        assertEquals(emptyList<String>(), result.livePhotoCoverUrls)
        assertEquals(listOf("https://cdn.example/hour.mp4"), result.urls)
    }

    @Test fun liveMarkerWithoutVideoDoesNotSilentlyBecomeAStaticImage() {
        for (marker in listOf("\"clip_type\":3", "\"live_photo_type\":1")) {
            val detail = JSONObject("""{"aweme_id":"$id","images":[{$marker,"url_list":["https://cdn.example/cover"]}]}""")
            val error = assertThrows(IOException::class.java) { DouyinResolver.parseDetail(detail, id, "best") }
            assertTrue(error.message!!.contains("实况照片"))
        }
    }

    @Test fun bestIsUncappedAndNumericQualityUsesActualHeight() {
        val detail = JSONObject("""{"aweme_id":"$id","video":{"bit_rate":[
            {"bit_rate":9000,"play_addr":{"height":1080,"url_list":["https://cdn.example/1080"]}},
            {"bit_rate":15000,"play_addr":{"height":4320,"url_list":["https://cdn.example/8k"]}},
            {"bit_rate":18000,"play_addr":{"height":2160,"url_list":["https://cdn.example/4k"]}},
            {"bit_rate":5000,"play_addr":{"height":720,"url_list":["https://cdn.example/720"]}}
        ]}}""")
        fun selected(quality: String) = DouyinResolver.parseDetail(detail, id, quality).single().urls.single()
        assertEquals("https://cdn.example/8k", selected("best"))
        assertEquals("https://cdn.example/1080", selected("1440"))
        assertEquals("https://cdn.example/720", selected("480"))
    }

    @Test fun backupRawAddressesAreKeptAndMalformedLiveDoesNotBecomeStatic() {
        val video = JSONObject("""{"aweme_id":"$id","video":{
            "play_addr":{"url_list":["https://cdn.example/primary"]},
            "play_addr_h264":{"url_list":["https://cdn.example/backup","javascript:bad"]}}}""")
        assertEquals(listOf("https://cdn.example/primary", "https://cdn.example/backup"),
            DouyinResolver.parseDetail(video, id, "best").single().urls)
        val broken = JSONObject("""{"aweme_id":"$id","images":[{"url_list":["https://cdn.example/cover"],"video":{"duration":4400}}]}""")
        val error = runCatching { DouyinResolver.parseDetail(broken, id, "best") }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error!!.message!!.contains("实况照片"))
    }

    @Test fun sharePageReadsOnlyRequestedWorkAndRejectsIdMismatch() {
        val html = """<script>window._ROUTER_DATA = {"loaderData":{"note_(id)/page":{"videoInfoRes":{"item_list":[
          {"aweme_id":"999","images":[{}]},
          {"aweme_id":"$id","video":{"play_addr":{"url_list":["https://cdn.example/movie"]}}}
        ]}}}};window.another=1;</script>"""
        val detail = DouyinResolver.parseSharePage(html, "note", id)!!
        assertEquals(id, detail.optString("aweme_id"))
        assertEquals("douyin_$id.mp4", DouyinResolver.parseDetail(detail, id, "best").single().fileName)
        assertNull(DouyinResolver.parseSharePage(html, "note", "123"))
        assertTrue(runCatching { DouyinResolver.parseDetail(detail, "123", "best") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun onlyRealDouyinHostsAndItemIdsAreAccepted() {
        assertTrue(DouyinResolver.allowedUrl("https://v.douyin.com/example/"))
        assertTrue(DouyinResolver.allowedUrl("https://www.iesdouyin.com/share/slides/$id/"))
        listOf("https://douyin.com.evil.example/note/$id", "file:///note/$id", "https://douyin.com@evil.example/note/$id")
            .forEach { assertFalse(DouyinResolver.allowedUrl(it)) }
        assertEquals(id, DouyinResolver.itemId("https://www.iesdouyin.com/share/slides/$id/?a=1"))
        assertEquals(id, DouyinResolver.itemId("https://www.douyin.com/?modal_id=$id"))
        assertNull(DouyinResolver.itemId("https://www.douyin.com/note/abc"))
    }

    private val videoId = "7705772725423459610"
    private val callback = "https://www.iesdouyin.com/ttwid/union/register/callback/?aid=1768&ticket=test"
    // This public video returned a valid router shell without videoInfoRes before anonymous registration.
    private val videoShell get() = """<script>window._ROUTER_DATA={"loaderData":{"video_layout":null,"video_(id)/page":{"itemId":"$videoId","renderInSSR":1,"serverToken":""}}};</script>"""
    private val videoPage get() = """<script>window._ROUTER_DATA={"loaderData":{"video_(id)/page":{"videoInfoRes":{"item_list":[{"aweme_id":"$videoId","video":{"height":1080,"width":1080,"duration":15100,"play_addr":{"url_list":["https://cdn.example/video.mp4"]}}}]}}}};</script>"""

    @Test fun anonymousFallbackPreservesCookieDomainsAndDoesNotShareSessions() {
        val globalCookies = CookieHandler.getDefault()
        repeat(2) {
            var registered = false
            val calls = mutableListOf<StubConnection>()
            val result = DouyinResolver.resolve("https://www.iesdouyin.com/share/video/$videoId/", "best") { url ->
                val reply = when (url.path) {
                    "/web/api/v2/aweme/slidesinfo/" -> Reply("""{"aweme_details":[]}""")
                    "/share/video/$videoId/" -> Reply(if (registered) videoPage else videoShell,
                        headers = mapOf("Set-Cookie" to listOf("ttwid=initial; Domain=.iesdouyin.com; Path=/")))
                    "/share/note/$videoId/" -> Reply(videoShell)
                    "/ttwid/union/register/" -> Reply("""{"status_code":0,"redirect_url":"$callback"}""",
                        headers = mapOf("Set-Cookie" to listOf("ttwid=bytedance_only; Domain=.bytedance.com; Path=/")))
                    "/ttwid/union/register/callback/" -> {
                        registered = true
                        Reply("""{"status_code":0}""", headers = mapOf("Set-Cookie" to listOf("ttwid=anonymous; Domain=.iesdouyin.com; Path=/")))
                    }
                    else -> error("Unexpected URL")
                }
                StubConnection(url, reply).also(calls::add)
            }
            assertEquals("douyin_$videoId.mp4", result.single().fileName)
            assertEquals(listOf("/web/api/v2/aweme/slidesinfo/", "/share/video/$videoId/", "/share/note/$videoId/",
                "/ttwid/union/register/", "/ttwid/union/register/callback/", "/web/api/v2/aweme/slidesinfo/", "/share/video/$videoId/"),
                calls.map { it.url.path })
            assertTrue(calls.first().getRequestProperty("Cookie").isNullOrBlank())
            val registration = calls[3]
            assertEquals("POST", registration.requestMethod)
            assertFalse(registration.getRequestProperty("Cookie").orEmpty().contains("initial"))
            assertEquals("www.iesdouyin.com", JSONObject(registration.post.toString("UTF-8")).getString("service"))
            assertTrue(calls.last().getRequestProperty("Cookie").orEmpty().contains("anonymous"))
            assertFalse(calls.last().getRequestProperty("Cookie").orEmpty().contains("bytedance_only"))
            assertSame(globalCookies, CookieHandler.getDefault())
        }
    }

    @Test fun keepsShortLinkResponseAndStillPrefersCompleteSlidesInfo() {
        val calls = mutableListOf<String>()
        val result = DouyinResolver.resolve("https://v.douyin.com/test/", "best") { url ->
            calls += url.path
            val reply = when (url.path) {
                "/test/" -> Reply("", 302, mapOf("Location" to listOf("https://www.iesdouyin.com/share/video/$videoId/")))
                "/share/video/$videoId/" -> Reply(videoPage)
                "/web/api/v2/aweme/slidesinfo/" -> Reply("""{"aweme_details":[]}""")
                else -> error("The final share body should have been reused")
            }
            StubConnection(url, reply)
        }
        assertEquals(3, calls.size)
        assertEquals("douyin_$videoId.mp4", result.single().fileName)

        val album = DouyinResolver.resolve("https://www.iesdouyin.com/share/slides/$id/", "best") { url ->
            assertEquals("/web/api/v2/aweme/slidesinfo/", url.path)
            StubConnection(url, Reply("""{"aweme_details":[{"aweme_id":"$id","images":[{"video":{"play_addr":{"url_list":["https://cdn.example/live.mp4"]}}}]}]}"""))
        }
        assertTrue(album.single().fileName.endsWith("_live.mp4"))
        assertEquals(emptyList<String>(), album.single().livePhotoCoverUrls)
    }

    @Test fun rejectsUnsafeCallbacksBeforeOpeningThem() {
        assertTrue(DouyinResolver.allowedCallback(callback))
        for (url in listOf(
            "http://www.iesdouyin.com/ttwid/union/register/callback/",
            "https://www.iesdouyin.com.evil.example/ttwid/union/register/callback/",
            "https://user@www.iesdouyin.com/ttwid/union/register/callback/",
            "https://www.iesdouyin.com:8443/ttwid/union/register/callback/",
            "https://www.iesdouyin.com/other/path/",
            "https://v.douyin.com/ttwid/union/register/callback/",
        )) assertFalse(url, DouyinResolver.allowedCallback(url))
        var registrations = 0
        val error = assertThrows(IOException::class.java) {
            DouyinResolver.resolve("https://www.iesdouyin.com/share/video/$videoId/", "best") { url ->
                assertTrue(url.host in setOf("www.iesdouyin.com", "ttwid.bytedance.com"))
                val body = if (url.host == "ttwid.bytedance.com") {
                    registrations++
                    """{"status_code":0,"redirect_url":"https://evil.example/steal"}"""
                } else if (url.path.contains("slidesinfo")) """{"aweme_details":[]}""" else videoShell
                StubConnection(url, Reply(body))
            }
        }
        assertEquals(2, registrations)
        assertTrue(error.cause!!.message!!.contains("回调"))
    }

    @Test fun retriesTransientBootstrapIoOnceAndRejectsCallbackErrors() {
        var registrations = 0
        var registered = false
        DouyinResolver.resolve("https://www.iesdouyin.com/share/video/$videoId/", "best") { url ->
            val reply = when {
                url.host == "ttwid.bytedance.com" -> {
                    registrations++
                    if (registrations == 1) Reply("", failure = IOException("temporary network failure"))
                    else Reply("""{"status_code":0,"redirect_url":"$callback"}""")
                }
                url.path.contains("callback") -> { registered = true; Reply("""{"status_code":0}""") }
                url.path.contains("slidesinfo") -> Reply("""{"aweme_details":[]}""")
                else -> Reply(if (registered) videoPage else videoShell)
            }
            StubConnection(url, reply)
        }
        assertEquals(2, registrations)

        for (callbackBody in listOf("""{"status_code":1}""", "{}", "<html>error</html>")) {
            var callbacks = 0
            assertThrows(IOException::class.java) {
                DouyinResolver.resolve("https://www.iesdouyin.com/share/video/$videoId/", "best") { url ->
                    val reply = when {
                        url.host == "ttwid.bytedance.com" -> Reply("""{"status_code":0,"redirect_url":"$callback"}""")
                        url.path.contains("callback") -> { callbacks++; Reply(callbackBody) }
                        url.path.contains("slidesinfo") -> Reply("""{"aweme_details":[]}""")
                        else -> Reply(videoShell)
                    }
                    StubConnection(url, reply)
                }
            }
            assertEquals(2, callbacks)
        }
    }

    @Test fun malformedInitialRouterDataStillAllowsTheAnonymousFallback() {
        var registered = false
        val result = DouyinResolver.resolve("https://v.douyin.com/test/", "best") { url ->
            val reply = when {
                url.host == "v.douyin.com" -> Reply("", 302, mapOf("Location" to listOf("https://www.iesdouyin.com/share/video/$videoId/")))
                url.host == "ttwid.bytedance.com" -> Reply("""{"status_code":0,"redirect_url":"$callback"}""")
                url.path.contains("callback") -> { registered = true; Reply("""{"status_code":0}""") }
                url.path.contains("slidesinfo") -> Reply("""{"aweme_details":[]}""")
                else -> Reply(if (registered) videoPage else "<script>window._ROUTER_DATA = !invalid;</script>")
            }
            StubConnection(url, reply)
        }
        assertEquals("douyin_$videoId.mp4", result.single().fileName)
    }

    private val reportedId = "7702728846361253007"
    private fun reportedDesktop() = JSONObject(javaClass.getResourceAsStream("/douyin-$reportedId-desktop.json")!!
        .bufferedReader().use { it.readText() })

    @Test fun reportedLoopingAnimationBecomesLivePhotoFromRealCamelCaseVideoAndCover() {
        val detail = reportedDesktop()
        val chunk = "0:[\"$\",{\"unrelated\":{\"awemeId\":\"999\",\"images\":[]},\"work\":$detail}]\n"
        val half = chunk.length / 2
        val html = "<script>self.__pace_f.push([1,${JSONObject.quote(chunk.take(half))}])</script>" +
            "<script>self.__pace_f.push([1,${JSONObject.quote(chunk.drop(half))}])</script>"
        val desktop = checkNotNull(DouyinResolver.parseDesktopPage(html, reportedId))
        val original = DouyinResolver.parseDetail(JSONObject("""{"aweme_id":"$reportedId","images":[{
            "width":1440,"height":1920,"url_list":["https://cdn.example/static-reflow.webp"]}]}"""), reportedId, "best")
        assertTrue(original.single().fileName.endsWith(".image"))
        val fixed = DouyinResolver.enrichImages(original, desktop, reportedId, "best").single()
        assertEquals("douyin_${reportedId}_01_live.mp4", fixed.fileName)
        assertEquals(listOf("https://cdn.example/animated-original.mp4"), fixed.urls)
        assertFalse(fixed.isGif)
        assertEquals(listOf("https://cdn.example/original-cover.webp", "https://cdn.example/watermarked-cover.webp"), fixed.livePhotoCoverUrls)
        assertNull(DouyinResolver.parseDesktopPage(html, "123"))
        assertNull(DouyinResolver.parseDesktopPage("self.__pace_f.push([1,not-json])", reportedId))
        val direct = DouyinResolver.desktopDetail(reportedId) { url ->
            assertEquals("/note/$reportedId", url.path)
            StubConnection(url, Reply(html))
        }
        assertEquals(reportedId, direct!!.getString("awemeId"))
    }

    @Test fun desktopEnrichmentKeepsStaticOriginalsAndPackagesBothMotionPhotoMarkers() {
        val desktop = reportedDesktop()
        val image = desktop.getJSONArray("images").getJSONObject(0)
        image.put("clipType", 3)
        val live = DouyinResolver.parseDetail(desktop, reportedId, "best").single()
        assertFalse(live.isGif)
        assertEquals(listOf("https://cdn.example/original-cover.webp", "https://cdn.example/watermarked-cover.webp"), live.livePhotoCoverUrls)
        image.remove("video"); image.put("clipType", 0); image.put("livePhotoType", 0)
        val old = listOf(MediaSource(listOf("https://cdn.example/preserve-existing-original.webp"), "douyin_${reportedId}_01.image"))
        assertEquals(old, DouyinResolver.enrichImages(old, desktop, reportedId, "best"))
        image.put("clipType", 5)
        assertTrue(assertThrows(IOException::class.java) { DouyinResolver.parseDetail(desktop, reportedId, "best") }.message!!.contains("动图"))
        assertThrows(IllegalStateException::class.java) { DouyinResolver.enrichImages(emptyList(), reportedDesktop(), reportedId, "best") }
    }

    @Test fun desktopBitrateSelectionUsesTheSameHeightPolicyAsMobile() {
        val desktop = reportedDesktop()
        val gears = desktop.getJSONArray("images").getJSONObject(0).getJSONObject("video").getJSONArray("bitRateList")
        gears.put(JSONObject("""{"height":2160,"bitRate":9000000,"playAddr":[{"src":"https://cdn.example/2160.mp4"}]}"""))
        gears.put(JSONObject("""{"height":360,"bitRate":200000,"playAddr":[{"src":"https://cdn.example/360.mp4"},{"src":"file:///unsafe"}]}"""))
        assertEquals("https://cdn.example/2160.mp4", DouyinResolver.parseDetail(desktop, reportedId, "best").single().urls.single())
        assertEquals("https://cdn.example/animated-original.mp4", DouyinResolver.parseDetail(desktop, reportedId, "1080").single().urls.single())
        assertEquals("https://cdn.example/360.mp4", DouyinResolver.parseDetail(desktop, reportedId, "480").single().urls.single())
    }

    private data class Reply(val body: String, val code: Int = 200, val headers: Map<String, List<String>> = emptyMap(), val failure: IOException? = null)

    private class StubConnection(url: URL, val reply: Reply) : HttpURLConnection(url) {
        val post = ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode(): Int { reply.failure?.let { throw it }; return reply.code }
        override fun getInputStream() = ByteArrayInputStream(reply.body.toByteArray(Charsets.UTF_8))
        override fun getOutputStream() = post
        override fun getHeaderFields() = reply.headers
        override fun getHeaderField(name: String) = reply.headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
    }
}
