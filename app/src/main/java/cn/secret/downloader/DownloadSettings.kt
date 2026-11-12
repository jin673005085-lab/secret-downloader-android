package cn.secret.downloader

import android.content.Context

data class DownloadSettings(val quality: String = "best", val threads: Int = 4) {
    init {
        require(quality in QUALITIES) { "不支持的下载规格" }
        require(threads in 1..16) { "线程数需要在 1 到 16 之间" }
    }

    fun save(context: Context) {
        context.getSharedPreferences("download_settings", Context.MODE_PRIVATE).edit()
            .putString("quality", quality)
            .putInt("threads", threads)
            .apply()
    }

    companion object {
        val QUALITIES = listOf("best", "2160", "1440", "1080", "720", "480")
        val QUALITY_LABELS = listOf("可用最高规格（默认）", "2160P / 4K", "1440P / 2K", "1080P", "720P", "480P")

        fun load(context: Context): DownloadSettings {
            val prefs = context.getSharedPreferences("download_settings", Context.MODE_PRIVATE)
            val quality = prefs.getString("quality", "best")?.takeIf { it in QUALITIES } ?: "best"
            return DownloadSettings(quality, prefs.getInt("threads", 4).coerceIn(1, 16))
        }
    }
}
