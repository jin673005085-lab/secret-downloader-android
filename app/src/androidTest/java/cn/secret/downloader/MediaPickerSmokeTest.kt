package cn.secret.downloader

import android.app.AlertDialog
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.KeyEvent
import android.widget.CheckBox
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID

internal object MediaPickerSmokeTest {
    fun run(instrumentation: Instrumentation): String {
        val fixture = P6TaskSmokeSupport(instrumentation)
        val marker = "picker-smoke-${UUID.randomUUID()}"
        val folder = File(fixture.context.cacheDir, marker).apply { check(mkdir()) }
        val source = fixture.main { fixture.activity }
        val selected = mutableListOf<List<String>>()
        val photo = ByteArrayOutputStream().use { bytes ->
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.BLUE)
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
                bytes.toByteArray()
            } finally { bitmap.recycle() }
        }
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 8_000
                val base = "http://127.0.0.1:${server.localPort}"
                val items = listOf(MediaSource(listOf("$base/skip"), "${marker}_01.mp4"),
                    MediaSource(listOf("$base/keep"), "${marker}_02.image"))
                val requests = java.util.concurrent.LinkedBlockingQueue<String>()
                val response = java.util.concurrent.FutureTask {
                    repeat(4) {
                        server.accept().use { client ->
                            client.soTimeout = 5_000
                            val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                            requests.add(reader.readLine())
                            while (!reader.readLine().isNullOrEmpty()) Unit
                            client.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${photo.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                write(photo)
                                flush()
                            }
                        }
                    }
                }
                Thread(response, marker).start()
                fun show(): AlertDialog {
                    val dialog = fixture.main { source.showMediaPicker(items, selected::add) }
                    fixture.await("image thumbnail loaded") {
                        val thumb = dialog.window?.decorView?.findViewWithTag<ImageView>("media_thumb_1")
                        val pixels = (thumb?.drawable as? BitmapDrawable)?.bitmap
                        pixels != null && pixels.getPixel(0, 0) == Color.BLUE
                    }
                    return dialog
                }
                val canceled = show()
                fixture.main { check(canceled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()) }
                fixture.await("picker canceled without selecting") { !canceled.isShowing && selected.isEmpty() }
                check(folder.listFiles()?.isEmpty() == true)
                val dialog = show()
                fixture.main {
                    val content = checkNotNull(dialog.window).decorView
                    check(content.findViewWithTag<ImageView>("media_thumb_1").performClick())
                }
                repeat(3) {
                    check(requests.poll(5, java.util.concurrent.TimeUnit.SECONDS)?.startsWith("GET /keep ") == true)
                }
                val deadline = android.os.SystemClock.uptimeMillis() + 5_000
                var enlargedVisible = false
                do {
                    val screen = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try {
                        enlargedVisible = (screen.height / 3 until screen.height * 2 / 3 step 8).count { y ->
                            screen.getPixel(screen.width / 2, y) == Color.BLUE
                        } >= 15
                    } finally { screen.recycle() }
                    if (!enlargedVisible) android.os.SystemClock.sleep(100)
                } while (!enlargedVisible && android.os.SystemClock.uptimeMillis() < deadline)
                check(enlargedVisible) { "The enlarged image was not rendered on screen" }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                fixture.main {
                    check(dialog.isShowing)
                    val content = checkNotNull(dialog.window).decorView
                    val first = content.findViewWithTag<CheckBox>("media_check_0")
                    val second = content.findViewWithTag<CheckBox>("media_check_1")
                    check(first.isChecked && second.isChecked)
                    first.performClick()
                    second.performClick()
                    check(!dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
                    second.performClick()
                    check(dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick())
                }
                fixture.await("only the checked item returned") { selected.singleOrNull() == listOf(items[1].selectionKey) }
                val files = DownloadEngine.downloadSources(selectMedia(items, selected.single()), folder, 1, {})
                response.get(10, java.util.concurrent.TimeUnit.SECONDS)
                check(requests.single().startsWith("GET /keep "))
                val downloaded = files.single()
                check(downloaded.extension == "png" && downloaded.readBytes().contentEquals(photo))
                check(folder.listFiles()?.toList() == files)
                val saved = MediaPublisher.publishResult(fixture.context, downloaded)
                try {
                    val actual = fixture.context.contentResolver.openInputStream(saved.uri)?.use { it.readBytes() }
                    check(actual != null && actual.contentEquals(photo))
                } finally {
                    if (saved.uri.scheme == "file") File(checkNotNull(saved.uri.path)).delete()
                    else fixture.context.contentResolver.delete(saved.uri, null, null)
                }
            }
            val gif = fixture.context.resources.openRawResource(R.raw.demo_animation).use { it.readBytes() }
            val mp4 = instrumentation.context.assets.open("h264-aac-9s.mp4").use { it.readBytes() }
            for ((name, mime, bytes) in listOf(Triple("animation.gif", "image/gif", gif), Triple("animation.mp4", "video/mp4", mp4))) {
                ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                    server.soTimeout = 8_000
                    val response = java.util.concurrent.FutureTask {
                        server.accept().use { client ->
                            client.soTimeout = 5_000
                            val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                            while (!reader.readLine().isNullOrEmpty()) Unit
                            client.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                write(bytes)
                            }
                        }
                    }
                    Thread(response, "$marker-gif").start()
                    val dialog = fixture.main { source.showMediaPicker(listOf(MediaSource(
                        listOf("http://127.0.0.1:${server.localPort}/$name"), name, isGif = true)), selected::add) }
                    fixture.await("$name first frame and GIF badge") {
                        val root = checkNotNull(dialog.window).decorView
                        root.findViewWithTag<ImageView>("media_thumb_0").scaleType == ImageView.ScaleType.CENTER_CROP &&
                            root.findViewWithTag<android.widget.TextView>("media_gif_badge_0").text == "GIF"
                    }
                    response.get(10, java.util.concurrent.TimeUnit.SECONDS)
                    fixture.main { dialog.dismiss() }
                }
            }
            return "Media picker: thumbnail and enlarged image rendered, cancel and empty selection, selected-only transfer and MediaStore readback, GIF and MP4-backed first frames and badges"
        } finally {
            folder.deleteRecursively()
            fixture.finish()
        }
    }
}
