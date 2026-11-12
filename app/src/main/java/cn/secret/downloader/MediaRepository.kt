package cn.secret.downloader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Owns metadata and private playback derivatives; public file deletion is an explicit UI action. */
class MediaRepository private constructor(context: Context) {
    private val worksDirectory = java.io.File(context.applicationContext.filesDir, "works")
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val database = MediaDatabase(context.applicationContext)
    private val records = linkedMapOf<String, MediaItem>()
    private val observers = linkedSetOf<(List<MediaItem>) -> Unit>()
    private val deletedBeforeLoad = mutableSetOf<String>()
    private val dirty = linkedSetOf<String>()
    @Volatile private var loaded = false
    private var flushScheduled = false
    @Volatile private var snapshot = emptyList<MediaItem>()
    @Volatile var persistenceError: String? = null
        private set
    private val flush = Runnable { flushPersistence() }

    val items: List<MediaItem> get() = snapshot
    val isLoaded: Boolean get() = loaded
    fun get(id: String): MediaItem? = snapshot.firstOrNull { it.id == id }

    init {
        worker.execute {
            val restored = try { database.load() } catch (error: Exception) {
                reportPersistenceError(error); emptyList()
            }
            onMain {
                restored.forEach { item ->
                    if (item.id !in records && item.id !in deletedBeforeLoad) records[item.id] = item
                }
                deletedBeforeLoad.clear()
                loaded = true
                publish()
            }
        }
    }

    fun observeAll(observer: (List<MediaItem>) -> Unit): Closeable {
        val closed = AtomicBoolean()
        val listener: (List<MediaItem>) -> Unit = { if (!closed.get()) observer(it) }
        onMain { if (!closed.get()) { observers += listener; listener(snapshot) } }
        return Closeable { closed.set(true); onMain { observers -= listener } }
    }

    fun observeItem(id: String, observer: (MediaItem?) -> Unit): Closeable {
        var delivered = false
        var previous: MediaItem? = null
        return observeAll { items ->
            val item = items.firstOrNull { it.id == id }
            if (item == null && !loaded) return@observeAll
            if (!delivered || item != previous) {
                delivered = true; previous = item; observer(item)
            }
        }
    }

    fun query(search: String = "", sort: MediaSort = MediaSort.RECENT_DOWNLOAD, filter: MediaFilter = MediaFilter.ALL,
              type: MediaType = MediaType.VIDEO, offset: Int = 0, limit: Int = 40): List<MediaItem> =
        MediaQueries.query(snapshot, search, sort, filter, type, offset, limit)

    fun add(item: MediaItem) {
        validate(item)
        onMain {
            deletedBeforeLoad -= item.id
            put(item, immediate = true)
        }
    }

    fun update(item: MediaItem) {
        validate(item)
        onMain { if (item.id in records) put(item, immediate = true) }
    }
    fun setFavorite(id: String, favorite: Boolean) = change(id) { it.copy(favorite = favorite) }
    fun setWatchLater(id: String, watchLater: Boolean) = change(id) { it.copy(watchLater = watchLater) }

    fun rename(id: String, title: String) {
        require(title.isNotBlank()) { "作品名称不能为空" }
        change(id) { it.copy(title = title.trim()) }
    }

    fun recordPlayback(id: String, positionMs: Long, started: Boolean = false, flushImmediately: Boolean = false) {
        onMain { records[id]?.let { put(MediaQueries.playback(it, positionMs, System.currentTimeMillis(), started), immediate = started || flushImmediately) } }
    }

    fun delete(id: String) {
        onMain {
            if (!loaded) deletedBeforeLoad += id
            dirty -= id
            val removed = records.remove(id)
            persist {
                database.delete(id)
                // These are our retained playback derivatives, never the user's public output or source.
                val privateFiles = java.io.File(worksDirectory, id).canonicalFile
                if (privateFiles.parentFile == worksDirectory.canonicalFile) privateFiles.deleteRecursively()
            }
            if (removed != null) publish()
        }
    }

    private fun change(id: String, transform: (MediaItem) -> MediaItem) {
        onMain { records[id]?.let { put(transform(it), immediate = true) } }
    }

    private fun validate(item: MediaItem) {
        require(item.id.isNotBlank() && item.title.isNotBlank()) { "作品名称和编号不能为空" }
        require(item.durationMs >= 0 && item.sizeBytes >= 0 && item.width >= 0 && item.height >= 0 &&
            item.playCount >= 0 && item.lastPositionMs >= 0) { "作品信息无效" }
        require(item.sourceStartMs >= 0 && (item.sourceEndMs == 0L || item.sourceEndMs >= item.sourceStartMs) &&
            (item.fps == null || item.fps > 0) && (item.frameCount == null || item.frameCount >= 0)) { "创作参数无效" }
        org.json.JSONObject(item.params)
    }

    private fun put(item: MediaItem, immediate: Boolean) {
        if (records[item.id] == item) return
        records[item.id] = item
        dirty += item.id
        publish()
        if (immediate) flushPersistence() else if (!flushScheduled) {
            flushScheduled = true; main.postDelayed(flush, 1_500)
        }
    }

    private fun publish() {
        snapshot = records.values.sortedWith(compareByDescending<MediaItem> { it.downloadedAt }.thenBy { it.id })
        observers.toList().forEach { it(snapshot) }
    }

    private fun flushPersistence() {
        main.removeCallbacks(flush); flushScheduled = false
        val batch = dirty.mapNotNull(records::get)
        dirty.clear()
        if (batch.isNotEmpty()) persist { database.save(batch) }
    }

    private fun persist(action: () -> Unit) {
        worker.execute { try { action() } catch (error: Exception) { reportPersistenceError(error) } }
    }

    private fun reportPersistenceError(error: Exception) {
        persistenceError = error.message ?: "作品记录保存失败"
        Log.e("MediaRepository", "作品数据库操作失败", error)
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    companion object {
        @Volatile private var instance: MediaRepository? = null
        fun get(context: Context): MediaRepository = instance ?: synchronized(this) {
            instance ?: MediaRepository(context.applicationContext).also { instance = it }
        }
    }
}
