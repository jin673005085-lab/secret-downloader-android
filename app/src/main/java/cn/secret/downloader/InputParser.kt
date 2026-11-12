package cn.secret.downloader

import java.net.URI
import java.util.Locale

enum class Platform { DOUYIN, BILIBILI, YOUTUBE, TWITTER, TIKTOK, PORNHUB }

data class ParsedInput(val url: String, val platform: Platform)

object InputParser {
    private val urlPattern = Regex("""https?://[^\s<>"'，。；;、（）【】「」『』“”‘’()\[\]]+""", RegexOption.IGNORE_CASE)
    private val trailingPunctuation = ".,，!！?？:：;；)）]】}」』>。"
    private val domains = linkedMapOf(
        Platform.DOUYIN to listOf("douyin.com", "iesdouyin.com"),
        Platform.BILIBILI to listOf("bilibili.com", "b23.tv", "bili2233.cn"),
        Platform.YOUTUBE to listOf("youtube.com", "youtu.be", "youtube-nocookie.com"),
        Platform.TWITTER to listOf("x.com", "twitter.com", "pbs.twimg.com", "video.twimg.com"),
        Platform.TIKTOK to listOf("tiktok.com"),
        Platform.PORNHUB to listOf("pornhub.com", "pornhub.org"),
    )

    fun extractUrl(text: String): String = urlPattern.find(text)?.value
        ?.trimEnd { it in trailingPunctuation }
        ?.takeIf { it.isNotEmpty() }
        ?: throw IllegalArgumentException("没有找到链接，请粘贴完整链接或分享文字")

    fun parse(text: String): ParsedInput {
        var error = IllegalArgumentException("没有找到链接，请粘贴完整链接或分享文字")
        for (match in urlPattern.findAll(text)) {
            val url = match.value.trimEnd { it in trailingPunctuation }
            try {
                return parseUrl(url)
            } catch (invalid: IllegalArgumentException) {
                error = invalid
            }
        }
        throw error
    }

    private fun parseUrl(url: String): ParsedInput {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            throw IllegalArgumentException("链接格式不正确，请重新复制")
        }
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) { "仅支持网页链接" }
        require(uri.rawUserInfo == null) { "链接格式不正确，请重新复制" }
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')
            ?: throw IllegalArgumentException("链接格式不正确，请重新复制")
        val platform = domains.entries.firstOrNull { (_, hosts) ->
            hosts.any { host == it || host.endsWith(".$it") }
        }?.key ?: throw IllegalArgumentException("暂不支持这个链接，请使用抖音、哔哩哔哩、YouTube、X、TikTok 或 Pornhub 链接")
        return ParsedInput(url, platform)
    }
}
