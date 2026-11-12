package cn.secret.downloader

internal object MediaImportRules {
    const val DOWNLOAD = "Download/不能说的秘密/"
    const val GIF = "Pictures/不能说的秘密/GIF/"
    const val LIVE = "Pictures/不能说的秘密/"

    fun matches(type: MediaType, path: String, name: String, mime: String): Boolean = when (type) {
        MediaType.VIDEO -> path.startsWith(DOWNLOAD) && mime.startsWith("video/")
        MediaType.GIF -> path.startsWith(GIF) && mime.equals("image/gif", ignoreCase = true)
        MediaType.LIVE_PHOTO -> path == LIVE && name.endsWith("_MP.jpg", ignoreCase = true) && mime.equals("image/jpeg", ignoreCase = true)
    }

    /** Refresh file facts without rolling back a rename, bookmark, favorite, or task association. */
    fun merge(current: MediaItem, scanned: MediaItem): MediaItem = current.copy(
        coverUri = current.coverUri ?: scanned.coverUri, durationMs = scanned.durationMs,
        width = scanned.width, height = scanned.height, sizeBytes = scanned.sizeBytes, exists = true,
        motionVideoUri = scanned.motionVideoUri ?: current.motionVideoUri,
        frameCount = current.frameCount ?: scanned.frameCount, fps = current.fps ?: scanned.fps,
    )
}
