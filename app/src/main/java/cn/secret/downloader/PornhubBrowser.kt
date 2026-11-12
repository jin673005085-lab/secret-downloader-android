package cn.secret.downloader

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
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
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object PornhubBrowser {
    @SuppressLint("SetJavaScriptEnabled")
    fun resolve(context: Context, url: String, onStatus: (String) -> Unit): JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper()) { "浏览器解析需要在后台执行" }
        val parsed = InputParser.parse(url)
        require(parsed.platform == Platform.PORNHUB) { "浏览器解析仅支持 Pornhub 链接" }
        val uri = URI(parsed.url)
        require(uri.port in setOf(-1, 80, 443)) { "链接端口无效" }
        val host = if (uri.host.endsWith(".org", true)) "www.pornhub.org" else "www.pornhub.com"
        val pageUrl = URI("https://$host${uri.rawPath}${uri.rawQuery?.let { "?$it" }.orEmpty()}").toASCIIString()
        val script = context.assets.open("pornhub-resolver.js").bufferedReader().use { it.readText() }
        val handler = Handler(Looper.getMainLooper())
        val done = CountDownLatch(1)
        val result = AtomicReference<JSONObject>()
        val failure = AtomicReference<Exception>()
        var browser: WebView? = null
        var httpStatus = 0
        var apiError = ""
        var closed = false
        onStatus("正在使用系统浏览器引擎解析…")
        val poll = object : Runnable {
            override fun run() {
                val view = browser ?: return
                if (closed || done.count == 0L) return
                view.evaluateJavascript(script) { encoded ->
                    if (closed || done.count == 0L) return@evaluateJavascript
                    try {
                        require(encoded.length <= 2_000_000) { "浏览器解析结果过大" }
                        val decoded = JSONTokener(encoded).nextValue() as? String
                        if (decoded != null) {
                            val state = JSONObject(decoded)
                            apiError = state.optString("error")
                            val definitions = state.optJSONArray("definitions")
                            if (definitions != null && definitions.length() > 0) {
                                result.set(PornhubMedia.info(pageUrl, definitions, view.settings.userAgentString) {
                                    CookieManager.getInstance().getCookie(it)
                                })
                                Log.i("PornhubBrowser", "Resolved ${definitions.length()} media formats")
                                done.countDown()
                                return@evaluateJavascript
                            }
                            if (!state.optBoolean("pending", false) && apiError.isNotBlank()) {
                                failure.set(IOException(apiError.take(300)))
                                done.countDown()
                                return@evaluateJavascript
                            }
                        }
                    } catch (error: Exception) {
                        failure.set(IOException("无法读取浏览器中的媒体信息", error))
                        done.countDown()
                        return@evaluateJavascript
                    }
                    handler.postDelayed(this, 750)
                }
            }
        }
        handler.post {
            if (closed) return@post
            try {
                WebView.setWebContentsDebuggingEnabled(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
                browser = WebView(context.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.blockNetworkImage = true
                    settings.mediaPlaybackRequiresUserGesture = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val next = request.url
                            val nextHost = next.host.orEmpty().lowercase()
                            return next.scheme != "https" || next.userInfo != null || next.port !in setOf(-1, 443) ||
                                !(nextHost == "pornhub.com" || nextHost.endsWith(".pornhub.com") ||
                                    nextHost == "pornhub.org" || nextHost.endsWith(".pornhub.org"))
                        }

                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            // Resolve metadata without autoplaying or fetching media in the browser.
                            val path = request.url.path.orEmpty()
                            if (Regex("\\.(?:mp4|ts|m4s|webm)$", RegexOption.IGNORE_CASE).containsMatchIn(path)) {
                                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
                            }
                            return null
                        }

                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                            if (request.isForMainFrame) {
                                httpStatus = response.statusCode
                                Log.i("PornhubBrowser", "Main page HTTP $httpStatus")
                            }
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (!request.isForMainFrame || closed) return
                            failure.set(IOException("浏览器无法连接站点：${error.description}"))
                            done.countDown()
                        }
                    }
                    loadUrl(pageUrl)
                }
                handler.postDelayed(poll, 750)
            } catch (error: Exception) {
                failure.set(IOException("系统 WebView 不可用，请更新 Android System WebView", error))
                done.countDown()
            }
        }
        try {
            if (!done.await(60, TimeUnit.SECONDS)) {
                val detail = when {
                    httpStatus >= 400 -> "站点返回 HTTP $httpStatus"
                    apiError.isNotBlank() -> apiError.take(200)
                    else -> "网页未提供可下载的媒体信息，可能需要在浏览器中完成站点验证"
                }
                throw IOException("浏览器解析超时：$detail")
            }
            failure.get()?.let { throw it }
            return result.get() ?: throw IOException("浏览器没有返回媒体信息")
        } finally {
            handler.post {
                closed = true
                handler.removeCallbacks(poll)
                browser?.apply { stopLoading(); destroy() }
                browser = null
            }
        }
    }
}
