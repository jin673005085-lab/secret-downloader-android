package cn.secret.downloader

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

object DouyinResolver {
    private const val USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
    private const val REFERER = "https://www.douyin.com/"
    private val itemPath = Regex("/(?:video|note|slides)/(\\d+)(?:/|$)")
    private val routerData = Regex("window\\._ROUTER_DATA\\s*=\\s*")
    private val mediaHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to REFERER)
    private const val REGISTER_URL = "https://ttwid.bytedance.com/ttwid/union/register/"
    internal const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36"

    fun resolve(url: String, quality: String): List<MediaSource> =
        resolve(url, quality) { it.openConnection() as HttpURLConnection }

    internal fun resolve(url: String, quality: String, openConnection: (URL) -> HttpURLConnection): List<MediaSource> {
        require(allowedUrl(url)) { "请使用抖音作品的分享链接" }
        val session = ShareSession(openConnection)
        val sharePage = if (itemId(url) == null) session.follow(url) else null
        val id = itemId(url) ?: sharePage?.first?.let(::itemId)
            ?: throw IOException("没有找到作品编号，请复制单个作品的分享链接")
        val detail = slidesDetail(session, id)
            ?: sharePage?.second?.let { html ->
                runCatching { listOf("video", "note", "slides").firstNotNullOfOrNull { parseSharePage(html, it, id) } }.getOrNull()
            }
            ?: shareDetail(session, id)
        if (detail != null) return parseDetail(detail, id, quality)

        var registrationError: IOException? = null
        for (attempt in 0..1) {
            try {
                session.registerAnonymous()
                registrationError = null
                break
            } catch (error: IOException) {
                registrationError = error
            }
        }
        if (registrationError != null) throw IOException("无法建立抖音访问会话，请检查网络后重试", registrationError)
        val refreshed = slidesDetail(session, id) ?: shareDetail(session, id)
            ?: throw IOException("抖音暂未返回作品信息，请稍后重试")
        return parseDetail(refreshed, id, quality)
    }

    private fun slidesDetail(session: ShareSession, id: String): JSONObject? {
        val api = "https://www.iesdouyin.com/web/api/v2/aweme/slidesinfo/?aweme_ids=%5B$id%5D&request_source=200"
        return runCatching {
            val body = session.follow(api, "https://www.iesdouyin.com/share/slides/$id/").second
            findDetail(JSONObject(body).optJSONArray("aweme_details"), id)
        }.getOrNull()
    }

    private fun shareDetail(session: ShareSession, id: String): JSONObject? =
        sequenceOf("video", "note").mapNotNull { route ->
            runCatching {
                val html = session.follow("https://www.iesdouyin.com/share/$route/$id/").second
                parseSharePage(html, route, id)
            }.getOrNull()
        }.firstOrNull()

    internal fun parseDetail(detail: JSONObject, wantedId: String, quality: String): List<MediaSource> {
        require(wantedId.matches(Regex("\\d+")) && detail.optString("aweme_id").ifEmpty { detail.optString("awemeId") } == wantedId) {
            "作品编号与分享链接不一致"
        }
        val images = detail.optJSONArray("images")
        if (images != null && images.length() > 0) {
            return (0 until images.length()).map { index ->
                val image = images.optJSONObject(index) ?: throw IOException("第 ${index + 1} 张图片信息无效")
                val name = "douyin_${wantedId}_${(index + 1).toString().padStart(2, '0')}"
                // download_url_list can carry a watermark; keep original display URLs first.
                val imageUrls = (urls(image.optJSONArray("url_list") ?: image.optJSONArray("urlList")) +
                    urls(image.optJSONArray("download_url_list") ?: image.optJSONArray("downloadUrlList"))).distinct()
                val video = image.optJSONObject("video")
                val clipType = image.optInt("clip_type", image.optInt("clipType"))
                if ((video != null && video.length() > 0) || clipType in setOf(3, 5) || image.optInt("live_photo_type", image.optInt("livePhotoType")) > 0) {
                    val urls = video?.let { videoUrls(it, quality) }.orEmpty()
                    if (urls.isEmpty()) throw IOException("第 ${index + 1} 张${if (clipType == 5) "动图" else "实况照片"}没有可下载的动态视频")
                    // Douyin looping photos are saved as Live Photos using their original motion and cover.
                    MediaSource(urls, "${name}_live.mp4", mediaHeaders, livePhotoCoverUrls = imageUrls)
                } else {
                    if (imageUrls.isEmpty()) throw IOException("第 ${index + 1} 张图片没有可下载的地址")
                    MediaSource(imageUrls, "$name.image", mediaHeaders)
                }
            }
        }
        val urls = detail.optJSONObject("video")?.let { videoUrls(it, quality) }.orEmpty()
        if (urls.isEmpty()) throw IOException("这个作品没有可直接下载的视频或图片")
        return listOf(MediaSource(urls, "douyin_$wantedId.mp4", mediaHeaders))
    }

    internal fun parseSharePage(html: String, route: String, wantedId: String): JSONObject? {
        val marker = routerData.find(html) ?: return null
        val data = JSONTokener(html.substring(marker.range.last + 1)).nextValue() as? JSONObject ?: return null
        val items = data.optJSONObject("loaderData")?.optJSONObject("${route}_(id)/page")
            ?.optJSONObject("videoInfoRes")?.optJSONArray("item_list")
        return findDetail(items, wantedId)
    }

    internal fun itemId(url: String): String? = runCatching {
        val uri = URI(url)
        itemPath.find(uri.path.orEmpty())?.groupValues?.get(1) ?: uri.rawQuery.orEmpty()
            .split('&').map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] in setOf("modal_id", "aweme_id", "item_id") && it[1].matches(Regex("\\d+")) }
            ?.get(1)
    }.getOrNull()

    private fun findDetail(items: JSONArray?, id: String): JSONObject? {
        if (items == null) return null
        return (0 until items.length()).mapNotNull(items::optJSONObject).firstOrNull {
            it.optString("aweme_id") == id && (it.optJSONArray("images")?.length()?.let { n -> n > 0 } == true ||
                it.optJSONObject("video")?.let { video -> videoUrls(video, "best").isNotEmpty() } == true)
        }
    }

    private data class Stream(val urls: List<String>, val height: Int, val bitrate: Long)

    private fun videoUrls(video: JSONObject, quality: String): List<String> {
        val gears = video.optJSONArray("bit_rate") ?: video.optJSONArray("bitRateList")
        val streams = (0 until (gears?.length() ?: 0)).mapNotNull { index ->
            val gear = gears?.optJSONObject(index) ?: return@mapNotNull null
            val address = gear.optJSONObject("play_addr") ?: gear.optJSONObject("play_addr_h264")
            val urls = urls(address?.optJSONArray("url_list") ?: gear.optJSONArray("playAddr"))
            if (urls.isEmpty()) null else Stream(urls, address?.optInt("height") ?: gear.optInt("height"), gear.optLong("bit_rate", gear.optLong("bitRate")))
        }
        if (streams.isNotEmpty()) {
            val target = quality.toIntOrNull()?.takeIf { it > 0 }
            val below = target?.let { maxHeight -> streams.filter { it.height in 1..maxHeight } }.orEmpty()
            val pick = when {
                target == null -> streams.maxWith(compareBy<Stream> { it.height }.thenBy { it.bitrate })
                below.isNotEmpty() -> below.maxWith(compareBy<Stream> { it.height }.thenBy { it.bitrate })
                else -> streams.minWith(compareBy<Stream> { kotlin.math.abs(it.height - target) }.thenByDescending { it.bitrate })
            }
            return pick.urls
        }
        val original = (urls(video.optJSONObject("play_addr")?.optJSONArray("url_list")) +
            urls(video.optJSONObject("play_addr_h264")?.optJSONArray("url_list")) + urls(video.optJSONArray("playAddr"))).distinct()
        // Share pages can label the source 1080p while playwm explicitly requests a 720p copy.
        return (original.map { originalPlaybackUrl(it, video, quality) ?: it } + original).distinct()
    }

    private fun originalPlaybackUrl(url: String, video: JSONObject, quality: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme !in setOf("http", "https") || uri.rawUserInfo != null || uri.port !in setOf(-1, 80, 443) ||
            uri.host?.lowercase() !in setOf("aweme.snssdk.com", "www.iesdouyin.com", "www.douyin.com") ||
            uri.rawPath != "/aweme/v1/playwm/") return null
        val width = video.optInt("width")
        val height = video.optInt("height")
        val resolution = if (width > 0 && height > 0) minOf(width, height) else null
        val target = quality.toIntOrNull()?.takeIf { it > 0 }
        val ratio = resolution?.let { if (target == null) it else minOf(it, target) }
        var query = uri.rawQuery.orEmpty()
        if (ratio != null) {
            val parameters = query.split('&').filter { it.isNotEmpty() }
            query = if (parameters.any { it.substringBefore('=') == "ratio" }) {
                parameters.joinToString("&") { if (it.substringBefore('=') == "ratio") "ratio=${ratio}p" else it }
            } else (parameters + "ratio=${ratio}p").joinToString("&")
        }
        return uri.resolve("/aweme/v1/play/" + if (query.isEmpty()) "" else "?$query").toASCIIString()
    }

    private fun urls(values: JSONArray?): List<String> = (0 until (values?.length() ?: 0)).mapNotNull { index ->
        val entry = values?.opt(index)
        (entry as? String ?: (entry as? JSONObject)?.let { it.optString("src").ifEmpty { it.optString("url") } })?.takeIf { value ->
            runCatching { URI(value).let { it.scheme in setOf("http", "https") && !it.host.isNullOrEmpty() && it.userInfo == null } }.getOrDefault(false)
        }
    }

    /** Mobile note SSR can omit every motion field; only the matching desktop work may enrich it. */
    internal fun enrichImages(original: List<MediaSource>, desktop: JSONObject, wantedId: String, quality: String): List<MediaSource> {
        val images = desktop.optJSONArray("images") ?: throw IOException("完整作品没有图集信息")
        check(images.length() == original.size) { "完整图集与分享页的附件数量不一致" }
        val complete = parseDetail(desktop, wantedId, quality)
        return original.mapIndexed { index, source -> complete[index].takeIf { it.livePhotoCoverUrls != null } ?: source }
    }

    internal fun desktopDetail(id: String, openConnection: (URL) -> HttpURLConnection): JSONObject? {
        require(id.matches(Regex("\\d+")))
        val html = ShareSession(openConnection).follow("https://www.douyin.com/note/$id", userAgent = DESKTOP_USER_AGENT).second
        return parseDesktopPage(html, id)
    }

    internal fun parseDesktopPage(html: String, wantedId: String): JSONObject? {
        require(wantedId.matches(Regex("\\d+")))
        val chunks = Regex("self\\.__pace_f\\.push\\(\\[\\s*1\\s*,").findAll(html).mapNotNull { marker ->
            runCatching { JSONTokener(html.substring(marker.range.last + 1)).nextValue() as? String }.getOrNull()
        }.joinToString("")
        fun find(value: Any?, depth: Int = 0): JSONObject? {
            if (depth > 80) return null
            if (value is JSONObject) {
                if (value.optString("awemeId").ifEmpty { value.optString("aweme_id") } == wantedId && value.optJSONArray("images") != null) return value
                val keys = value.keys()
                while (keys.hasNext()) find(value.opt(keys.next()), depth + 1)?.let { return it }
            } else if (value is JSONArray) {
                for (index in 0 until value.length()) find(value.opt(index), depth + 1)?.let { return it }
            }
            return null
        }
        return chunks.lineSequence().mapNotNull { line ->
            val payload = line.substringAfter(':', "")
            if (!payload.startsWith('{') && !payload.startsWith('[')) null
            else runCatching { find(JSONTokener(payload).nextValue()) }.getOrNull()
        }.firstOrNull()
    }

    internal fun allowedUrl(value: String): Boolean = runCatching {
        URI(value).let { uri ->
            val host = uri.host.orEmpty().lowercase()
            uri.scheme in setOf("http", "https") && uri.userInfo == null &&
                listOf("douyin.com", "iesdouyin.com").any { host == it || host.endsWith(".$it") }
        }
    }.getOrDefault(false)

    internal fun allowedCallback(value: String): Boolean = runCatching {
        URI(value).let { uri ->
            uri.scheme.equals("https", true) && uri.host.equals("www.iesdouyin.com", true) &&
                uri.rawUserInfo == null && uri.port in setOf(-1, 443) &&
                uri.rawPath == "/ttwid/union/register/callback/" && uri.rawFragment == null
        }
    }.getOrDefault(false)

    private class ShareSession(val openConnection: (URL) -> HttpURLConnection) {
        private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)

        fun registerAnonymous() {
            val body = """{"region":"cn","aid":1768,"needFid":false,"service":"www.iesdouyin.com","migrate_info":{"ticket":"","source":"node"},"cbUrlProtocol":"https","union":true}"""
            try {
                val data = JSONObject(follow(REGISTER_URL, post = body, permitted = { it == REGISTER_URL }).second)
                if (data.optInt("status_code", -1) != 0) throw IOException("抖音会话接口暂不可用")
                val callback = data.optString("redirect_url")
                if (!allowedCallback(callback)) throw IOException("抖音会话回调地址无效")
                val result = JSONObject(follow(callback, permitted = ::allowedCallback).second)
                if (result.optInt("status_code", -1) != 0) throw IOException("抖音会话建立失败")
            } catch (error: org.json.JSONException) {
                throw IOException("抖音会话接口未返回有效信息", error)
            }
        }

        fun follow(
            url: String,
            referer: String = REFERER,
            post: String? = null,
            permitted: (String) -> Boolean = ::allowedUrl,
            userAgent: String = USER_AGENT,
        ): Pair<String, String> {
            var current = url
            repeat(8) {
                if (!permitted(current)) throw IOException("分享链接重定向到非抖音地址")
                val uri = URI(current)
                val connection = openConnection(uri.toURL())
                try {
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 20_000
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("User-Agent", userAgent)
                    connection.setRequestProperty("Referer", referer)
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    cookies.get(uri, emptyMap()).forEach { (name, values) ->
                        values.forEach { connection.addRequestProperty(name, it) }
                    }
                    if (post != null) {
                        connection.requestMethod = "POST"
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.outputStream.use { it.write(post.toByteArray(Charsets.UTF_8)) }
                    }
                    val code = connection.responseCode
                    cookies.put(uri, connection.headerFields)
                    if (code in listOf(301, 302, 303, 307, 308)) {
                        if (post != null) throw IOException("抖音会话接口返回了意外重定向")
                        val location = connection.getHeaderField("Location") ?: throw IOException("分享地址缺少重定向目标")
                        current = URI(current).resolve(location).toString()
                    } else {
                        if (code !in 200..299) throw IOException("抖音服务返回 HTTP $code")
                        val body = connection.inputStream.use { stream ->
                            val bytes = stream.readBytesLimited(8 * 1024 * 1024)
                            String(bytes, Charsets.UTF_8)
                        }
                        return current to body
                    }
                } finally {
                    connection.disconnect()
                }
            }
            throw IOException("分享链接重定向次数过多")
        }
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size() + count > limit) throw IOException("作品信息过大")
            output.write(buffer, 0, count)
        }
    }
}
