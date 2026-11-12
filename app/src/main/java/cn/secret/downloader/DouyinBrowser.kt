package cn.secret.downloader

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Reads the official full desktop page, which preserves animation fields omitted by mobile SSR. */
internal object DouyinBrowser {
    @SuppressLint("SetJavaScriptEnabled")
    fun resolve(context: Context, id: String, checkCanceled: () -> Unit): JSONObject {
        require(id.matches(Regex("\\d+")))
        check(Looper.myLooper() != Looper.getMainLooper()) { "完整图集解析需要在后台执行" }
        val handler = Handler(Looper.getMainLooper())
        val closed = AtomicBoolean()
        val failure = AtomicReference<Exception>()
        val scripts = ArrayBlockingQueue<String>(1)
        var browser: WebView? = null
        val script = """(() => Array.from(document.scripts, s => s.textContent || '').filter(s => s.includes('self.__pace_f.push')).join('\n'))()"""
        val poll = object : Runnable {
            override fun run() {
                val view = browser ?: return
                if (closed.get()) return
                view.evaluateJavascript(script) { encoded ->
                    if (closed.get()) return@evaluateJavascript
                    if (encoded.length <= 8 * 1024 * 1024) {
                        val text = runCatching { JSONTokener(encoded).nextValue() as? String }.getOrNull()
                        if (!text.isNullOrEmpty()) { scripts.poll(); scripts.offer(text) }
                    } else failure.set(IOException("完整图集信息过大"))
                    handler.postDelayed(this, 750)
                }
            }
        }
        handler.post {
            if (closed.get()) return@post
            try {
                browser = WebView(context.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = DouyinResolver.DESKTOP_USER_AGENT
                    settings.allowFileAccess = false; settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.blockNetworkImage = true
                    settings.mediaPlaybackRequiresUserGesture = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val uri = request.url
                            return uri.scheme != "https" || uri.userInfo != null || uri.port !in setOf(-1, 443) ||
                                !DouyinResolver.allowedUrl(uri.toString())
                        }
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val host = request.url.host.orEmpty().lowercase()
                            val path = request.url.path.orEmpty()
                            if (host == "douyinvod.com" || host.endsWith(".douyinvod.com") ||
                                Regex("\\.(?:mp4|m3u8|ts|m4s|webm)$", RegexOption.IGNORE_CASE).containsMatchIn(path))
                                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
                            return null
                        }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame && !closed.get()) failure.set(IOException("无法读取完整图集：${error.description}"))
                        }
                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                            if (request.isForMainFrame && !closed.get()) failure.set(IOException("完整图集返回 HTTP ${response.statusCode}"))
                        }
                    }
                    loadUrl("https://www.douyin.com/note/$id")
                }
                handler.postDelayed(poll, 500)
            } catch (error: Exception) { failure.set(IOException("系统 WebView 不可用，无法检查动态图", error)) }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            while (System.nanoTime() < deadline) {
                checkCanceled()
                failure.get()?.let { throw it }
                scripts.poll(750, TimeUnit.MILLISECONDS)?.let { text ->
                    DouyinResolver.parseDesktopPage(text, id)?.let { return it }
                }
            }
            throw IOException("完整图集解析超时，请检查网络或稍后重试；未将动态图降级保存为静态封面")
        } finally {
            closed.set(true)
            handler.post {
                handler.removeCallbacks(poll)
                browser?.apply { stopLoading(); destroy() }; browser = null
            }
        }
    }
}
