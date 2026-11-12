package cn.secret.downloader

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.io.IOException
import kotlin.concurrent.thread

class DownloadEngineTest {
    @get:Rule val temporary = TemporaryFolder()
    private val post = "https://x.com/example/status/1234567890123456789"
    private val mixedTweet = """{
      "mediaDetails": [
        {"type":"photo","media_url_https":"https://pbs.twimg.com/media/Example.jpg"},
        {"type":"video","video_info":{"variants":[
          {"content_type":"video/mp4","bitrate":1000,"url":"https://video.twimg.com/vid/640x360/low.mp4"},
          {"content_type":"video/mp4","bitrate":3000,"url":"https://video.twimg.com/vid/1920x1080/high.mp4"},
          {"content_type":"application/x-mpegURL","url":"https://video.twimg.com/a.m3u8"}
        ]}},
        {"type":"animated_gif","video_info":{"variants":[{"url":"https://video.twimg.com/gif.mp4"}]}}
      ],
      "quoted_tweet":{"mediaDetails":[{"type":"photo","media_url_https":"https://pbs.twimg.com/media/Quoted.jpg"}]}
    }"""

    @Test fun defaultHasNoHeightCapAndSupportsSilentVideo() {
        val best = DownloadEngine.formatSelector("best")
        assertFalse(best.contains("height"))
        assertTrue(best.endsWith("/bestvideo"))
        val capped = DownloadEngine.formatSelector("720")
        assertTrue(capped.contains("height<=?720"))
        assertTrue(capped.contains("/worstvideo+bestaudio/worst/worstvideo"))
    }

    @Test fun explainsMissingImpersonationOnlyWhenForbiddenIsAlsoReported() {
        val warning = "WARNING: [PornHub] The extractor is attempting impersonation, but no impersonate target is available."
        val forbidden = "ERROR: [PornHub] example: Unable to download webpage: HTTP Error 403: Forbidden"
        val explanation = DownloadEngine.ytDlpFailureMessage("$warning\n$forbidden")
        assertTrue(explanation.contains("浏览器请求模拟"))
        assertTrue(explanation.contains("HTTP 403"))
        assertFalse(explanation.startsWith("下载失败："))
        assertEquals(forbidden, DownloadEngine.ytDlpFailureMessage(forbidden))
        val unavailable = "$warning\nERROR: This video has been removed"
        assertEquals(unavailable, DownloadEngine.ytDlpFailureMessage(unavailable))
        assertEquals("请检查网络和链接是否可公开访问", DownloadEngine.ytDlpFailureMessage(null))
        assertEquals(1600, DownloadEngine.ytDlpFailureMessage("x".repeat(2000)).length)
    }

    @Test fun reportsDnsFailureSeparatelyFromTheEarlierImpersonationWarning() {
        val warning = "WARNING: [PornHub] no impersonate target is available."
        for (failure in listOf("No address associated with hostname", "Name or service not known",
                "Temporary failure in name resolution", "getaddrinfo failed", "Unable to resolve host")) {
            val explanation = DownloadEngine.ytDlpFailureMessage("$warning\nERROR: Unable to download webpage: $failure")
            assertTrue(explanation.contains("域名解析失败"))
            assertTrue(explanation.contains("分应用"))
            assertFalse(explanation.contains("浏览器请求模拟"))
            assertFalse(explanation.contains("WARNING:"))
        }
        val timeout = "ERROR: connection timed out"
        assertEquals(timeout, DownloadEngine.ytDlpFailureMessage(timeout))
    }

