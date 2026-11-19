package cn.secret.downloader

data class MediaSource(
    val urls: List<String>,
    val fileName: String,
    val headers: Map<String, String> = emptyMap(),
    // null is ordinary media; an empty list still identifies a live photo without a supplied cover.
    val livePhotoCoverUrls: List<String>? = null,
    val isGif: Boolean = false,
    val selectionId: String? = null,
    val videoInfo: org.json.JSONObject? = null,
    val previewUrls: List<String> = emptyList(),
) {
    internal val previewCandidates: List<String> get() = previewUrls.ifEmpty {
        livePhotoCoverUrls ?: if (kind == "图片") urls else emptyList()
    }

    internal val kind: String get() = when {
        livePhotoCoverUrls != null -> "实况照片"
        isGif -> "GIF 动图"
        fileName.endsWith(".image") -> "图片"
        else -> "视频"
    }

    internal val selectionKey: String get() {
        val identity = selectionId ?: (livePhotoCoverUrls?.firstOrNull() ?: urls.first()).let {
            java.net.URI(it).rawPath.orEmpty()
        }
        // Include the slot so a changed album cannot reuse another attachment's partial file or journal.
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$fileName\u0000$identity".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
    }
}

internal fun selectMedia(sources: List<MediaSource>, keys: List<String>?): List<MediaSource> {
    if (keys == null) return sources
    require(keys.isNotEmpty() && keys.distinct().size == keys.size) { "请至少选择一项媒体" }
    val byKey = sources.associateBy { it.selectionKey }
    return keys.map { byKey[it] ?: throw java.io.IOException("媒体清单已变化，请返回下载页重新解析并选择") }
}
