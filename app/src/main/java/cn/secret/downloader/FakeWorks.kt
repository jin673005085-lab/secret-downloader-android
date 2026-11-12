package cn.secret.downloader

import org.json.JSONObject

/** These bundled demonstration assets are never written to the user's gallery. */
internal object FakeWorks {
    const val GIF_URI = "android.resource://cn.secret.downloader/raw/demo_animation"
    const val MOTION_URI = "android.resource://cn.secret.downloader/raw/demo_motion"
    fun create(now: Long): List<MediaItem> = List(20) { index ->
        val gif = index % 2 == 0
        val type = if (gif) MediaType.GIF else MediaType.LIVE_PHOTO
        val start = (index % 3) * 1_000L
        val duration = if (gif) 3_000L else 9_000L
        MediaItem(id = when (index) { 0 -> "media-gif-01"; 1 -> "media-live-01"; else -> "media-work-${index + 1}" },
            type = type, title = "演示 · ${listOf("城市节奏", "窗边的光", "海浪循环", "云朵旅行", "黄昏留影")[index % 5]} ${index + 1}",
            platform = "本机创作", localUri = if (gif) GIF_URI else null,
            motionVideoUri = if (gif) null else MOTION_URI,
            durationMs = duration, width = if (index % 3 == 0) 320 else 480, height = if (index % 3 == 0) 480 else 320,
            sizeBytes = if (gif) 420_000L + index * 30_000 else 1_500_000L + index * 100_000,
            downloadedAt = now - index * 3_600_000L, favorite = index % 4 == 0, exists = index != 6 && index != 17,
            sourceMediaId = "media-video-${(index % 20 + 1).toString().padStart(2, '0')}",
            sourceStartMs = start, sourceEndMs = start + duration,
            fps = if (gif) 12 else null, frameCount = if (gif) 36 else null,
            params = JSONObject().apply {
                put("mediaId", "media-video-${(index % 20 + 1).toString().padStart(2, '0')}")
                put("startMs", start); put("endMs", start + duration)
                put("fps", 15); put("width", 480); put("quality", "标准"); put("loop", "无限"); put("speed", 1.0)
            }.toString())
    }
}