    @Test fun forwardsSystemHttpProxyAndHonorsDirectBypassWithoutLookingUpTheTarget() {
        val target = "https://unresolvable.invalid/watch"
        fun selector(vararg routes: Proxy) = object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> {
                assertEquals(target, uri.toString())
                return routes.toList()
            }
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
        }
        val http = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.invalid", 7890))
        assertEquals("http://proxy.invalid:7890", DownloadEngine.systemProxyFor(target, selector(http)))
        assertNull(DownloadEngine.systemProxyFor(target, selector(Proxy.NO_PROXY, http)))
        assertNull(DownloadEngine.systemProxyFor(target, selector()))
        assertNull(DownloadEngine.systemProxyFor(target, null))
        val ipv6 = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("::1", 7890))
        assertEquals("http://[::1]:7890", DownloadEngine.systemProxyFor(target, selector(ipv6)))
        val socks = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("localhost", 1080))
        assertThrows(IllegalArgumentException::class.java) { DownloadEngine.systemProxyFor(target, selector(socks)) }
        val invalid = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("localhost", 0))
        assertThrows(IllegalArgumentException::class.java) { DownloadEngine.systemProxyFor(target, selector(invalid)) }
    }

    @Test fun keepsAllOriginalTweetAttachmentsWithoutQuotedFiles() {
        val sources = DownloadEngine.parseTwitterMedia(JSONObject(mixedTweet), post, "best")
        assertEquals(3, sources.size)
        assertEquals("https://pbs.twimg.com/media/Example.jpg?name=orig", sources[0].urls.single())
        assertTrue(sources[1].urls.single().endsWith("high.mp4"))
        assertEquals("twitter_1234567890123456789_3.mp4", sources[2].fileName)
        assertFalse(sources[0].isGif)
        assertFalse(sources[1].isGif)
        assertTrue(sources[2].isGif)
        assertFalse(sources.any { source -> source.urls.any { it.contains("Quoted") } })
        val capped = DownloadEngine.parseTwitterMedia(JSONObject(mixedTweet), post, "720")
        assertTrue(capped[1].urls.single().endsWith("low.mp4"))
    }

    @Test fun honorsSelectedAttachmentAndDoesNotSilentlySelectEverythingForInvalidIndex() {
        val sources = DownloadEngine.parseTwitterMedia(JSONObject(mixedTweet), "$post/photo/1", "best")
        assertEquals(1, sources.size)
        assertTrue(sources.single().fileName.endsWith("_1.image"))
        for (suffix in listOf("photo/2", "photo/0", "video/9", "video/999999999999999999999999999999")) {
            assertThrows(Exception::class.java) { DownloadEngine.parseTwitterMedia(JSONObject(mixedTweet), "$post/$suffix", "best") }
        }
        assertThrows(Exception::class.java) {
            DownloadEngine.parseTwitterMedia(JSONObject("""{"mediaDetails":[],"quoted_tweet":{"mediaDetails":[{}]}}"""), post, "best")
        }
    }

    @Test fun originalImageChangesOnlyItsSizeAndRejectsUnrelatedHosts() {
        assertEquals("https://pbs.twimg.com/media/A?format=png&name=orig", DownloadEngine.originalImage("https://pbs.twimg.com/media/A?format=png&name=small"))
        assertEquals("https://pbs.twimg.com/media/A.jpg?name=orig", DownloadEngine.originalImage("https://pbs.twimg.com/media/A.jpg:small"))
        for (url in listOf("https://pbs.twimg.com.evil.test/media/A.jpg", "https://evil.test/media/A.jpg", "https://user@pbs.twimg.com/media/A.jpg", "https://pbs.twimg.com/profile_images/A.jpg")) {
            assertThrows(Exception::class.java) { DownloadEngine.originalImage(url) }
        }
    }

    @Test fun tokensMatchActualJavascriptForMoreThanOneHundredSnowflakeIds() {
        val rows = javaClass.getResourceAsStream("/twitter-token-fixture.tsv")!!.bufferedReader().use { it.readLines() }
        assertTrue(rows.size >= 100)
        for (row in rows) {
            val (id, token) = row.split('\t')
            assertEquals(id, token, DownloadEngine.syndicationToken(id))
        }
        assertThrows(IllegalArgumentException::class.java) { DownloadEngine.syndicationToken("0") }
    }

    @Test fun recognizesActualImageBytesAndRejectsHtml() {
        val samples = mapOf(
            "jpg" to byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0),
            "png" to byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
            "webp" to "RIFF0000WEBP".toByteArray(),
            "gif" to "GIF89a".toByteArray(),
            "avif" to "0000ftypavif".toByteArray(),
        )
        samples.forEach { (extension, bytes) -> assertEquals(extension, DownloadEngine.imageExtension(bytes)) }
        assertNull(DownloadEngine.imageExtension("<!doctype html>validation".toByteArray()))
    }

    @Test fun actualHttpDownloadTriesBackupAndSniffsImageExtension() {
        val requests = AtomicInteger()
        val requiredHeader = AtomicReference<String>()
        val bytes = "RIFF0000WEBPsynthetic-content".toByteArray()
        HttpStub { path, headers ->
            requests.incrementAndGet()
            if (path == "/missing") 404 to ByteArray(0) else {
                requiredHeader.set(headers["x-test-header"])
                200 to bytes
            }
        }.use { server ->
            val base = server.baseUrl
            val folder = temporary.newFolder()
            val file = DownloadEngine.downloadSource(MediaSource(listOf("$base/missing", "$base/image"), "photo.image", mapOf("X-Test-Header" to "required")), folder)
            assertEquals("photo.webp", file.name)
            assertArrayEquals(bytes, file.readBytes())
            assertEquals("required", requiredHeader.get())
            assertEquals(2, requests.get())
            assertEquals(listOf("photo.webp"), folder.listFiles()!!.map { it.name })
        }
    }

    @Test fun invalidVideoAndHtmlAreNotPublishedAsMedia() {
        val bytes = "<html>please log in</html>".toByteArray()
        HttpStub { _, _ -> 200 to bytes }.use { server ->
            val folder = temporary.newFolder()
            for (name in listOf("photo.image", "video_live.mp4")) {
                assertThrows(Exception::class.java) {
                    DownloadEngine.downloadSource(MediaSource(listOf("${server.baseUrl}/"), name), folder)
                }
            }
            assertTrue(folder.listFiles()!!.isEmpty())
        }
    }

    private class HttpStub(handler: (String, Map<String, String>) -> Pair<Int, ByteArray>) : AutoCloseable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val baseUrl = "http://127.0.0.1:${server.localPort}"
        private val worker = thread(isDaemon = true, name = "engine-test-http") {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: SocketException) { break }
                client.use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val request = reader.readLine() ?: return@use
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    val (code, bytes) = handler(request.split(' ')[1], headers)
                    socket.getOutputStream().use { output ->
                        output.write("HTTP/1.1 $code OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(bytes)
                    }
                }
            }
        }

        override fun close() {
            server.close()
            worker.join(1000)
        }
    }
}
