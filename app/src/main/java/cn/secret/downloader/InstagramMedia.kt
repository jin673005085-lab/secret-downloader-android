package cn.secret.downloader

import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale

internal object InstagramMedia {
    private val hosts = setOf("instagram.com", "www.instagram.com", "m.instagram.com")
    private val post = Regex("/(p|reel|reels|tv)/([A-Za-z0-9_-]{1,80})/?")
    private val share = Regex("/share/(?:(?:p|reel|reels|tv)/)?[A-Za-z0-9_-]{1,80}/?")
    private const val HOME = "https://www.instagram.com/"

    internal fun failure(message: String?): String {
        val detail = message.orEmpty()
        return when {
            Regex("(?i)(?:HTTP(?: Error)?[ :]*429|too many requests)").containsMatchIn(detail) ->
                "Instagram 请求过于频繁（429），请稍后再试。也可在设置中导入或更新自己的登录 Cookie。"
            detail.contains("No address associated with hostname", true) || detail.contains("name resolution", true) ||
                detail.contains("getaddrinfo", true) || detail.contains("Name or service not known", true) ->
                "手机无法解析 Instagram 域名，请检查网络或此应用的代理连接后重试。"
            detail.contains("login", true) || detail.contains("private", true) || detail.contains("empty media", true) || detail.contains("403") ->
                "Instagram 要求登录或拒绝访问。请检查帖子权限，并在设置中导入或更新自己的登录 Cookie。"
            detail.isBlank() -> "Instagram 返回空内容，请检查网络和帖子权限。需要登录时，可在设置中导入 Cookie。"
            else -> "Instagram 解析失败，请检查网络和帖子权限后重试。"
        }
    }

    internal fun <T> retryRateLimit(check: () -> Unit, wait: () -> Unit, action: () -> T): T {
        repeat(2) { attempt ->
            check()
            try { return action() } catch (error: Exception) {
                check()
                if (attempt == 0 && failure(error.message).contains("429")) wait()
                else throw IOException(failure(error.message))
            }
        }
        error("unreachable")
    }

