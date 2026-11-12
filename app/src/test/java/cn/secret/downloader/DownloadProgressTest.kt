package cn.secret.downloader

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class DownloadProgressTest {
    @get:Rule val temporary = TemporaryFolder()
    private val movie = "0000ftypisom".toByteArray() + ByteArray(256 * 1024) { (it % 251).toByte() }

    @Test fun structuredProgressKeepsActualBytesAndUnknownTotals() {
        val known = DownloadProgressParser.ytDlp(-1f, "${DownloadProgressParser.PREFIX}1024|4096|5000|2048.8|2|NA|NA")!!
        assertEquals(TaskStage.DOWNLOADING, known.stage)
        assertEquals(.25f, known.progress!!, .0001f)
        assertEquals(1024L, known.bytesDone)
        assertEquals(4096L, known.bytesTotal)
        assertEquals(2048L, known.speedBps)
        assertEquals(2L, known.etaSeconds)
        val fragment = DownloadProgressParser.ytDlp(99f, "${DownloadProgressParser.PREFIX}700|NA|9000|NA|NA|3|12")!!
        assertNull(fragment.bytesTotal)
        assertNull(fragment.speedBps)
        assertEquals(.25f, fragment.progress!!, .0001f)
        val unknown = DownloadProgressParser.ytDlp(99f, "${DownloadProgressParser.PREFIX}700|NA|9000|NA|NA|NA|NA")!!
        assertNull(unknown.progress)
        assertNull(unknown.bytesTotal)
        assertNull(DownloadProgressParser.ytDlp(99f, "[download] Destination: file.mp4"))
        val merger = DownloadProgressParser.ytDlp(100f, "[Merger] Merging formats into file.mp4")!!
        assertEquals(TaskStage.MERGING, merger.stage)
        assertNull(merger.progress)
    }

    @Test fun parsesAriaBytesAndFragmentProgressWithoutInventingAFileSize() {
        val raw = DownloadProgressParser.aria2("[#a1b2c3 1024/4096(25%) CN:4 DL:2048 ETA:1m2s]")!!
        assertEquals(1024L, raw.bytesDone)
        assertEquals(4096L, raw.bytesTotal)
        assertEquals(2048L, raw.speedBps)
        assertEquals(62L, raw.etaSeconds)
        val human = DownloadProgressParser.aria2("[#a1b2c3 1.5MiB/3MiB(50%) CN:4 DL:512KiB ETA:3s]")!!
        assertEquals(1_572_864L, human.bytesDone)
        assertEquals(.5f, human.progress!!, .0001f)
        val hls = DownloadProgressParser.ytDlp(77f, "[download] 4.0% of ~ 9.5MiB at 1MiB/s ETA 00:09 (frag 2/8)")!!
        assertEquals(.25f, hls.progress!!, .0001f)
        assertNull(hls.bytesTotal)
        assertEquals(9L, hls.etaSeconds)
        assertEquals(1_048_576L, hls.speedBps)
    }

    @Test fun validatesEveryContentRangeBoundary() {
        assertEquals(100L, DownloadProgressParser.resumedTotal("bytes 20-99/100", 20, 80))
        assertNull(DownloadProgressParser.resumedTotal("bytes 19-99/100", 20, 81))
        assertNull(DownloadProgressParser.resumedTotal("bytes 20-100/100", 20, 81))
        assertNull(DownloadProgressParser.resumedTotal("bytes 20-99/100", 20, 79))
        assertNull(DownloadProgressParser.resumedTotal("bytes 20-99/*", 20, 80))
    }

    @Test fun resumesRealHttpBytesAndSkipsAnAlreadyFinishedAttachment() {
        val requests = AtomicInteger()
        Stub { headers ->
            requests.incrementAndGet()
            assertEquals("bytes=8-", headers["range"])
            Reply(206, movie.copyOfRange(8, movie.size), mapOf("Content-Range" to "bytes 8-${movie.lastIndex}/${movie.size}"))
        }.use { server ->
            val folder = temporary.newFolder()
            File(folder, "video.mp4.part").writeBytes(movie.copyOfRange(0, 8))
            val updates = mutableListOf<EngineProgress>()
            val source = MediaSource(listOf(server.url), "video.mp4")
            val result = DownloadEngine.downloadSource(source, folder, onProgress = updates::add)
            assertArrayEquals(movie, result.readBytes())
            assertEquals(8L, updates.first().bytesDone)
            assertEquals(movie.size.toLong(), updates.last().bytesDone)
            assertEquals(movie.size.toLong(), updates.last().bytesTotal)
            assertEquals(result, DownloadEngine.downloadSource(source, folder))
            assertEquals(1, requests.get())
            assertFalse(File(folder, "video.mp4.part").exists())
        }
    }

    @Test fun restartsWhenTheServerIgnoresOrRejectsTheRequestedRange() {
        for (invalidPartialResponse in listOf(false, true)) {
            val ranges = Collections.synchronizedList(mutableListOf<String?>())
            Stub { headers ->
                ranges += headers["range"]
                if (invalidPartialResponse && ranges.size == 1)
                    Reply(206, movie, mapOf("Content-Range" to "bytes 0-${movie.lastIndex}/${movie.size}"))
                else Reply(200, movie)
            }.use { server ->
                val folder = temporary.newFolder()
                File(folder, "video.mp4.part").writeBytes(ByteArray(8) { 42 })
                val result = DownloadEngine.downloadSource(MediaSource(listOf(server.url), "video.mp4"), folder)
                assertArrayEquals(movie, result.readBytes())
                assertEquals(if (invalidPartialResponse) listOf("bytes=8-", null) else listOf("bytes=8-"), ranges.toList())
            }
        }
    }

    @Test fun unknownLengthStaysIndeterminateUntilCompletion() {
        Stub { Reply(200, movie, includeLength = false) }.use { server ->
            val updates = mutableListOf<EngineProgress>()
            DownloadEngine.downloadSource(MediaSource(listOf(server.url), "video.mp4"), temporary.newFolder(), onProgress = updates::add)
            assertTrue(updates.dropLast(1).all { it.bytesTotal == null && it.progress == null })
            assertEquals(movie.size.toLong(), updates.last().bytesDone)
        }
    }

    @Test fun cancellationStopsTheTransferAndPreservesItsPartialForResume() {
        Stub { Reply(200, movie) }.use { server ->
            val folder = temporary.newFolder()
            val job = DownloadJob("cancel-test")
            val failure = AtomicReference<Throwable>()
            val worker = thread(name = "cancel-download-test") {
                job.addThread(Thread.currentThread())
                try {
                    DownloadEngine.downloadSource(MediaSource(listOf(server.url), "video.mp4"), folder, job = job) {
                        if (it.bytesDone > 0) job.cancel()
                    }
                } catch (error: Throwable) { failure.set(error) }
                finally { job.removeThread(Thread.currentThread()) }
            }
            worker.join(5_000)
            assertFalse("Download worker must stop after cancellation", worker.isAlive)
            assertTrue(failure.get() is DownloadCanceledException)
            val partial = File(folder, "video.mp4.part")
            assertTrue(partial.length() > 0)
            assertTrue(partial.length() < movie.size)
            assertFalse(File(folder, "video.mp4").exists())
        }
    }

    private data class Reply(val status: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap(), val includeLength: Boolean = true)
    private class Stub(handler: (Map<String, String>) -> Reply) : AutoCloseable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}/video"
        private val failure = AtomicReference<Throwable>()
        private val worker = thread(isDaemon = true, name = "progress-http-stub") {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: SocketException) { break }
                try {
                    client.use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        if (reader.readLine() == null) return@use
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        val reply = handler(headers)
                        val head = buildString {
                            append("HTTP/1.1 ${reply.status} OK\r\nConnection: close\r\n")
                            if (reply.includeLength) append("Content-Length: ${reply.body.size}\r\n")
                            reply.headers.forEach { (key, value) -> append("$key: $value\r\n") }
                            append("\r\n")
                        }
                        try { socket.getOutputStream().apply { write(head.toByteArray()); write(reply.body); flush() } }
                        catch (_: SocketException) { /* Cancellation closes the client connection. */ }
                    }
                } catch (error: Throwable) { failure.compareAndSet(null, error) }
            }
        }
        override fun close() {
            server.close(); worker.join(1_000)
            failure.get()?.let { throw AssertionError("HTTP stub failed", it) }
        }
    }
}
