package cn.secret.downloader

import java.text.Normalizer
import java.util.Locale

enum class MediaType { VIDEO, GIF, LIVE_PHOTO }
enum class MediaSort { RECENT_DOWNLOAD, RECENT_PLAYED, DURATION, SIZE }
enum class MediaFilter { ALL, FAVORITES, WATCH_LATER, UNFINISHED }

data class MediaItem(
    val id: String,
    val taskId: String? = null,
    val type: MediaType = MediaType.VIDEO,
    val title: String,
    val author: String? = null,
    val platform: String = "本机",
    val sourceUrl: String? = null,
    val localUri: String? = null,
    val coverUri: String? = null,
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0,
    val downloadedAt: Long = System.currentTimeMillis(),
    val playCount: Int = 0,
    val lastPlayedAt: Long? = null,
    val lastPositionMs: Long = 0,
    val favorite: Boolean = false,
    val watchLater: Boolean = false,
    val exists: Boolean = true,
    val sourceMediaId: String? = null,
    val sourceStartMs: Long = 0,
    val sourceEndMs: Long = 0,
    val fps: Int? = null,
    val frameCount: Int? = null,
    val params: String = "{}",
    val motionVideoUri: String? = null,
) {
    val isFinished: Boolean get() = durationMs > 0 && lastPositionMs >= durationMs - durationMs / 10
    val isPartiallyWatched: Boolean get() = durationMs > 0 && lastPositionMs > 0 && !isFinished
    val isNew: Boolean get() = playCount == 0 && lastPlayedAt == null
}

internal object MediaQueries {
    private fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    fun query(items: List<MediaItem>, search: String, sort: MediaSort, filter: MediaFilter,
              type: MediaType, offset: Int, limit: Int): List<MediaItem> {
        require(offset >= 0 && limit >= 0) { "分页范围无效" }
        val words = normalized(search.trim()).split(Regex("\\s+")).filter { it.isNotEmpty() }
        val comparator = when (sort) {
            MediaSort.RECENT_DOWNLOAD -> compareByDescending<MediaItem> { it.downloadedAt }
            MediaSort.RECENT_PLAYED -> compareByDescending<MediaItem> { it.lastPlayedAt ?: Long.MIN_VALUE }
            MediaSort.DURATION -> compareByDescending<MediaItem> { it.durationMs }
            MediaSort.SIZE -> compareByDescending<MediaItem> { it.sizeBytes }
        }.thenBy { it.id }
        return items.asSequence().filter { item ->
            item.type == type && when (filter) {
                MediaFilter.ALL -> true
                MediaFilter.FAVORITES -> item.favorite
                MediaFilter.WATCH_LATER -> item.watchLater
                MediaFilter.UNFINISHED -> item.isPartiallyWatched
            } && (words.isEmpty() || normalized("${item.title} ${item.author.orEmpty()} ${item.platform}").let { text ->
                words.all { it in text }
            })
        }.sortedWith(comparator).drop(offset).take(limit).toList()
    }

    fun playback(item: MediaItem, positionMs: Long, now: Long, started: Boolean): MediaItem = item.copy(
        lastPositionMs = if (item.durationMs > 0) positionMs.coerceIn(0, item.durationMs) else positionMs.coerceAtLeast(0),
        lastPlayedAt = now,
        playCount = if (started && item.playCount < Int.MAX_VALUE) item.playCount + 1 else item.playCount,
    )
}
