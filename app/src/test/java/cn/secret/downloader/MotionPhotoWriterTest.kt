package cn.secret.downloader

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.xml.parsers.DocumentBuilderFactory

class MotionPhotoWriterTest {
    @get:Rule val temporary = TemporaryFolder()
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0, 2, 0xff.toByte(), 0xd9.toByte())
    private val movie = ByteArray(300_000) { (it % 251).toByte() }.apply {
        "ftyp".toByteArray().copyInto(this, 4)
    }

    @Test fun packagesWholeVideoWithGoogleAndOplusMetadataAndAccurateMpf() {
        val image = temporary.newFile("cover.jpg").apply { writeBytes(jpeg) }
        val video = temporary.newFile("video.mp4").apply { writeBytes(movie) }
        val output = temporary.root.resolve("result_MP.jpg")
        MotionPhotoWriter.write(image, video, output)
        val result = output.readBytes()
        assertArrayEquals(movie, result.copyOfRange(result.size - movie.size, result.size))
        assertArrayEquals(jpeg, image.readBytes())
        assertArrayEquals(movie, video.readBytes())
        val imageLength = result.size - movie.size
        assertArrayEquals(jpeg.copyOfRange(2, jpeg.size), result.copyOfRange(imageLength - jpeg.size + 2, imageLength))

        var cursor = 2
        val segments = mutableListOf<Pair<Int, ByteArray>>()
        repeat(3) {
            val marker = result[cursor + 1].toInt() and 255
            val length = ((result[cursor + 2].toInt() and 255) shl 8) or (result[cursor + 3].toInt() and 255)
            segments += marker to result.copyOfRange(cursor + 4, cursor + 2 + length)
            cursor += 2 + length
        }
        assertEquals(listOf(0xe1, 0xe1, 0xe2), segments.map { it.first })
        val exif = segments[0].second
        val tiff = ByteBuffer.wrap(exif, 6, exif.size - 6).slice().order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x4d4d, tiff.getShort(0).toInt())
        assertEquals(0x8769, tiff.getShort(10).toInt() and 65535)
        assertEquals(26, tiff.getInt(18))
        assertEquals(0x9286, tiff.getShort(28).toInt() and 65535)
        assertEquals(44, tiff.getInt(36))
        assertEquals("ASCII\u0000\u0000\u0000Oplus_8388608", String(exif.copyOfRange(50, exif.size), Charsets.US_ASCII))
        val xmp = String(segments[1].second, Charsets.UTF_8).substringAfter('\u0000')
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(xmp.toByteArray()))
        val description = document.getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "Description").item(0)
        assertEquals("1", description.attributes.getNamedItemNS("http://ns.google.com/photos/1.0/camera/", "MotionPhoto").nodeValue)
        assertEquals(movie.size.toString(), description.attributes.getNamedItemNS("http://ns.oplus.com/photos/1.0/camera/", "VideoLength").nodeValue)
        assertNull(description.attributes.getNamedItemNS("http://ns.google.com/photos/1.0/camera/", "MicroVideoOffset"))
        assertEquals("oplus", description.attributes.getNamedItemNS("http://ns.oplus.com/photos/1.0/camera/", "MotionPhotoOwner").nodeValue)
        val items = document.getElementsByTagNameNS("http://ns.google.com/photos/1.0/container/", "Item")
        assertEquals(2, items.length)
        val itemNamespace = "http://ns.google.com/photos/1.0/container/item/"
        for (index in 0 until items.length) {
            val item = items.item(index) as org.w3c.dom.Element
            // Android's JPEG extractor reads Item attributes, never nested property elements.
            assertEquals(if (index == 0) "image/jpeg" else "video/mp4", item.getAttributeNS(itemNamespace, "Mime"))
            assertEquals(if (index == 0) "Primary" else "MotionPhoto", item.getAttributeNS(itemNamespace, "Semantic"))
            assertEquals(if (index == 0) "0" else movie.size.toString(), item.getAttributeNS(itemNamespace, "Length"))
            assertEquals(0, item.getElementsByTagNameNS(itemNamespace, "Mime").length)
        }
        assertEquals("0", (items.item(0) as org.w3c.dom.Element).getAttributeNS(itemNamespace, "Padding"))
        val motion = items.item(1) as org.w3c.dom.Element
        assertFalse(motion.hasAttributeNS(itemNamespace, "Padding"))
        val strictReaderOffset = result.size - motion.getAttributeNS(itemNamespace, "Length").toLong()
        assertEquals(imageLength.toLong(), strictReaderOffset)
        assertEquals("ftyp", String(result, strictReaderOffset.toInt() + 4, 4, Charsets.US_ASCII))
        assertTrue(xmp.startsWith("<?xpacket begin=\"\uFEFF\""))
        assertTrue(xmp.endsWith("<?xpacket end=\"w\"?>"))
        val mpf = segments[2].second
        assertEquals(70, mpf.size)
        val mpTiff = ByteBuffer.wrap(mpf, 4, mpf.size - 4).slice().order(ByteOrder.BIG_ENDIAN)
        assertEquals(50, mpTiff.getInt(42))
        assertEquals(0x00030000, mpTiff.getInt(50))
        assertEquals(imageLength, mpTiff.getInt(54))
    }

    @Test fun supportsVideoOffsetsBeyondTwoGigabytes() {
        val length = Int.MAX_VALUE.toLong() + 100_000
        val metadata = MotionPhotoWriter.metadata(length, -1)
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(metadata.toByteArray()))
        val motion = document.getElementsByTagNameNS("http://ns.google.com/photos/1.0/container/", "Item").item(1)
        assertEquals(length, motion.attributes.getNamedItemNS("http://ns.google.com/photos/1.0/container/item/", "Length").nodeValue.toLong())
        assertTrue(metadata.contains("OpCamera:VideoLength=\"$length\""))
        assertFalse(metadata.contains("MicroVideo"))
        assertTrue(metadata.contains("GCamera:MotionPhotoPresentationTimestampUs=\"-1\""))
        assertTrue(metadata.contains("OpCamera:MotionPhotoPrimaryPresentationTimestampUs=\"0\""))
        val selectedFrame = MotionPhotoWriter.metadata(1000, 15_123_456)
        assertTrue(selectedFrame.contains("GCamera:MotionPhotoPresentationTimestampUs=\"15123456\""))
        assertTrue(selectedFrame.contains("OpCamera:MotionPhotoPrimaryPresentationTimestampUs=\"15123456\""))
    }

    @Test fun rejectsBadMediaAndNeverOverwritesAnExistingFile() {
        val image = temporary.newFile("cover.jpg").apply { writeBytes(jpeg) }
        val video = temporary.newFile("video.mp4").apply { writeBytes(movie) }
        val existing = temporary.newFile("existing.jpg").apply { writeText("original") }
        assertThrows(Exception::class.java) { MotionPhotoWriter.write(image, video, existing) }
        assertEquals("original", existing.readText())
        assertThrows(Exception::class.java) { MotionPhotoWriter.write(image, video, video) }
        val output = temporary.root.resolve("bad_MP.jpg")
        video.writeText("<html>not a video</html>")
        assertThrows(Exception::class.java) { MotionPhotoWriter.write(image, video, output) }
        assertFalse(output.exists())
        video.writeBytes(movie)
        image.writeBytes(jpeg.dropLast(1).toByteArray())
        assertThrows(Exception::class.java) { MotionPhotoWriter.write(image, video, output) }
        assertFalse(output.exists())
    }

    @Test fun interruptedWriteRemovesOnlyItsOwnPartialOutput() {
        val image = temporary.newFile("cover.jpg").apply { writeBytes(jpeg) }
        val video = temporary.newFile("video.mp4").apply { writeBytes(movie) }
        val output = temporary.root.resolve("cancelled_MP.jpg")
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) { MotionPhotoWriter.write(image, video, output) }
        } finally {
            Thread.interrupted()
        }
        assertFalse(output.exists())
        assertArrayEquals(movie, video.readBytes())
        assertArrayEquals(jpeg, image.readBytes())
    }
}