    private fun stripCredentials(value: Any?) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys().asSequence().toList()
                keys.forEach { key ->
                    if (key.equals("http_headers", true) || key.equals("cookies", true) ||
                        key.equals("cookie", true) || key.equals("authorization", true) || key.equals("proxy-authorization", true)) value.remove(key)
                    else stripCredentials(value.opt(key))
                }
            }
            is JSONArray -> for (index in 0 until value.length()) stripCredentials(value.opt(index))
        }
    }

    fun isHost(uri: URI) = uri.host?.lowercase(Locale.ROOT) in hosts

    fun valid(uri: URI) = isHost(uri) && uri.rawUserInfo == null &&
        (uri.scheme.equals("https", true) && uri.port in setOf(-1, 443) ||
            uri.scheme.equals("http", true) && uri.port in setOf(-1, 80)) &&
        (post.matches(uri.rawPath.orEmpty()) || share.matches(uri.rawPath.orEmpty()))

    fun resolve(url: String, job: DownloadJob): String {
        var uri = URI(url)
        require(valid(uri)) { "请粘贴 Instagram 单条帖子或 Reel 的分享链接" }
        uri = URI(HOME.trimEnd('/') + uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: ""))
        repeat(6) {
            job.check()
            post.matchEntire(uri.rawPath)?.let { match ->
                val kind = if (match.groupValues[1] == "reels") "reel" else match.groupValues[1]
                return "$HOME$kind/${match.groupValues[2]}/"
            }
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Chrome/130.0 Mobile Safari/537.36")
            job.add(connection)
            try {
                val location = connection.getHeaderField("Location")
                if (connection.responseCode == 429) throw IOException(failure("HTTP Error 429"))
                if (connection.responseCode !in setOf(301, 302, 303, 307, 308) || location.isNullOrBlank()) {
                    throw IOException("Instagram 分享短链没有指向公开帖子，请尝试复制帖子原链接")
                }
                uri = uri.resolve(location)
                if (!uri.scheme.equals("https", true) || !valid(uri)) {
                    throw IOException("Instagram 分享短链没有指向公开帖子，请检查链接或登录要求")
                }
            } finally {
                job.remove(connection)
                connection.disconnect()
            }
        }
        throw IOException("Instagram 分享链接跳转次数过多")
    }

    private fun cdnUrl(value: String): String? = runCatching {
        val uri = URI(value)
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        value.takeIf { uri.scheme.equals("https", true) && uri.rawUserInfo == null && uri.port in setOf(-1, 443) &&
            listOf("cdninstagram.com", "fbcdn.net").any { host == it || host.endsWith(".$it") } }
    }.getOrNull()

    private fun objects(array: org.json.JSONArray?) = (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }

    fun sources(info: JSONObject, canonical: String): List<MediaSource> {
        val shortcode = post.matchEntire(URI(canonical).rawPath)?.groupValues?.get(2)
            ?: throw IOException("Instagram 帖子链接无效")
        val entries = info.optJSONArray("entries")
        val items = if (entries == null && info.optString("_type") !in setOf("playlist", "multi_video")) listOf(info)
            else objects(entries).also { check(it.size == (entries?.length() ?: 0)) { "Instagram 未返回完整媒体清单" } }
        check(items.isNotEmpty()) { "Instagram 未返回可下载的图片或视频，请检查帖子权限后重试。" }
        val sources = items.mapIndexed { index, item ->
            val id = item.optString("id").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,100}")) }
                ?: throw IOException("Instagram 第 ${index + 1} 项缺少有效媒体编号")
            val name = "instagram_${shortcode}_${(index + 1).toString().padStart(2, '0')}"
            val photos = objects(item.optJSONArray("thumbnails")).sortedByDescending {
                it.optInt("width").toLong() * it.optInt("height")
            }.mapNotNull { cdnUrl(it.optString("url")) }
            val poster = cdnUrl(item.optString("thumbnail"))
            val previews = (photos.asReversed() + listOfNotNull(poster)).distinct()
            val formats = objects(item.optJSONArray("formats"))
            val direct = cdnUrl(item.optString("url"))
            val hasVideo = formats.isNotEmpty() || (direct != null && item.optString("ext") in setOf("mp4", "webm", "mov", "m4v"))
            if (hasVideo) {
                val safeFormats = formats.filter { cdnUrl(it.optString("url")) != null }
                check(safeFormats.isNotEmpty() || formats.isEmpty()) { "Instagram 第 ${index + 1} 项没有可用的视频地址" }
                val videoInfo = JSONObject(item.toString()).apply {
                    // yt-dlp can retry the entire post if an info-file download fails.
                    remove("webpage_url")
                    remove("original_url")
                    if (formats.isNotEmpty()) put("formats", org.json.JSONArray(safeFormats))
                    stripCredentials(this)
                }
                MediaSource(emptyList(), "$name.mp4", headers = mapOf("Referer" to HOME),
                    selectionId = "instagram:$id:video", videoInfo = videoInfo, previewUrls = previews)
            } else {
                // A missing video format is not a promise that its poster is a photo.
                check(item.optDouble("duration", 0.0) <= 0 && (item.isNull("vcodec") || item.optString("vcodec") in setOf("", "none"))) {
                    "Instagram 第 ${index + 1} 项没有可用的视频地址"
                }
                val urls = (photos + listOfNotNull(poster)).distinct()
                check(urls.isNotEmpty()) { "Instagram 第 ${index + 1} 项没有可用的图片地址" }
                MediaSource(urls, "$name.image", mapOf("Referer" to HOME),
                    selectionId = "instagram:$id:image", previewUrls = previews)
            }
        }
        check(sources.map { it.selectionKey }.distinct().size == sources.size) { "Instagram 返回了重复的媒体编号" }
        return sources
    }
}
