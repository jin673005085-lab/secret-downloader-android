package cn.secret.downloader

import android.content.Context
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object DownloadEngine {
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36"
    @Volatile private var initialized = false
    private val active = ConcurrentHashMap<String, DownloadJob>()

    fun download(
        context: Context,
        text: String,
        directory: File,
        settings: DownloadSettings,
        onStatus: (String) -> Unit,
    ): List<File> = downloadJob(context, text, directory, settings, UUID.randomUUID().toString(), {}, onStatus = onStatus)

    fun cancel(jobId: String) { active[jobId]?.cancel() }

    fun downloadJob(
        context: Context, text: String, directory: File, settings: DownloadSettings,
        jobId: String, onProgress: (EngineProgress) -> Unit,
        onOutput: (EngineOutput) -> Unit = {}, onStatus: (String) -> Unit = {},
    ): List<File> {
        require(jobId.isNotBlank()) { "任务编号不能为空" }
        val job = DownloadJob(jobId, onProgress) { YoutubeDL.getInstance().destroyProcessById(jobId) }
        check(active.putIfAbsent(jobId, job) == null) { "该任务已在执行" }
        try {
        job.addThread(Thread.currentThread())
        job.emit(EngineProgress(TaskStage.PARSING), force = true)
        val input = InputParser.parse(text)
        check(directory.isDirectory || directory.mkdirs()) { "无法创建下载临时目录" }
        initialize(context, onStatus)
        job.check()
        val result = when (input.platform) {
            Platform.DOUYIN -> {
                onStatus("正在解析作品…")
                val open: (URL) -> HttpURLConnection = { url ->
                    job.check()
                    (url.openConnection() as HttpURLConnection).also(job::add)
                }
                var sources = DouyinResolver.resolve(input.url, settings.quality, open)
                if (sources.any { it.fileName.endsWith(".image") }) {
                    val id = checkNotNull(Regex("^douyin_(\\d+)_").find(sources.first().fileName)?.groupValues?.get(1))
                    onStatus("正在检查图集的原始动态资源…")
                    val direct = runCatching { DouyinResolver.desktopDetail(id, open) }.getOrNull()
                    job.check()
                    val desktop = direct ?: DouyinBrowser.resolve(context, id, job::check)
                    sources = DouyinResolver.enrichImages(sources, desktop, id, settings.quality)
                }
                downloadSources(sources, directory, settings.threads, onStatus, context, job, onOutput)
            }
            Platform.TWITTER -> {
                onStatus("正在解析图片和视频…")
                val sources = try {
                    twitterSources(input.url, settings.quality, job)
                } catch (error: Exception) {
                    job.check()
                    // Direct image URLs must never fall through to a video-only extractor.
                    if (URI(input.url).host.equals("pbs.twimg.com", true)) throw error
                    onStatus("公开附件接口暂不可用，正在尝试备用解析…")
                    null
                }
                if (sources != null) downloadSources(sources, directory, settings.threads, onStatus, context, job, onOutput)
                else downloadWithYtDlp(context, input.url, directory, settings, onStatus, job = job, onOutput = onOutput)
            }
            Platform.PORNHUB -> {
                val info = PornhubBrowser.resolve(context, input.url, onStatus)
                val infoFile = File(directory, "browser-info.json").apply { writeText(info.toString()) }
                try {
                    downloadWithYtDlp(context, input.url, directory, settings, onStatus, infoFile, job, onOutput)
                } finally {
                    infoFile.delete()
                }
            }
            else -> downloadWithYtDlp(context, input.url, directory, settings, onStatus, job = job, onOutput = onOutput)
        }
        job.check()
        check(result.isNotEmpty() && result.all { it.isFile && it.length() > 0 }) { "下载结束，但没有找到完整媒体文件" }
        return result
        } catch (error: Exception) {
            job.check()
            throw error
        } finally {
            job.removeThread(Thread.currentThread())
            active.remove(jobId, job)
        }
    }

    @Synchronized
    private fun initialize(context: Context, onStatus: (String) -> Unit) {
        if (initialized) return
        onStatus("首次使用，正在准备下载引擎…")
        YoutubeDL.getInstance().init(context.applicationContext)
        FFmpeg.getInstance().init(context.applicationContext)
        Aria2c.getInstance().init(context.applicationContext)
        initialized = true
    }

    private fun downloadWithYtDlp(
        context: Context,
        url: String,
        directory: File,
        settings: DownloadSettings,
        onStatus: (String) -> Unit,
        infoFile: File? = null,
        job: DownloadJob,
        onOutput: (EngineOutput) -> Unit,
    ): List<File> {
        initialize(context, onStatus)
        val proxy = systemProxyFor(url)
        onStatus(if (infoFile != null) "浏览器解析完成，正在下载…" else if (proxy == null) "正在解析并下载…" else "正在通过系统代理解析并下载…")
        val request = (if (infoFile == null) YoutubeDLRequest(url) else YoutubeDLRequest(emptyList())).apply {
            if (infoFile != null) addOption("--load-info-json", infoFile.absolutePath)
            // The native Python process cannot read Android's HTTP proxy/PAC settings itself.
            if (proxy != null) addOption("--proxy", proxy)
            addOption("--format", formatSelector(settings.quality))
            addOption("--merge-output-format", "mp4")
            addOption("--no-playlist")
            addOption("--concurrent-fragments", settings.threads.coerceIn(1, 16))
            addOption("--downloader", "libaria2c.so")
            // Custom arguments follow the library's injected aria2 options, which otherwise replace the thread settings.
            val certificate = File(context.noBackupFilesDir, "youtubedl-android/packages/python/usr/etc/tls/cert.pem")
            addCommands(listOf("--downloader-args", "aria2c:-x ${settings.threads.coerceIn(1, 16)} -s ${settings.threads.coerceIn(1, 16)} -k 1M --summary-interval=1 --ca-certificate=${certificate.absolutePath}"))
            addOption("--restrict-filenames")
            addOption("--no-mtime")
            addOption("--newline")
            addOption("--no-simulate")
            addOption("--progress")
            addOption("--continue")
            addOption("--progress-template", DownloadProgressParser.TEMPLATE)
            addOption("--socket-timeout", 20)
            addOption("--retries", 3)
            addOption("--fragment-retries", 3)
            addOption("--print", "after_move:filepath")
            addOption("--output", File(directory, "%(id)s_%(autonumber)03d.%(ext)s").absolutePath)
        }
        var lastStatusAt = 0L
        try {
            YoutubeDL.getInstance().execute(
                request = request,
                processId = job.id,
                redirectErrorStream = false,
                callback = { progress, _, line ->
                    job.check()
                    DownloadProgressParser.ytDlp(progress, line)?.let { value ->
                        job.emit(value)
                        val now = System.nanoTime()
                        if (now - lastStatusAt >= 200_000_000L) {
                            lastStatusAt = now
                            onStatus(if (value.stage == TaskStage.MERGING) "正在合并视频与音频…" else
                                value.progress?.let { "下载中 ${(it * 100).toInt()}%" } ?: "正在下载…")
                        }
                    }
                },
            )
        } catch (error: Exception) {
            job.check()
            throw IOException(ytDlpFailureMessage(error.message), error)
        }
        return directory.walkTopDown().filter {
            it.isFile && it.length() > 0 && it.extension.lowercase(Locale.ROOT) in
                setOf("mp4", "mkv", "webm", "mov", "m4v", "jpg", "jpeg", "webp", "png", "gif", "avif", "m4a", "mp3", "opus", "ogg") &&
                !it.name.contains(Regex("\\.f\\d+\\."))
        }.toList().also {
            check(it.isNotEmpty()) { "解析已结束，但没有生成可保存的媒体文件" }
            it.forEach { file -> onOutput(EngineOutput(file)) }
        }
    }

    internal fun ytDlpFailureMessage(message: String?): String {
        val detail = message?.trim().orEmpty()
        if (listOf("No address associated with hostname", "Name or service not known",
                "Temporary failure in name resolution", "getaddrinfo failed", "Unable to resolve host")
                .any { detail.contains(it, ignoreCase = true) }) {
            return "域名解析失败，无法连接到请求的服务器。请在 Clash / VPN 的分应用设置中确认“不能说的秘密”已纳入代理，并检查代理的 DNS 设置后重试。手机和电脑连接同一个 Wi-Fi，不代表使用相同的代理和 DNS。"
        }
        if (detail.contains("no impersonate target is available", ignoreCase = true) &&
            detail.contains("HTTP Error 403", ignoreCase = true)) {
            return "站点拒绝访问（HTTP 403）。日志还提示浏览器请求模拟不可用。请先确认手机代理及该链接可访问；若仍失败，需要支持站点验证的解析引擎。"
        }
        return detail.takeLast(1600).ifEmpty { "请检查网络和链接是否可公开访问" }
    }

    internal fun systemProxyFor(url: String, selector: ProxySelector? = ProxySelector.getDefault()): String? {
        // shortcut: one proxy per job; use a routing transport if CDN URLs need separate PAC rules.
        val proxy = selector?.select(URI(url))?.firstOrNull() ?: return null
        if (proxy.type() == Proxy.Type.DIRECT) return null
        require(proxy.type() == Proxy.Type.HTTP) { "暂不支持此系统代理类型，请使用 HTTP 代理或将本应用纳入 VPN" }
        val address = proxy.address() as? InetSocketAddress ?: throw IOException("系统代理地址无效")
        require(address.port in 1..65535) { "系统代理端口无效" }
        return URI("http", null, address.hostString, address.port, null, null, null).toASCIIString()
    }

    internal fun formatSelector(quality: String): String {
        val height = quality.toIntOrNull()?.takeIf { it > 0 }
        return if (height == null) "bestvideo+bestaudio/best/bestvideo"
        else "bestvideo[height<=?$height]+bestaudio/best[height<=?$height]/bestvideo[height<=?$height]/worstvideo+bestaudio/worst/worstvideo"
    }

    internal fun downloadSources(
        sources: List<MediaSource>,
        directory: File,
        threads: Int,
        onStatus: (String) -> Unit,
        nativeContext: Context? = null,
        job: DownloadJob? = null,
        onOutput: (EngineOutput) -> Unit = {},
    ): List<File> {
        check(sources.isNotEmpty()) { "作品中没有可下载的图片或视频" }
        val workerCount = minOf(threads.coerceIn(1, 16), sources.size)
        val connectionsPerFile = maxOf(1, threads.coerceIn(1, 16) / workerCount)
        val pool = Executors.newFixedThreadPool(workerCount)
        val progress = mutableMapOf<String, EngineProgress>()
        fun updateProgress(key: String, value: EngineProgress) {
            synchronized(progress) {
                progress[key] = value
                val values = progress.values
                val done = values.sumOf { it.bytesDone }
                val total = if (values.size == sources.size && values.all { it.bytesTotal != null }) values.sumOf { it.bytesTotal!! } else null
                val speed = values.mapNotNull { it.speedBps }.sum().takeIf { it > 0 }
                job?.emit(EngineProgress(TaskStage.DOWNLOADING, total?.takeIf { it > 0 }?.let { (done.toDouble() / it).toFloat().coerceIn(0f, 1f) },
                    done, total, speed, if (total != null && speed != null) ((total - done).coerceAtLeast(0) / speed) else null))
            }
        }
        try {
            val completed = java.util.concurrent.atomic.AtomicInteger()
            val jobs = sources.map { source ->
                Callable {
                    job?.addThread(Thread.currentThread())
                    try {
                    var file = downloadSource(source, directory, nativeContext, connectionsPerFile, job) { updateProgress(source.fileName, it) }
                    var motionVideo: File? = null
                    var coverFile: File? = null
                    if (source.livePhotoCoverUrls != null) {
                        job?.emit(EngineProgress(TaskStage.MERGING), force = true)
                        val context = nativeContext ?: throw IOException("实况封装需要 Android 运行环境")
                        onStatus("识别到实况图，正在合成实况图片…")
                        val cover = if (source.livePhotoCoverUrls.isEmpty()) null else try {
                            downloadSource(MediaSource(source.livePhotoCoverUrls,
                                "${file.nameWithoutExtension}_cover.image", source.headers), directory, context, connectionsPerFile, job)
                        } catch (_: IOException) {
                            job?.check()
                            null // A missing cover can be recovered from the intact video.
                        }
                        val sidecars = File(directory, "live-sidecars").apply { check(isDirectory || mkdirs()) }
                        val stem = file.nameWithoutExtension
                        val preparedMotion = File(sidecars, "${stem}_motion.mp4")
                        val preparedCover = File(sidecars, "${stem}_cover.jpg")
                        val target = File(directory, "${stem}_MP.jpg")
                        val ready = File(directory, "${stem}_MP.complete")
                        val reusable = target.isFile && ready.isFile && validPrefix(target, true)
                        if (!reusable && target.exists()) check(target.delete()) { "无法清理未完成的实况文件" }
                        val live = if (reusable) target else MotionPhotoConverter.convert(context, file,
                            target, cover, onStatus = onStatus, onPrepared = { image, movie ->
                                job?.check()
                                preservePrepared(image, preparedCover, job)
                                preservePrepared(movie, preparedMotion, job)
                            }).also { job?.check(); ready.writeText("complete") }
                        motionVideo = preparedMotion.takeIf { it.isFile && it.length() > 0 } ?: file
                        coverFile = preparedCover.takeIf { it.isFile && it.length() > 0 } ?: cover
                        file = live
                    }
                    job?.check()
                    synchronized(onOutput) { onOutput(EngineOutput(file, source.isGif, motionVideo, coverFile)) }
                    onStatus("已下载 ${completed.incrementAndGet()}/${sources.size} 个文件")
                    file
                    } finally { job?.removeThread(Thread.currentThread()) }
                }
            }
            val futures = pool.invokeAll(jobs)
            return futures.map { future ->
                try { future.get() }
                catch (error: java.util.concurrent.ExecutionException) {
                    job?.check()
                    throw IOException(error.cause?.message ?: "附件下载失败", error.cause)
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    internal fun downloadSource(source: MediaSource, directory: File, nativeContext: Context? = null, threads: Int = 1,
                                job: DownloadJob? = null, onProgress: (EngineProgress) -> Unit = {}): File {
        require(source.fileName == File(source.fileName).name && source.fileName !in setOf(".", "..")) { "媒体文件名无效" }
        val isImage = source.fileName.substringAfterLast('.').lowercase(Locale.ROOT) in setOf("image", "jpg", "jpeg", "png", "webp", "gif", "avif")
        val existing = if (isImage) directory.listFiles()?.firstOrNull {
            it.isFile && it.nameWithoutExtension == source.fileName.substringBeforeLast('.') &&
                it.extension.lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png", "webp", "gif", "avif") && validPrefix(it, true)
        } else File(directory, source.fileName).takeIf { it.isFile && validPrefix(it, false) }
        if (existing != null) {
            job?.check()
            onProgress(EngineProgress(TaskStage.DOWNLOADING, 1f, existing.length(), existing.length()))
            return existing
        }
        val part = File(directory, source.fileName + ".part")
        var lastError: Exception? = null
        for (url in source.urls.distinct()) {
            job?.check()
            if (Thread.currentThread().isInterrupted) throw DownloadCanceledException()
            try {
                if (nativeContext != null && (threads > 1 || File(part.path + ".aria2").isFile)) {
                    try {
                        transferWithAria2(nativeContext, url, part, source.headers, threads, job, onProgress)
                    } catch (_: IOException) {
                        job?.check()
                        if (Thread.currentThread().isInterrupted) throw DownloadCanceledException()
                        part.delete()
                        File(part.path + ".aria2").delete()
                        transfer(url, part, source.headers, job, onProgress)
                    }
                } else {
                    if (File(part.path + ".aria2").exists()) { part.delete(); File(part.path + ".aria2").delete() }
                    transfer(url, part, source.headers, job, onProgress)
                }
                val prefix = part.inputStream().use { input -> ByteArray(64).let { bytes -> bytes.copyOf(input.read(bytes).coerceAtLeast(0)) } }
                val name = if (isImage) {
                    val extension = imageExtension(prefix) ?: throw IOException("图片地址返回的内容不是有效图片")
                    source.fileName.substringBeforeLast('.') + "." + extension
                } else {
                    check(prefix.size >= 12 && String(prefix, 4, 4, Charsets.US_ASCII) == "ftyp") { "视频地址没有返回有效 MP4 文件" }
                    source.fileName
                }
                val target = File(directory, name)
                if (target.exists()) check(target.delete()) { "无法替换未完成的临时文件" }
                check(part.renameTo(target)) { "无法保存下载的文件" }
                return target
            } catch (error: Exception) {
                job?.check()
                if (error is InterruptedException || error is DownloadCanceledException || Thread.currentThread().isInterrupted) throw DownloadCanceledException()
                // Keep transport partials for Range/aria2 continuation; invalid media is never resumed.
                if (error !is IOException || error.message?.contains("不是有效图片") == true || error.message?.contains("没有返回有效 MP4") == true) part.delete()
                lastError = error
            }
        }
        throw IOException("${source.fileName} 下载失败：${lastError?.message ?: "没有可用的下载地址"}", lastError)
    }

    private fun validPrefix(file: File, image: Boolean): Boolean = runCatching {
        val bytes = file.inputStream().use { input -> ByteArray(64).let { buffer -> buffer.copyOf(input.read(buffer).coerceAtLeast(0)) } }
        if (image) imageExtension(bytes) != null else bytes.size >= 12 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp"
    }.getOrDefault(false)

    private fun preservePrepared(source: File, target: File, job: DownloadJob?) {
        if (target.exists()) check(target.delete()) { "无法替换实况动态素材" }
        if (!source.renameTo(target)) source.inputStream().use { input -> target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                job?.check(); if (Thread.currentThread().isInterrupted) throw DownloadCanceledException()
                val count = input.read(buffer); if (count < 0) break
                output.write(buffer, 0, count)
            }
        } }
        job?.check()
    }

    private fun transferWithAria2(context: Context, url: String, target: File, headers: Map<String, String>, threads: Int,
                                  job: DownloadJob?, onProgress: (EngineProgress) -> Unit) {
        requireMediaUrl(url)
        val root = File(context.noBackupFilesDir, "youtubedl-android/packages")
        val command = mutableListOf(
            File(context.applicationInfo.nativeLibraryDir, "libaria2c.so").absolutePath,
            "--dir=${target.parentFile!!.absolutePath}", "--out=${target.name}",
            "--max-connection-per-server=$threads", "--split=$threads", "--min-split-size=1M",
            "--allow-overwrite=true", "--auto-file-renaming=false", "--file-allocation=none",
            "--connect-timeout=8", "--timeout=12", "--max-tries=3", "--retry-wait=1",
            "--continue=true", "--auto-save-interval=1", "--human-readable=false", "--show-console-readout=true",
            "--console-log-level=error", "--summary-interval=1", "--download-result=hide", "--enable-color=false",
            "--ca-certificate=${File(root, "python/usr/etc/tls/cert.pem").absolutePath}",
            "--user-agent=$USER_AGENT",
        )
        headers.forEach { (key, value) -> command += "--header=$key: $value" }
        systemProxyFor(url)?.let { command += "--all-proxy=$it" }
        command += url
        val process = ProcessBuilder(command).redirectErrorStream(true).apply {
            environment()["LD_LIBRARY_PATH"] = listOf("python", "ffmpeg", "aria2c").joinToString(":") {
                File(root, "$it/usr/lib").absolutePath
            }
        }.start()
        job?.add(process)
        var completed = false
        try {
            val output = StringBuilder()
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                job?.check()
                DownloadProgressParser.aria2(line)?.let(onProgress)
                output.append(line).append('\n')
                if (output.length > 4_000) output.delete(0, output.length - 4_000)
            } }
            if (process.waitFor() != 0 || !target.isFile || target.length() == 0L) {
                throw IOException("多线程下载失败：${output.takeLast(600)}")
            }
            job?.check()
            completed = true
            onProgress(EngineProgress(TaskStage.DOWNLOADING, 1f, target.length(), target.length()))
        } catch (error: Exception) {
            process.destroy()
            throw error
        } finally {
            job?.remove(process)
            if (completed) File(target.path + ".aria2").delete()
        }
    }

    private fun requireMediaUrl(url: String) {
        val parsed = URI(url)
        require(parsed.scheme in setOf("https", "http") && !parsed.host.isNullOrEmpty() && parsed.rawUserInfo == null) { "无效的媒体地址" }
    }

    private fun connection(url: String, headers: Map<String, String>): HttpURLConnection {
        requireMediaUrl(url)
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Encoding", "identity")
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
    }

    private fun readText(url: String, headers: Map<String, String> = emptyMap(), job: DownloadJob? = null): String {
        val connection = connection(url, headers)
        job?.add(connection)
        try {
            check(connection.responseCode in 200..299) { "服务器返回 HTTP ${connection.responseCode}" }
            return connection.inputStream.bufferedReader().use { input ->
                val text = StringBuilder()
                val buffer = CharArray(8192)
                val started = System.nanoTime()
                while (true) {
                    job?.check()
                    if ((System.nanoTime() - started) / 1_000_000 > 20_000) throw IOException("解析接口请求超时")
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(text.length + count <= 5_000_000) { "解析接口返回的数据过大" }
                    text.append(buffer, 0, count)
                }
                text.toString()
            }
        } finally {
            job?.remove(connection)
            connection.disconnect()
        }
    }

    private fun transfer(url: String, target: File, headers: Map<String, String>, job: DownloadJob?, onProgress: (EngineProgress) -> Unit) {
        var resume = true
        while (true) {
            job?.check()
            val offset = if (resume && target.isFile) target.length() else 0L
            val connection = connection(url, headers).apply { if (offset > 0) setRequestProperty("Range", "bytes=$offset-") }
            job?.add(connection)
            try {
                val code = connection.responseCode
                if (code == 416 && offset > 0) {
                    val total = Regex("bytes\\s+\\*/(\\d+)", RegexOption.IGNORE_CASE).matchEntire(connection.getHeaderField("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()
                    if (total == offset) { onProgress(EngineProgress(TaskStage.DOWNLOADING, 1f, offset, total)); return }
                    resume = false; continue
                }
                check(code in 200..299) { "服务器返回 HTTP $code" }
                val resumedTotal = if (code == 206) DownloadProgressParser.resumedTotal(connection.getHeaderField("Content-Range"), offset, connection.contentLengthLong) else null
                if (code == 206 && resumedTotal == null) {
                    if (offset > 0 && resume) { resume = false; continue }
                    throw IOException("服务器返回的续传范围无效")
                }
                val start = if (code == 206) offset else 0L
                val total = resumedTotal ?: connection.contentLengthLong.takeIf { it >= 0 }
                var copied = start
                val started = System.nanoTime()
                var lastAt = 0L
                onProgress(EngineProgress(TaskStage.DOWNLOADING, total?.takeIf { it > 0 }?.let { (copied.toDouble() / it).toFloat() }, copied, total))
                connection.inputStream.buffered().use { input ->
                java.io.FileOutputStream(target, start > 0).buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        job?.check()
                        if (Thread.currentThread().isInterrupted) throw DownloadCanceledException()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        val now = System.nanoTime()
                        if (now - lastAt >= 200_000_000L) {
                            lastAt = now
                            val speed = ((copied - start) * 1_000_000_000.0 / (now - started).coerceAtLeast(1)).toLong().coerceAtLeast(0)
                            onProgress(EngineProgress(TaskStage.DOWNLOADING, total?.takeIf { it > 0 }?.let { (copied.toDouble() / it).toFloat().coerceIn(0f, 1f) },
                                copied, total, speed, if (total != null && speed > 0) (total - copied).coerceAtLeast(0) / speed else null))
                        }
                    }
                }
                }
                if (copied <= 0 || total != null && copied != total) throw IOException("下载的文件不完整，请重试")
                onProgress(EngineProgress(TaskStage.DOWNLOADING, 1f, copied, copied))
                return
            } finally {
                job?.remove(connection)
                connection.disconnect()
            }
        }
    }

    internal fun imageExtension(bytes: ByteArray): String? {
        fun text(start: Int, length: Int) = if (bytes.size >= start + length) String(bytes, start, length, Charsets.US_ASCII) else ""
        return when {
            bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "jpg"
            bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> "png"
            text(0, 6) in setOf("GIF87a", "GIF89a") -> "gif"
            text(0, 4) == "RIFF" && text(8, 4) == "WEBP" -> "webp"
            text(4, 4) == "ftyp" && (8 until minOf(bytes.size - 3, 40) step 4).any { text(it, 4) in setOf("avif", "avis") } -> "avif"
            else -> null
        }
    }

    private val twitterHosts = setOf("x.com", "www.x.com", "mobile.x.com", "m.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com", "m.twitter.com")
    private val tweetPath = Regex("/(?:i/web/status|[^/]+/status|statuses)/(\\d+)(?:/(photo|video)/(\\d+))?/?")

    private fun twitterSources(url: String, quality: String, job: DownloadJob? = null): List<MediaSource> {
        val uri = URI(url)
        val host = uri.host?.lowercase(Locale.ROOT)
        require(uri.scheme in setOf("https", "http") && uri.rawUserInfo == null && uri.port in setOf(-1, 80, 443)) { "推特链接无效" }
        if (host == "pbs.twimg.com") return listOf(MediaSource(listOf(originalImage(url)), "twitter_image.image"))
        if (host == "video.twimg.com") {
            require(uri.path.endsWith(".mp4", true)) { "视频直链需以 .mp4 结尾，请改用推文链接" }
            return listOf(MediaSource(listOf(url), "twitter_video.mp4"))
        }
        require(host in twitterHosts) { "请使用 x.com 或 twitter.com 推文链接" }
        val match = tweetPath.matchEntire(uri.path) ?: throw IllegalArgumentException("请复制单条推文的完整链接")
        val id = match.groupValues[1]
        val json = JSONObject(readText(
            "https://cdn.syndication.twimg.com/tweet-result?id=$id&lang=en&token=${syndicationToken(id)}",
            mapOf("User-Agent" to "Googlebot", "Referer" to "https://platform.twitter.com/"),
            job,
        ))
        return parseTwitterMedia(json, url, quality)
    }

    internal fun parseTwitterMedia(json: JSONObject, url: String, quality: String): List<MediaSource> {
        val match = tweetPath.matchEntire(URI(url).path) ?: throw IllegalArgumentException("推文链接格式无效")
        val id = match.groupValues[1]
        val kind = match.groupValues[2]
        val selectedText = match.groupValues[3]
        val selected = if (selectedText.isEmpty()) null else {
            selectedText.toIntOrNull() ?: throw IllegalArgumentException("附件序号无效")
        }
        require(selected == null || selected > 0) { "附件序号需要从 1 开始" }
        // mediaDetails belongs to this tweet; quoted_tweet/extended_entities can include someone else's files.
        val media = json.optJSONArray("mediaDetails") ?: throw IOException("推文没有公开可下载的图片或视频，可能需要登录")
        val result = mutableListOf<MediaSource>()
        for (index in 0 until media.length()) {
            if (selected != null && selected != index + 1) continue
            val item = media.getJSONObject(index)
            val type = item.optString("type")
            if (kind.isNotEmpty() && type != "animated_gif") {
                require((kind == "photo") == (type == "photo")) { "指定附件类型与链接不符，请复制推文链接重试" }
            }
            val stem = "twitter_${id}_${index + 1}"
            when (type) {
                "photo" -> result += MediaSource(listOf(originalImage(item.optString("media_url_https").ifEmpty { item.optString("media_url") })), "$stem.image")
                "video", "animated_gif" -> {
                    val array = item.optJSONObject("video_info")?.optJSONArray("variants") ?: throw IOException("附件 ${index + 1} 没有可下载的视频")
                    val variants = (0 until array.length()).map { array.getJSONObject(it) }.filter {
                        val link = it.optString("url")
                        link.isNotEmpty() && (it.optString("content_type") == "video/mp4" || runCatching { URI(link).path.endsWith(".mp4", true) }.getOrDefault(false))
                    }
                    val limit = quality.toIntOrNull()
                    val allowed = if (limit == null) variants else variants.filter {
                        val dimensions = Regex("/(\\d+)x(\\d+)/").find(URI(it.getString("url")).path)
                        dimensions == null || minOf(dimensions.groupValues[1].toInt(), dimensions.groupValues[2].toInt()) <= limit
                    }.ifEmpty { variants.minByOrNull { it.optLong("bitrate", 0) }?.let(::listOf) ?: emptyList() }
                    val best = allowed.maxByOrNull { it.optLong("bitrate", 0) } ?: throw IOException("附件 ${index + 1} 没有可下载的 MP4 视频")
                    result += MediaSource(listOf(best.getString("url")), "$stem.mp4", isGif = type == "animated_gif")
                }
            }
        }
        check(result.isNotEmpty()) { "推文没有可下载的附件，或指定的附件序号不存在" }
        return result
    }

    internal fun originalImage(url: String): String {
        val uri = URI(url)
        require(uri.host.equals("pbs.twimg.com", true) && uri.rawUserInfo == null && uri.port in setOf(-1, 80, 443) &&
            Regex("/media/[\\w-]+(?:\\.[a-zA-Z0-9]+)?(?::[\\w]+)?").matches(uri.path)) { "这不是有效的推特图片地址" }
        val query = uri.rawQuery?.split('&')?.filter { it.substringBefore('=') != "name" }.orEmpty() + "name=orig"
        return URI("https", null, "pbs.twimg.com", -1, uri.path.substringBefore(':'), query.joinToString("&"), null).toASCIIString()
    }

    internal fun syndicationToken(id: String): String {
        val value = id.toDouble() / 1e15 * Math.PI
        require(value.isFinite() && value > 0) { "推文编号无效" }
        var integer = value.toLong()
        var fraction = value - integer
        var delta = Math.ulp(value) / 2
        val digits = mutableListOf<Int>()
        while (fraction >= delta) {
            delta *= 36
            fraction *= 36
            val digit = fraction.toInt()
            digits += digit
            fraction -= digit
            if ((fraction > 0.5 || (fraction == 0.5 && digit % 2 == 1)) && fraction + delta > 1) {
                var index = digits.lastIndex
                while (index >= 0 && digits[index] == 35) { digits.removeAt(index); index-- }
                if (index >= 0) digits[index]++ else integer++
                break
            }
        }
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
        return (integer.toString(36) + digits.joinToString("") { alphabet[it].toString() }).replace("0", "")
    }
}
