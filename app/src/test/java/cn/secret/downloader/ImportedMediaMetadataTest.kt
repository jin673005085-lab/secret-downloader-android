package cn.secret.downloader

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class ImportedMediaMetadataTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun readsOurWriterUsingPublicContainerAndPreservesEntireLongVideo() {
        val image = temporary.newFile("cover.jpg").apply { writeBytes(byteArrayOf(-1, -40, -1, -32, 0, 2, -1, -39)) }
        val video = temporary.newFile("motion.mp4").apply { writeBytes(ByteArray(300_000).also { "ftyp".toByteArray().copyInto(it, 4) }) }
        val output = temporary.root.resolve("live_MP.jpg")
        MotionPhotoWriter.write(image, video, output)
        val segment = output.inputStream().use { StandardMotionPhoto.readJpeg(it, output.length()) }
        assertNotNull(segment)
        assertEquals(video.length(), segment!!.length)
        assertEquals(output.length() - video.length(), segment.offset)
        assertArrayEquals(video.readBytes(), output.readBytes().copyOfRange(segment.offset.toInt(), output.length().toInt()))
        assertNull(image.inputStream().use { StandardMotionPhoto.readJpeg(it, image.length()) })
    }

    @Test fun acceptsStandardAttributesWithArbitraryNamespacePrefixes() {
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/" xmlns:r="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
          xmlns:c="http://ns.google.com/photos/1.0/camera/" xmlns:d="http://ns.google.com/photos/1.0/container/"
          xmlns:i="http://ns.google.com/photos/1.0/container/item/"><r:RDF><r:Description c:MotionPhoto="1">
          <d:Directory><r:Seq><r:li><d:Item i:Mime="image/jpeg" i:Semantic="Primary"/></r:li>
          <r:li><d:Item i:Mime="video/mp4" i:Semantic="MotionPhoto" i:Length="40"/></r:li>
          </r:Seq></d:Directory></r:Description></r:RDF></x:xmpmeta>"""
        assertEquals(StandardMotionPhoto.Segment(160, 40), StandardMotionPhoto.parse(xmp, 200))
        assertNull(StandardMotionPhoto.parse(xmp.replace("i:Length=\"40\"", "i:Length=\"400\""), 200))
        assertNull(StandardMotionPhoto.parse(xmp.replace("c:MotionPhoto=\"1\"", "c:MotionPhoto=\"0\""), 200))
        assertNull(StandardMotionPhoto.parse(xmp.replace("http://ns.google.com/photos/1.0/camera/", "http://ns.vendor.com/camera/"), 200))
        assertNull(StandardMotionPhoto.parse("<!DOCTYPE x [<!ENTITY leak SYSTEM 'file:///private'>]>$xmp", 200))
    }

    @Test fun rejectsPrivateOnlyOffsetsAndStrippedOrAmbiguousContainers() {
        val xmp = MotionPhotoWriter.metadata(100, 0)
        assertNotNull(StandardMotionPhoto.parse(xmp, 300))
        assertNull(StandardMotionPhoto.parse(xmp, 99))
        assertNull(StandardMotionPhoto.parse(xmp.replace("Item:Length=\"100\"", "Item:Length=\"-1\""), 300))
        assertNull(StandardMotionPhoto.parse(xmp.replace("GCamera:MotionPhoto=\"1\"", "OpCamera:VendorMotionPhoto=\"1\""), 300))
        assertNull(StandardMotionPhoto.parse(xmp.replace("Item:Semantic=\"MotionPhoto\"", "Item:Semantic=\"Primary\""), 300))
    }

    @Test fun readsGifDimensionsFramesAndDelayWithoutDecodingPixels() {
        // Two 1x1 frames, with 100 ms and 200 ms graphics control delays.
        val header = byteArrayOf(71, 73, 70, 56, 57, 97, 1, 0, 1, 0, 0, 0, 0)
        fun frame(delay: Int) = byteArrayOf(0x21, -7, 4, 0, delay.toByte(), 0, 0, 0,
            0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0)
        val bytes = header + frame(10) + frame(20) + byteArrayOf(0x3b)
        assertEquals(ImportedGifMetadata.Info(1, 1, 300, 2), ImportedGifMetadata.read(ByteArrayInputStream(bytes)))
        assertNull(ImportedGifMetadata.read(ByteArrayInputStream(bytes.dropLast(2).toByteArray())))
        assertNull(ImportedGifMetadata.read(ByteArrayInputStream("not a gif".toByteArray())))
    }
}
