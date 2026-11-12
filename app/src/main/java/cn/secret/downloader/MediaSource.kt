package cn.secret.downloader

data class MediaSource(
    val urls: List<String>,
    val fileName: String,
    val headers: Map<String, String> = emptyMap(),
    // null is ordinary media; an empty list still identifies a live photo without a supplied cover.
    val livePhotoCoverUrls: List<String>? = null,
    val isGif: Boolean = false,
)
