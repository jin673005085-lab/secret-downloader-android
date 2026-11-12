package cn.secret.downloader

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.os.Bundle
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs

/** Run with: adb shell am instrument -w cn.secret.downloader.test/cn.secret.downloader.MotionPhotoSmokeInstrumentation */
class MotionPhotoSmokeInstrumentation : Instrumentation() {
    private var publish = false
    private var navigation = false
    private var tasks = false
    private var library = false
    private var player = false
    private var works = false
    private var integration = false
    private var serviceCommands = false
    private var releaseUi = false
    private var remoteDownload = false
    private var recovery: String? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        publish = arguments?.getString("publish") == "true"
        navigation = arguments?.getString("navigation") == "true"
        tasks = arguments?.getString("tasks") == "true"
        library = arguments?.getString("library") == "true"
        player = arguments?.getString("player") == "true"
        works = arguments?.getString("works") == "true"
        integration = arguments?.getString("integration") == "true"
        serviceCommands = arguments?.getString("service_commands") == "true"
        releaseUi = arguments?.getString("release_ui") == "true"
        remoteDownload = arguments?.getString("remote_download") == "true"
        recovery = arguments?.getString("recovery")?.takeIf { it == "prepare" || it == "verify" }
        start()
    }

    override fun onStart() {
        if (navigation || tasks || library || player || works || integration || serviceCommands || releaseUi || remoteDownload || recovery != null) {
            val report = Bundle()
            try {
                val result = when {
                    releaseUi -> ReleaseUiSmokeTest.run(this)
                    recovery == "prepare" -> ProcessRecoverySmokeTest.prepare(this)
                    recovery == "verify" -> ProcessRecoverySmokeTest.verify(this)
                    serviceCommands -> ServiceCommandsSmokeTest.run(this)
                    remoteDownload -> RemoteDownloadSmokeTest.run(this)
                    integration -> RealConversionSmokeTest.run(this)
                    works -> ConversionLibrarySmokeTest.run(this)
                    player -> LocalPlayerSmokeTest.run(this)
                    library -> VideoLibrarySmokeTest.run(this)
                    tasks -> TaskSystemSmokeTest.run(this)
                    else -> NavigationSmokeTest.run(this)
                }
                report.putString("stream", "PASS: $result\n")
                finish(Activity.RESULT_OK, report)
            } catch (error: Throwable) {
                report.putString("stream", "FAIL: ${android.util.Log.getStackTraceString(error)}\n")
                finish(Activity.RESULT_CANCELED, report)
            }
            return
        }
        val result = Bundle()
        val folder = File(targetContext.cacheDir, "motion-photo-smoke-${System.nanoTime()}")
        var resultCode = Activity.RESULT_OK
        try {
            check(folder.mkdirs())
            val report = mutableListOf<String>()
            val cover = File(folder, "cover.jpg")
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(0xff336699.toInt())
                cover.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            } finally {
                bitmap.recycle()
            }
            val coverBytes = cover.readBytes()
            val coverSha256 = MessageDigest.getInstance("SHA-256").digest(coverBytes)
            val sampleStamp = System.currentTimeMillis()
            var h264: File? = null
            var h264Photo: File? = null
            for (asset in listOf("h264-aac-9s.mp4", "vp9-opus-9s.webm")) {
                status("Converting $asset")
                val source = File(folder, asset)
                context.assets.open(asset).use { input -> source.outputStream().use { input.copyTo(it) } }
                val sourceBytes = source.readBytes()
                val original = tracks(source)
                check(original.single { it.mime.startsWith("video/") }.lastSampleUs >= 8_000_000)
                check(original.single { it.mime.startsWith("audio/") }.lastSampleUs >= 8_000_000)
                val photo = File(folder, if (h264 == null) "兼容测试_完整9秒_${sampleStamp}_MP.jpg" else "vp9_converted_MP.jpg")
                val timestampUs = if (h264 == null) 3_000_000L else -1L
                MotionPhotoConverter.convert(targetContext, source, photo,
                    cover = if (h264 == null) null else cover,
                    frameTimestampUs = if (h264 == null) timestampUs else 0)
                val header = photo.readBytes().let { String(it, 0, minOf(it.size, 65_536), Charsets.ISO_8859_1) }
                check(header.contains("GCamera:MotionPhotoPresentationTimestampUs=\"$timestampUs\""))
                check(header.contains("OpCamera:MotionPhotoPrimaryPresentationTimestampUs=\"${timestampUs.coerceAtLeast(0)}\""))
                check(sourceBytes.contentEquals(source.readBytes())) { "$asset source was modified" }
                val embedded = unpack(photo, folder)
                if (h264 != null) {
                    val (width, height) = videoDisplaySize(embedded)
                    val decoded = checkNotNull(BitmapFactory.decodeFile(photo.absolutePath))
                    try { assertLetterboxedCover(decoded, width, height) } finally { decoded.recycle() }
                    val originalCover = checkNotNull(BitmapFactory.decodeFile(cover.absolutePath))
                    val preview = try { MotionPhotoCover.preview(originalCover, width, height) } finally { originalCover.recycle() }
                    try { assertLetterboxedCover(preview, width, height) } finally { preview.recycle() }
                    check(coverSha256.contentEquals(MessageDigest.getInstance("SHA-256").digest(cover.readBytes()))) { "Cover source SHA-256 changed" }
                    report += "Custom square cover: video display aspect, black padding, center color, matching Creator preview and source SHA-256: PASS"
                }
                val converted = tracks(embedded)
                check(converted.single { it.mime.startsWith("video/") }.mime == "video/avc")
                check(converted.single { it.mime.startsWith("audio/") }.mime == "audio/mp4a-latm")
                for (type in listOf("video/", "audio/")) {
                    val before = original.single { it.mime.startsWith(type) }
                    val after = converted.single { it.mime.startsWith(type) }
                    check(after.samples > 0 && abs(after.lastSampleUs - before.lastSampleUs) <= 150_000) {
                        "$asset $type duration changed: ${before.lastSampleUs} -> ${after.lastSampleUs} us"
                    }
                }
                report += "$asset: full video/audio retained (${converted.joinToString { "${it.mime} last=${it.lastSampleUs}us" }})"
                if (h264 == null) {
                    h264 = source; h264Photo = photo
                }
            }

            status("Checking writer byte preservation and invalid input")
            val video = checkNotNull(h264)
            val videoBytes = video.readBytes()
            status("Checking last-frame selection and invalid custom covers")
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(video.absolutePath)
                val preview = checkNotNull(retriever.getFrameAtTime(8_999_000, MediaMetadataRetriever.OPTION_CLOSEST)) {
                    "Last-frame preview could not be decoded"
                }
                preview.recycle()
            } finally {
                retriever.release()
            }
            val lastFramePhoto = File(folder, "last_frame_MP.jpg")
            MotionPhotoConverter.convert(targetContext, video, lastFramePhoto, frameTimestampUs = 8_999_000)
            val lastFrameBytes = lastFramePhoto.readBytes()
            val lastFrameHeader = String(lastFrameBytes, 0, minOf(lastFrameBytes.size, 65_536), Charsets.ISO_8859_1)
            check(lastFrameHeader.contains("GCamera:MotionPhotoPresentationTimestampUs=\"8999000\""))
            check(lastFrameHeader.contains("OpCamera:MotionPhotoPrimaryPresentationTimestampUs=\"8999000\""))
            check(tracks(unpack(lastFramePhoto, folder)).all { it.lastSampleUs >= 8_000_000 })
            val badCover = File(folder, "invalid-cover.jpg").apply { writeText("<html>not an image</html>") }
            for (rejectedCover in listOf(badCover, File(folder, "missing-cover.jpg"))) {
                val rejectedOutput = File(folder, "${rejectedCover.nameWithoutExtension}_MP.jpg")
                check(runCatching {
                    MotionPhotoConverter.convert(targetContext, video, rejectedOutput, cover = rejectedCover)
                }.isFailure) { "Invalid custom cover was silently replaced: ${rejectedCover.name}" }
                check(!rejectedOutput.exists()) { "Invalid custom cover produced an output" }
            }
            report += "8999000us final-frame selection and invalid/missing custom-cover rejection: PASS"
            val direct = File(folder, "writer_MP.jpg")
            MotionPhotoWriter.write(cover, video, direct)
            check(videoBytes.contentEquals(unpack(direct, folder).readBytes())) { "Writer changed MP4 payload" }
            check(coverBytes.contentEquals(cover.readBytes())) { "Writer changed source cover" }
            check(videoBytes.contentEquals(video.readBytes())) { "Writer changed source video" }
            val invalid = File(folder, "not-a-video.txt").apply { writeText("motion photo smoke test") }
            val invalidBytes = invalid.readBytes()
            val invalidOutput = File(folder, "invalid_MP.jpg")
            check(runCatching { MotionPhotoConverter.convert(targetContext, invalid, invalidOutput) }.isFailure) {
                "Converter accepted a non-video file"
            }
            check(!invalidOutput.exists() && invalidBytes.contentEquals(invalid.readBytes()))
            report += "Writer payload/offsets/source preservation and non-video rejection: PASS"
            if (publish) {
                status("Creating the controlled 3-second comparison input; production conversion still retains its whole input")
                val shortVideo = File(folder, "controlled-3s.mp4")
                MotionPhotoConverter.run(targetContext, "libffmpeg.so", listOf("-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                    "-i", video.absolutePath, "-t", "3", "-map", "0:v:0", "-map", "0:a:0?",
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", shortVideo.absolutePath))
                val shortPhoto = File(folder, "兼容测试_短片3秒_${sampleStamp}_MP.jpg")
                MotionPhotoConverter.convert(targetContext, shortVideo, shortPhoto, frameTimestampUs = 1_000_000)
                val shortTracks = tracks(unpack(shortPhoto, folder))
                check(shortTracks.isNotEmpty() && shortTracks.all { it.lastSampleUs in 2_800_000L..3_150_000L }) {
                    "The controlled comparison sample is not about three seconds: $shortTracks"
                }
                check(videoBytes.contentEquals(video.readBytes())) { "Creating comparison input changed the original 9-second source" }
                for (sample in listOf(checkNotNull(h264Photo), shortPhoto)) {
                    val saved = MediaPublisher.publishResult(targetContext, sample)
                    val line = "Published compatibility sample: ${saved.uri} (${saved.path})"
                    status(line); report += line
                }
            }
            result.putString("result", "PASS")
            result.putString("stream", "\nPASS\n${report.joinToString("\n")}\n")
        } catch (error: Throwable) {
            resultCode = Activity.RESULT_CANCELED
            result.putString("result", "FAIL")
            result.putString("stream", "\nFAIL\n${error.stackTraceToString()}\n")
        } finally {
            folder.deleteRecursively()
        }
        finish(resultCode, result)
    }

    private fun status(message: String) = sendStatus(0, Bundle().apply { putString("stream", "$message\n") })

    private fun videoDisplaySize(file: File): Pair<Int, Int> {
        val reader = MediaMetadataRetriever()
        return try {
            reader.setDataSource(file.absolutePath)
            val width = checkNotNull(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull())
            val height = checkNotNull(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull())
            val rotation = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation % 180 == 0) width to height else height to width
        } finally { reader.release() }
    }

    private fun assertLetterboxedCover(bitmap: Bitmap, width: Int, height: Int) {
        check(bitmap.width == width && bitmap.height == height) { "Cover ${bitmap.width}x${bitmap.height} differs from video display ${width}x$height" }
        val center = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        check(abs(Color.red(center) - 0x33) <= 12 && abs(Color.green(center) - 0x66) <= 12 && abs(Color.blue(center) - 0x99) <= 12) {
            "The chosen cover's center color was lost: ${Integer.toHexString(center)}"
        }
        if (width != height) {
            val edge = if (width > height) bitmap.getPixel(0, height / 2) else bitmap.getPixel(width / 2, 0)
            check(Color.red(edge) + Color.green(edge) + Color.blue(edge) < 45) { "Mismatched cover must have black padding" }
        }
    }

    private data class Track(val mime: String, val lastSampleUs: Long, val samples: Int)

    private fun tracks(file: File): List<Track> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val mimes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString("mime").orEmpty() }
            val last = LongArray(mimes.size) { -1L }
            val counts = IntArray(mimes.size)
            mimes.indices.forEach(extractor::selectTrack)
            while (extractor.sampleTrackIndex >= 0) {
                val index = extractor.sampleTrackIndex
                last[index] = maxOf(last[index], extractor.sampleTime)
                counts[index]++
                if (!extractor.advance()) break
            }
            return mimes.indices.map { Track(mimes[it], last[it], counts[it]) }
        } finally {
            extractor.release()
        }
    }

    private fun unpack(photo: File, folder: File): File {
        val bytes = photo.readBytes()
        check(bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) { "Missing JPEG SOI" }
        val header = String(bytes, 0, minOf(bytes.size, 65_536), Charsets.ISO_8859_1)
        val length = Regex("OpCamera:VideoLength=\"(\\d+)\"").find(header)?.groupValues?.get(1)?.toInt()
            ?: error("Missing Motion Photo offset")
        val start = bytes.size - length
        check(length >= 12 && start >= 2 && bytes[start - 2] == 0xff.toByte() && bytes[start - 1] == 0xd9.toByte()) {
            "Motion Photo offset does not point after JPEG EOI"
        }
        check(String(bytes, start + 4, 4, Charsets.US_ASCII) == "ftyp") { "Embedded payload is not MP4" }
        check(header.contains("Oplus_8388608") && header.contains("OpCamera:MotionPhotoOwner=\"oplus\""))
        check(header.contains("OpCamera:VideoLength=\"$length\"") && header.contains("Item:Length=\"$length\""))
        var cursor = 2
        var foundMpf = false
        while (cursor + 4 <= start) {
            check(bytes[cursor] == 0xff.toByte()) { "Invalid JPEG segment" }
            val marker = bytes[cursor + 1].toInt() and 0xff
            if (marker == 0xda || marker == 0xd9) break
            val segmentLength = ((bytes[cursor + 2].toInt() and 0xff) shl 8) or (bytes[cursor + 3].toInt() and 0xff)
            check(segmentLength >= 2 && cursor + 2 + segmentLength <= start)
            if (marker == 0xe2 && String(bytes, cursor + 4, 4, Charsets.US_ASCII) == "MPF\u0000") {
                check(segmentLength == 72)
                check(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt(cursor + 4 + 58) == start) {
                    "MPF primary image length does not match actual JPEG bytes"
                }
                foundMpf = true
            }
            cursor += 2 + segmentLength
        }
        check(foundMpf) { "Missing MPF segment" }
        return File(folder, "${photo.nameWithoutExtension}-payload.mp4").apply {
            outputStream().use { it.write(bytes, start, length) }
        }
    }
}
