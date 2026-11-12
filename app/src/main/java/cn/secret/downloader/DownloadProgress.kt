package cn.secret.downloader

import java.io.File
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap

data class EngineProgress(
    val stage: TaskStage,
    val progress: Float? = null,
    val bytesDone: Long = 0,
    val bytesTotal: Long? = null,
    val speedBps: Long? = null,
    val etaSeconds: Long? = null,
)

data class EngineOutput(val file: File, val isGif: Boolean = false, val motionVideo: File? = null, val cover: File? = null)

class DownloadCanceledException : InterruptedIOException("任务已停止")

internal class DownloadJob(
    val id: String,
    private val onProgress: (EngineProgress) -> Unit = {},
    private val onCancelNative: () -> Unit = {},
) {
    @Volatile var canceled = false
        private set
    private val threads = ConcurrentHashMap.newKeySet<Thread>()
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val processes = ConcurrentHashMap.newKeySet<Process>()
    private var lastAt = 0L
    private var stage: TaskStage? = null

    fun check() { if (canceled || Thread.currentThread().isInterrupted) throw DownloadCanceledException() }
    fun addThread(thread: Thread) { threads += thread; if (canceled) thread.interrupt(); check() }
    fun removeThread(thread: Thread) { threads -= thread }
    fun add(connection: HttpURLConnection) { connections += connection; if (canceled) connection.disconnect(); check() }
    fun remove(connection: HttpURLConnection) { connections -= connection }
    fun add(process: Process) { processes += process; if (canceled) process.destroy(); check() }
    fun remove(process: Process) { processes -= process }

    @Synchronized fun emit(value: EngineProgress, force: Boolean = false) {
        check()
        val now = System.nanoTime()
        if (force || value.progress == 1f || value.stage != stage || now - lastAt >= 200_000_000L) {
            stage = value.stage; lastAt = now; onProgress(value)
        }
    }

    fun cancel() {
        canceled = true
        threads.forEach { it.interrupt() }
        runCatching(onCancelNative)
        connections.forEach { runCatching { it.disconnect() } }
        processes.forEach { runCatching { it.destroy() } }
    }
}

internal object DownloadProgressParser {
    const val PREFIX = "__SECRET_PROGRESS__"
    const val TEMPLATE = "download:$PREFIX%(progress.downloaded_bytes)s|%(progress.total_bytes)s|%(progress.total_bytes_estimate)s|%(progress.speed)s|%(progress.eta)s|%(progress.fragment_index)s|%(progress.fragment_count)s"

    fun ytDlp(progress: Float?, line: String): EngineProgress? {
        if (line.contains("[Merger]") || line.contains("[VideoRemuxer]") || line.contains("[Fixup")) return EngineProgress(TaskStage.MERGING)
        val marker = line.indexOf(PREFIX)
        if (marker >= 0) {
            val fields = line.substring(marker + PREFIX.length).trim().split('|')
            if (fields.size < 7) return null
            val done = number(fields[0]) ?: 0
            val total = number(fields[1])?.takeIf { it > 0 }
            val fragment = number(fields[5])
            val fragments = number(fields[6])?.takeIf { it > 0 }
            val fraction = when {
                total != null -> (done.toDouble() / total).toFloat().coerceIn(0f, 1f)
                fragment != null && fragments != null -> (fragment.toDouble() / fragments).toFloat().coerceIn(0f, 1f)
                else -> null
            }
            return EngineProgress(TaskStage.DOWNLOADING, fraction, done, total, number(fields[3]), number(fields[4]))
        }
        if (!line.contains("[download]") && !line.contains("[#")) return null
        aria2(line)?.let { return it }
        val fragment = Regex("frag\\s+(\\d+)\\s*/\\s*(\\d+)").find(line)
        val percent = Regex("(\\d+(?:\\.\\d+)?)%").find(line)?.groupValues?.get(1)?.toFloatOrNull()
        // The library retains its last callback percentage on unrelated output lines.
        if (fragment == null && percent == null) return null
        val fraction = fragment?.let { (it.groupValues[1].toFloat() / it.groupValues[2].toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f) }
            ?: percent?.div(100f)?.coerceIn(0f, 1f)
        val total = Regex("\\bof\\s+([\\d.]+\\s*[KMGTP]?i?B)\\b", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.let(::bytes)
        val rate = Regex("\\bat\\s+([\\d.]+\\s*[KMGTP]?i?B)/s", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.let(::bytes)
        val eta = Regex("\\bETA\\s+(\\d+(?::\\d+){0,2})").find(line)?.groupValues?.get(1)?.let(::clock)
        return EngineProgress(TaskStage.DOWNLOADING, fraction, bytesTotal = total, speedBps = rate, etaSeconds = eta)
    }

    fun aria2(line: String): EngineProgress? {
        val match = Regex("\\[#\\w+\\s+([\\d.]+\\s*[KMGTP]?i?B?)/([\\d.]+\\s*[KMGTP]?i?B?)\\((\\d+)%\\)", RegexOption.IGNORE_CASE).find(line) ?: return null
        val done = bytes(match.groupValues[1]) ?: return null
        val total = bytes(match.groupValues[2])?.takeIf { it > 0 }
        val rate = Regex("\\bDL:([\\d.]+\\s*[KMGTP]?i?B?)", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.let(::bytes)
        val eta = Regex("\\bETA:([\\dhms]+)").find(line)?.groupValues?.get(1)?.let { value ->
            Regex("(\\d+)([hms])").findAll(value).sumOf { it.groupValues[1].toLong() * when (it.groupValues[2]) { "h" -> 3_600L; "m" -> 60L; else -> 1L } }
        }
        return EngineProgress(TaskStage.DOWNLOADING, total?.let { (done.toDouble() / it).toFloat().coerceIn(0f, 1f) }, done, total, rate, eta)
    }

    fun resumedTotal(header: String?, offset: Long, contentLength: Long): Long? {
        val match = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE).matchEntire(header.orEmpty().trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        if (start != offset || end < start || end >= total || contentLength >= 0 && end - start + 1 != contentLength) return null
        return total
    }

    private fun number(value: String) = value.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it <= Long.MAX_VALUE.toDouble() }?.toLong()
    private fun bytes(value: String): Long? {
        val match = Regex("([\\d.]+)\\s*([KMGTP]?)(i?B)?", RegexOption.IGNORE_CASE).matchEntire(value.trim()) ?: return null
        val amount = match.groupValues[1].toDoubleOrNull() ?: return null
        val index = "KMGTP".indexOf(match.groupValues[2].uppercase()).let { if (match.groupValues[2].isEmpty()) 0 else it + 1 }
        val result = amount * Math.pow(1_024.0, index.toDouble())
        return result.takeIf { it.isFinite() && it >= 0 && it <= Long.MAX_VALUE.toDouble() }?.toLong()
    }
    private fun clock(value: String): Long? = value.split(':').map { it.toLongOrNull() ?: return null }.fold(0L) { total, part -> total * 60 + part }
}
