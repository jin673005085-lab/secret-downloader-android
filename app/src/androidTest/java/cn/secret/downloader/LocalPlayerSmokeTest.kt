package cn.secret.downloader

import android.app.Instrumentation
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.SystemClock
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.Player
import java.io.File
import kotlin.math.abs

/** Plays only two test-owned cache files. Existing library records and public media are untouched. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object LocalPlayerSmokeTest {
    fun run(instrumentation: Instrumentation): String {
        fun <T> main(action: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(action) }
            return checkNotNull(result).getOrThrow()
        }
        fun await(description: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 10_000
            do {
                instrumentation.waitForIdleSync()
                if (main(condition)) return
                SystemClock.sleep(40)
            } while (SystemClock.uptimeMillis() < deadline)
            error("Timed out: $description")
        }

        val context = instrumentation.targetContext
        val prefix = "PlayerSmoke${System.nanoTime().toString(36)}"
        val directory = File(context.cacheDir, prefix).apply { check(mkdirs()) }
        val media = MediaRepository.get(context)
        val now = System.currentTimeMillis()
        val owned = (0..1).map { index ->
            val sample = File(directory, "$index.mp4")
            instrumentation.context.assets.open("h264-aac-9s.mp4").use { input ->
                sample.outputStream().use { output -> input.copyTo(output) }
            }
            MediaItem(id = "$prefix-$index", title = "$prefix video $index", platform = "播放器测试",
                localUri = Uri.fromFile(sample).toString(), durationMs = 9_000, width = 320, height = 240,
                sizeBytes = sample.length(), downloadedAt = now + index, lastPositionMs = if (index == 0) 2_000 else 0)
        }
        val unplayable = MediaItem(id = "$prefix-no-file", title = "$prefix no local file", durationMs = 9_000,
            downloadedAt = now - 1)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val report = mutableListOf<String>()
        val originalOrientation = main { activity.requestedOrientation }
        val originalConfiguration = main { activity.resources.configuration.orientation }
        var player: LocalVideoPlayerView? = null
        var protected = emptyMap<String, MediaItem>()

        fun tagged(tag: String): View = checkNotNull(find(activity.window.decorView, tag)) { "Missing player UI: $tag" }
        fun click(tag: String) = main { check(tagged(tag).performClick()) { "Not clickable: $tag" } }
        fun engine() = checkNotNull(checkNotNull(player).playbackEngine) { "Player has no active engine" }
        fun tap(x: Float, y: Float) {
            val at = SystemClock.uptimeMillis()
            for ((action, elapsed) in listOf(MotionEvent.ACTION_DOWN to 0L, MotionEvent.ACTION_UP to 60L)) {
                val event = MotionEvent.obtain(at, at + elapsed, action, x, y, 0)
                try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            }
        }
        try {
            await("media metadata ready") { media.items.isNotEmpty() }
            main {
                protected = media.items.associateBy { it.id }
                owned.forEach(media::add)
                media.add(unplayable)
            }
            click("tab_button_2")
            await("video library") { findOrNull(activity.window.decorView, "tab_library")?.isShown == true }
            // The newest controlled samples are visible first; no existing library filters are changed.
            await("controlled video card") { findOrNull(activity.window.decorView, "video_card_${owned[0].id}")?.isShown == true }
            click("video_card_${owned[0].id}")
            await("actual local video starts") {
                player = descendants(activity.window.decorView).filterIsInstance<LocalVideoPlayerView>().firstOrNull()
                player?.isShown == true &&
                    findOrNull(activity.window.decorView, "video_player_cover")?.visibility == View.GONE &&
                    player?.playbackEngine?.let { it.playbackState == Player.STATE_READY && it.isPlaying && it.currentPosition >= 2_100 } == true
            }
            val firstEngine = main { engine() }
            main {
                val minimum = UiTheme(activity).dp(48)
                listOf("player_back", "player_more", "player_toggle", "player_seek", "player_speed", "player_pip", "player_fullscreen").forEach { tag ->
                    val control = tagged(tag)
                    check(control.width >= minimum && control.height >= minimum) { "Touch target is smaller than 48dp: $tag" }
                }
                check(media.get(owned[0].id)?.playCount == 1) { "A single playback start was counted more than once" }
                check(engine().currentMediaItem?.localConfiguration?.uri == Uri.parse(owned[0].localUri))
            }
            report += "Controlled cache video renders and advances from its bookmark; one real playback increments its count once: PASS"

            click("player_toggle")
            await("pause") { !engine().isPlaying }
            val paused = main { engine().currentPosition }
            SystemClock.sleep(180)
            main { check(abs(engine().currentPosition - paused) <= 80) { "Pause left the playback clock running" } }
            val seekPoint = main {
                val seek = tagged("player_seek")
                val rect = Rect(); check(seek.getGlobalVisibleRect(rect))
                rect.left + seek.paddingLeft + (seek.width - seek.paddingLeft - seek.paddingRight) * (4_000f / engine().duration) to rect.exactCenterY()
            }
            tap(seekPoint.first, seekPoint.second)
            await("local seek") { abs(engine().currentPosition - 4_000) < 250 }
            click("player_speed")
            val speedDeadline = SystemClock.uptimeMillis() + 5_000
            var speedPoint: Pair<Float, Float>? = null
            while (speedPoint == null && SystemClock.uptimeMillis() < speedDeadline) {
                val node = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("1.5×")?.firstOrNull()
                if (node != null) {
                    val rect = Rect(); node.getBoundsInScreen(rect)
                    if (!rect.isEmpty) speedPoint = rect.exactCenterX() to rect.exactCenterY()
                }
                if (speedPoint == null) SystemClock.sleep(40)
            }
            checkNotNull(speedPoint) { "Missing 1.5× speed menu option" }.let { tap(it.first, it.second) }
            await("speed menu updates engine") { abs(engine().playbackParameters.speed - 1.5f) < .001f }
            main { check(abs(engine().playbackParameters.speed - 1.5f) < .001f) }
            main { engine().play() }
            await("resume") { engine().isPlaying && engine().currentPosition > 4_150 }
            main { engine().pause() }
            await("paused bookmark persisted") { (media.get(owned[0].id)?.lastPositionMs ?: 0) > 4_000 }
            main { check(media.get(owned[0].id)?.playCount == 1) { "Resume created another play count" } }
            report += "Playback pauses, seeks and resumes at 1.5×; repeated resume keeps one play count and saves the bookmark: PASS"

            click("player_fullscreen")
            await("landscape fullscreen without reloading video") {
                activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                    player?.isFullscreen == true && engine() === firstEngine
            }
            main {
                check(engine() === firstEngine) { "Configuration update recreated the playback engine" }
                check(abs(engine().currentPosition - (media.get(owned[0].id)?.lastPositionMs ?: 0)) < 300)
                check(checkNotNull(player).handleBack()) { "Fullscreen Back did not consume the navigation" }
            }
            await("fullscreen Back returns to the same playback page") {
                !checkNotNull(player).isFullscreen && engine() === firstEngine &&
                    activity.resources.configuration.orientation == originalConfiguration
            }
            main {
                engine().seekTo(8_500)
            }
            await("near-end seek") { engine().currentPosition >= 8_300 }
            main { engine().play() }
            await("near-end actual playback") { engine().isPlaying && engine().currentPosition >= 8_550 }
            main { engine().pause() }
            await("ninety-percent completion bookmark") { media.get(owned[0].id)?.isFinished == true }
            report += "Fullscreen rotation and Back retain the same engine and position; playback beyond 90% persists a finished record: PASS"

            main { checkNotNull(player).setMedia(owned[1]) }
            await("same-page video switch") {
                engine() === firstEngine && engine().playbackState == Player.STATE_READY && engine().isPlaying &&
                    engine().currentMediaItem?.localConfiguration?.uri == Uri.parse(owned[1].localUri) && engine().currentPosition > 100
            }
            main {
                check(checkNotNull(player).currentItem?.id == owned[1].id)
                check(media.get(owned[1].id)?.playCount == 1)
                engine().pause()
            }
            report += "Switching to another controlled video reuses one engine and updates its own playback history: PASS"

            main { checkNotNull(player).setMedia(unplayable) }
            await("missing local URI stays unplayed") {
                player?.playbackEngine?.isPlaying != true &&
                    findOrNull(activity.window.decorView, "player_error")?.isShown == true
            }
            main { check(media.get(unplayable.id)?.playCount == 0) { "A fake video increased its play count without playback" } }
            report += "A video without a local URI shows an error, stops previous playback and never increments its play count: PASS"

            click("player_back")
            await("player exits and releases its engine") {
                player?.playbackEngine == null && findOrNull(activity.window.decorView, "bottom_tabs")?.isShown == true
            }
            main {
                check(!firstEngine.isPlaying) { "The departed playback engine is still playing" }
                check(protected.all { (id, item) -> media.get(id) == item }) { "An existing library record changed" }
                check(media.persistenceError == null) { "Media persistence failed: ${media.persistenceError}" }
            }
            report += "Leaving the page releases its engine, restores the bottom bar and leaves existing records unchanged: PASS"
            return report.joinToString("\n")
        } finally {
            main {
                player?.release()
                activity.requestedOrientation = originalOrientation
                owned.forEach { media.delete(it.id) }
                media.delete(unplayable.id)
                if (!activity.isFinishing) activity.finish()
            }
            directory.deleteRecursively()
        }
    }

    private fun find(root: View, tag: String): View? = descendants(root).firstOrNull { it.tag == tag && it.isShown }
    private fun findOrNull(root: View, tag: String): View? = descendants(root).firstOrNull { it.tag == tag }
    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }
}
