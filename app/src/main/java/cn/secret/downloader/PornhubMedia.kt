package cn.secret.downloader

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.util.Locale

internal object PornhubMedia {
    fun info(pageUrl: String, definitions: JSONArray, userAgent: String, cookieForUrl: (String) -> String?): JSONObject {
        val page = URI(pageUrl)
        val host = page.host?.lowercase(Locale.ROOT).orEmpty()
        require(page.scheme in setOf("https", "http") && page.rawUserInfo == null && page.port in setOf(-1, 80, 443) &&
            listOf("pornhub.com", "pornhub.org").any { host == it || host.endsWith(".$it") }) { "不是有效的 Pornhub 视频链接" }
        val id = page.rawQuery?.split('&')?.firstOrNull { it.substringBefore('=') == "viewkey" }?.substringAfter('=')
            ?: Regex("/embed/([a-zA-Z0-9]+)/?").matchEntire(page.path)?.groupValues?.get(1)
        require(id != null && Regex("[a-zA-Z0-9]{1,128}").matches(id)) { "请使用单个 Pornhub 视频的完整链接" }
        require(userAgent.isNotBlank() && userAgent.none { it == '\r' || it == '\n' }) { "浏览器标识无效" }
        require(definitions.length() <= 256) { "页面返回的媒体格式过多" }
        val origin = "${page.scheme}://${page.rawAuthority}"
        val headers = JSONObject().put("User-Agent", userAgent).put("Referer", "$origin/").put("Origin", origin)
        val formats = JSONArray()
        val seen = mutableSetOf<String>()
        for (index in 0 until definitions.length()) {
            val definition = definitions.optJSONObject(index) ?: continue
            val url = definition.optString("videoUrl")
            val media = mediaUri(url) ?: continue
            if (!seen.add(url)) continue
            val hls = media.path.endsWith(".m3u8", true)
            val height = definition.opt("quality")?.toString()?.let { Regex("(\\d{2,5})[pP]?").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
                ?: Regex("(?:^|[/_])(\\d{2,5})[pP]?_\\d+[kK]").find(media.path)?.groupValues?.get(1)?.toIntOrNull()
            val formatHeaders = JSONObject(headers.toString())
            cookieForUrl(url)?.takeIf { it.isNotBlank() && it.none { char -> char == '\r' || char == '\n' } }
                ?.let { formatHeaders.put("Cookie", it) }
            val format = JSONObject().put("url", url).put("ext", "mp4")
                .put("format_id", "${if (hls) "hls" else "mp4"}-${height ?: "auto"}-$index")
                // HlsFD expects a media playlist; FFmpeg also accepts a master if the page did not expose variants.
                .put("protocol", if (hls) "m3u8" else media.scheme)
                .put("http_headers", formatHeaders)
            if (height != null && height in 1..10000) format.put("height", height)
            formats.put(format)
        }
        if (formats.length() == 0) throw IOException("浏览器尚未取得可下载的视频地址，请确认该视频可公开播放")
        return JSONObject().put("id", id).put("title", "Pornhub_$id")
            .put("extractor", "PornhubBrowser").put("extractor_key", "PornhubBrowser")
            .put("formats", formats).put("http_headers", headers)
    }

    private fun mediaUri(value: String): URI? = runCatching {
        require(value.length <= 16384)
        val uri = URI(value)
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')?.removeSurrounding("[", "]") ?: return null
        require(uri.scheme in setOf("https", "http") && uri.rawUserInfo == null && uri.port in setOf(-1, 80, 443))
        require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local"))
        if (':' in host || host.all { it.isDigit() || it == '.' } || host.startsWith("0x")) {
            // Only numeric literals are resolved here; ordinary CDN names must follow the phone's VPN DNS route.
            if (':' !in host) require(host.split('.').let { parts ->
                parts.size == 4 && parts.all { part -> part.toIntOrNull() in 0..255 && (part == "0" || !part.startsWith('0')) }
            })
            val address = InetAddress.getByName(host)
            require(!address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress &&
                !address.isSiteLocalAddress && !address.isMulticastAddress)
            val bytes = address.address
            require(bytes.size != 16 || bytes[0].toInt() and 0xfe != 0xfc)
            if (bytes.size == 4) require((bytes[0].toInt() and 255) !in setOf(0, 127) &&
                !(bytes[0].toInt() and 255 == 100 && bytes[1].toInt() and 255 in 64..127))
        } else require('.' in host)
        require(uri.path.endsWith(".mp4", true) || uri.path.endsWith(".m3u8", true))
        require(!uri.path.contains("/video/get_media", true))
        uri
    }.getOrNull()
}
