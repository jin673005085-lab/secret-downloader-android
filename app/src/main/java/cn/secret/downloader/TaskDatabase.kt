package cn.secret.downloader

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

internal data class StoredTask(val task: Task, val logs: List<String>)

internal object TaskCodec {
    fun encode(task: Task): String = JSONObject().apply {
        put("id", task.id); put("type", task.type.name); put("title", task.title)
        put("sourceUrl", task.sourceUrl); put("coverUri", task.coverUri)
        put("status", task.status.name); put("stage", task.stage.name); put("progress", task.progress)
        put("bytesDone", task.bytesDone); put("bytesTotal", task.bytesTotal)
        put("speedBps", task.speedBps); put("etaSeconds", task.etaSeconds)
        put("createdAt", task.createdAt); put("finishedAt", task.finishedAt)
        put("outputUri", task.outputUri); put("errorMessage", task.errorMessage)
        put("params", task.params); put("attempt", task.attempt)
    }.toString()

    fun decode(value: String): Task = JSONObject(value).let { data ->
        fun string(key: String): String? = if (data.isNull(key)) null else data.getString(key)
        fun long(key: String): Long? = if (data.isNull(key)) null else data.getLong(key)
        Task(id = data.getString("id"), type = TaskType.valueOf(data.getString("type")),
            title = data.getString("title"), sourceUrl = string("sourceUrl"), coverUri = string("coverUri"),
            status = TaskStatus.valueOf(data.getString("status")), stage = TaskStage.valueOf(data.getString("stage")),
            progress = if (data.isNull("progress")) null else data.getDouble("progress").toFloat(),
            bytesDone = data.getLong("bytesDone"), bytesTotal = long("bytesTotal"), speedBps = long("speedBps"),
            etaSeconds = long("etaSeconds"), createdAt = data.getLong("createdAt"), finishedAt = long("finishedAt"),
            outputUri = string("outputUri"), errorMessage = string("errorMessage"),
            params = data.getString("params"), attempt = data.optInt("attempt", 0))
    }
}

internal class TaskDatabase(context: Context) : SQLiteOpenHelper(context, "creative_tasks.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tasks (id TEXT PRIMARY KEY NOT NULL, task_json TEXT NOT NULL, log_json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE task_meta (name TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun loadAndRestore(): List<StoredTask> {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val tasks = mutableListOf<StoredTask>()
            db.query("tasks", arrayOf("task_json", "log_json"), null, null, null, null, null).use { rows ->
                while (rows.moveToNext()) {
                    val task = TaskCodec.decode(rows.getString(0))
                    val log = JSONArray(rows.getString(1))
                    val restored = TaskTransitions.interruptedOnRestart(task)
                    val lines = List(log.length()) { log.getString(it) }.let {
                        if (task == restored) it else (it + "应用重新启动，未完成任务已中断，等待手动继续。").takeLast(100)
                    }
                    tasks += StoredTask(restored, lines)
                }
            }
            val seeded = db.query("task_meta", arrayOf("value"), "name = ?", arrayOf("real_data_v1"),
                null, null, null).use { it.moveToFirst() }
            if (!seeded) {
                val previews = tasks.filter { it.task.id.startsWith("demo-") && it.task.sourceUrl == null && it.task.title.startsWith("示例 ·") }
                previews.forEach { db.delete("tasks", "id = ?", arrayOf(it.task.id)) }
                tasks.removeAll(previews.toSet())
                db.insertOrThrow("task_meta", null, ContentValues().apply {
                    put("name", "real_data_v1"); put("value", "1")
                })
            }
            val fixturesRemoved = db.query("task_meta", arrayOf("value"), "name = ?", arrayOf("test_fixture_cleanup_v1"),
                null, null, null).use { it.moveToFirst() }
            if (!fixturesRemoved) {
                // Old interrupted instrumentation left metadata in the user's task list, never real outputs.
                val fixtures = tasks.filter { isLegacyTestTask(it.task) }
                fixtures.forEach { db.delete("tasks", "id = ?", arrayOf(it.task.id)) }
                tasks.removeAll(fixtures.toSet())
                db.insertOrThrow("task_meta", null, ContentValues().apply {
                    put("name", "test_fixture_cleanup_v1"); put("value", "1")
                })
            }
            tasks.forEach { write(db, it) }
            db.setTransactionSuccessful()
            return tasks
        } finally { db.endTransaction() }
    }

    fun save(tasks: List<StoredTask>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            tasks.forEach { write(db, it) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun delete(id: String) {
        writableDatabase.delete("tasks", "id = ?", arrayOf(id))
    }

    private fun write(db: SQLiteDatabase, stored: StoredTask) {
        check(db.insertWithOnConflict("tasks", null, ContentValues().apply {
            put("id", stored.task.id)
            put("task_json", TaskCodec.encode(stored.task))
            put("log_json", JSONArray(stored.logs).toString())
        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "无法保存任务记录" }
    }
}

internal fun isLegacyTestTask(task: Task) = task.sourceUrl == "https://example.invalid/task-smoke" &&
    task.title.startsWith("测试 · ") && task.outputUri == null
