package cn.secret.downloader

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Isolated metadata database; never opens the app's library or any media URI. */
object MediaRepositorySmokeTest {
    fun run(context: Context): String {
        val folder = File(context.cacheDir, "media-db-smoke-${System.nanoTime()}")
        check(folder.mkdirs())
        val isolated = object : ContextWrapper(context) {
            override fun getDatabasePath(name: String) = File(folder, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?) =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath, factory, errorHandler)
        }
        try {
            val original = MediaType.entries.mapIndexed { index, type ->
                MediaItem("database-$type", taskId = "task-$index", type = type, title = "数据库 $type",
                    platform = "本地演示", durationMs = 12_000, width = 1080, height = 1440,
                    sizeBytes = 9_000_000_000, downloadedAt = 1_900_000_000_000L + index)
            }
            val modified = MediaQueries.playback(original[0], 4_000, 1_900_000_001_000, true)
                .copy(title = "已重命名的视频", favorite = true, watchLater = true)
            MediaDatabase(isolated).use { db ->
                db.save(original)
                db.save(listOf(modified))
                db.delete(original[1].id)
            }
            val expected = listOf(modified, original[2]).associateBy { it.id }
            val restored = MediaDatabase(isolated).use { it.load() }
            check(restored.associateBy { it.id } == expected) { "Media fields or deletion did not survive database reopen" }
            check(MediaQueries.query(restored, "", MediaSort.RECENT_DOWNLOAD, MediaFilter.ALL,
                MediaType.VIDEO, 0, 40) == listOf(modified)) { "Non-video records leaked into the restored video query" }
            MediaDatabase(isolated).use { db -> restored.forEach { db.delete(it.id) } }
            check(MediaDatabase(isolated).use { it.load() }.isEmpty()) { "Deleted media records were seeded again" }
            return "Isolated media SQLite close/reopen preserves rename, favorite, playback and large sizes; deletions persist without reseeding: PASS"
        } finally {
            check(folder.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            check(folder.deleteRecursively()) { "Could not clean isolated media database" }
        }
    }
}
