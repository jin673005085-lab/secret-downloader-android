package cn.secret.downloader

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import java.util.concurrent.Executors

/** Metadata is removed only after an explicitly selected physical deletion succeeds. */
internal object MediaDeletion {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    fun canDeleteFile(item: MediaItem): Boolean = item.exists && item.localUri?.let { Uri.parse(it).scheme == "content" } == true

    fun delete(context: Context, repository: MediaRepository, items: List<MediaItem>, files: Boolean, onDone: (Int) -> Unit) {
        if (!files) { items.forEach { repository.delete(it.id) }; onDone(0); return }
        val app = context.applicationContext
        worker.execute {
            val deleted = items.filter { item ->
                canDeleteFile(item) && runCatching {
                    val uri = Uri.parse(item.localUri!!)
                    if (DocumentsContract.isDocumentUri(app, uri)) DocumentsContract.deleteDocument(app.contentResolver, uri)
                    else app.contentResolver.delete(uri, null, null) > 0
                }.getOrDefault(false)
            }
            main.post { deleted.forEach { repository.delete(it.id) }; onDone(items.size - deleted.size) }
        }
    }
}
