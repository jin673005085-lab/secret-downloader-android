package cn.secret.downloader

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

internal object MediaCodec {
    fun encode(item: MediaItem): String = JSONObject().apply {
        put("id", item.id); put("taskId", item.taskId); put("type", item.type.name); put("title", item.title)
        put("author", item.author); put("platform", item.platform); put("sourceUrl", item.sourceUrl)
        put("localUri", item.localUri); put("coverUri", item.coverUri); put("durationMs", item.durationMs)
        put("width", item.width); put("height", item.height); put("sizeBytes", item.sizeBytes)
        put("downloadedAt", item.downloadedAt); put("playCount", item.playCount); put("lastPlayedAt", item.lastPlayedAt)
        put("lastPositionMs", item.lastPositionMs); put("favorite", item.favorite); put("watchLater", item.watchLater)
        put("exists", item.exists)
        put("sourceMediaId", item.sourceMediaId); put("sourceStartMs", item.sourceStartMs); put("sourceEndMs", item.sourceEndMs)
        put("fps", item.fps); put("frameCount", item.frameCount); put("params", item.params); put("motionVideoUri", item.motionVideoUri)
    }.toString()

    fun decode(value: String): MediaItem = JSONObject(value).let { data ->
        fun string(key: String): String? = if (data.isNull(key)) null else data.getString(key)
        MediaItem(id = data.getString("id"), taskId = string("taskId"), type = MediaType.valueOf(data.getString("type")),
            title = data.getString("title"), author = string("author"), platform = data.getString("platform"),
            sourceUrl = string("sourceUrl"), localUri = string("localUri"), coverUri = string("coverUri"),
            durationMs = data.getLong("durationMs"), width = data.getInt("width"), height = data.getInt("height"),
            sizeBytes = data.getLong("sizeBytes"), downloadedAt = data.getLong("downloadedAt"), playCount = data.getInt("playCount"),
            lastPlayedAt = if (data.isNull("lastPlayedAt")) null else data.getLong("lastPlayedAt"),
            lastPositionMs = data.getLong("lastPositionMs"), favorite = data.getBoolean("favorite"),
            watchLater = data.getBoolean("watchLater"), exists = data.optBoolean("exists", true),
            sourceMediaId = string("sourceMediaId"), sourceStartMs = data.optLong("sourceStartMs"), sourceEndMs = data.optLong("sourceEndMs"),
            fps = if (data.isNull("fps")) null else data.optInt("fps"),
            frameCount = if (data.isNull("frameCount")) null else data.optInt("frameCount"),
            params = data.optString("params", "{}"), motionVideoUri = string("motionVideoUri"))
    }
}

internal class MediaDatabase(context: Context) : SQLiteOpenHelper(context, "creative_media.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE media_items (id TEXT PRIMARY KEY NOT NULL, item_json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE media_meta (name TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun load(): List<MediaItem> {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val items = mutableListOf<MediaItem>()
            db.query("media_items", arrayOf("item_json"), null, null, null, null, null).use { rows ->
                while (rows.moveToNext()) items += MediaCodec.decode(rows.getString(0))
            }
            val migrated = db.query("media_meta", arrayOf("value"), "name = ?", arrayOf("real_data_v1"),
                null, null, null).use { it.moveToFirst() }
            if (!migrated) {
                // Remove only our old preview metadata, never any physical file or imported record.
                val previews = items.filter { it.id.matches(Regex("media-(video-\\d+|gif-01|live-01|work-\\d+)")) &&
                    (it.localUri == null || it.localUri.startsWith("android.resource:")) }
                previews.forEach { db.delete("media_items", "id = ?", arrayOf(it.id)) }
                items.removeAll(previews.toSet())
                db.insertOrThrow("media_meta", null, ContentValues().apply { put("name", "real_data_v1"); put("value", "1") })
            }
            db.setTransactionSuccessful()
            return items
        } finally { db.endTransaction() }
    }

    fun save(items: List<MediaItem>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            items.forEach { write(db, it) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun delete(id: String) { writableDatabase.delete("media_items", "id = ?", arrayOf(id)) }

    private fun write(db: SQLiteDatabase, item: MediaItem) {
        check(db.insertWithOnConflict("media_items", null, ContentValues().apply {
            put("id", item.id); put("item_json", MediaCodec.encode(item))
        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "无法保存作品记录" }
    }
}
