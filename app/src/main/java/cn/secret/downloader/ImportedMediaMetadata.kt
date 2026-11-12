package cn.secret.downloader

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Only the public Google container format is accepted; private vendor offsets are not guessed. */
internal object StandardMotionPhoto {
    private const val CAMERA = "http://ns.google.com/photos/1.0/camera/"
    private const val CONTAINER = "http://ns.google.com/photos/1.0/container/"
    private const val ITEM = "http://ns.google.com/photos/1.0/container/item/"
    private val xmpHeader = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
    data class Segment(val offset: Long, val length: Long)

    fun readJpeg(source: InputStream, size: Long): Segment? = try {
        val input = DataInputStream(source)
        if (input.readUnsignedShort() != 0xffd8) null else {
            var scanned = 2
            var result: Segment? = null
            while (scanned < 2 * 1024 * 1024) {
                if (input.readUnsignedByte() != 0xff) break
                var marker = input.readUnsignedByte()
                while (marker == 0xff) marker = input.readUnsignedByte()
                if (marker == 0xda || marker == 0xd9) break
                if (marker in 0xd0..0xd7 || marker == 0x01) continue
                val length = input.readUnsignedShort() - 2
                if (length < 0) break
                val bytes = ByteArray(length)
                input.readFully(bytes)
                scanned += length + 4
                if (marker == 0xe1 && bytes.size > xmpHeader.size &&
                    xmpHeader.indices.all { bytes[it] == xmpHeader[it] }) {
                    result = parse(String(bytes, xmpHeader.size, bytes.size - xmpHeader.size, Charsets.UTF_8), size)
                    if (result != null) break
                }
            }
            result
        }
    } catch (_: Exception) { null }

    fun parse(xmp: String, size: Long): Segment? = try {
        require(size > 12 && xmp.length <= 128 * 1024)
        require(!xmp.contains("<!DOCTYPE", ignoreCase = true) && !xmp.contains("<!ENTITY", ignoreCase = true))
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xmp.toByteArray(Charsets.UTF_8)))
        val descriptions = document.getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "Description")
        require((0 until descriptions.length).any { (descriptions.item(it) as Element).getAttributeNS(CAMERA, "MotionPhoto") == "1" })
        val directories = document.getElementsByTagNameNS(CONTAINER, "Directory")
        require(directories.length == 1)
        val items = (directories.item(0) as Element).getElementsByTagNameNS(CONTAINER, "Item")
        require(items.length >= 2)
        var total = 0L
        var videoLength = 0L
        for (index in 0 until items.length) {
            val item = items.item(index) as Element
            fun field(name: String): String {
                val attribute = item.getAttributeNS(ITEM, name)
                val children = item.getElementsByTagNameNS(ITEM, name)
                require(children.length <= 1 && !(attribute.isNotEmpty() && children.length > 0))
                return if (attribute.isNotEmpty()) attribute.trim() else if (children.length == 1) children.item(0).textContent.trim() else ""
            }
            val mime = field("Mime")
            val semantic = field("Semantic")
            val length = field("Length").ifEmpty { if (index == 0) "0" else "invalid" }.toLong()
            val padding = field("Padding").ifEmpty { "0" }.toLong()
            require(length >= 0 && padding >= 0)
            if (index == 0) {
                require(mime == "image/jpeg" && semantic == "Primary" && length == 0L)
            } else {
                require(semantic != "Primary" && padding == 0L && length <= size - total)
                total += length
            }
            if (semantic == "MotionPhoto") {
                require(index == items.length - 1 && mime == "video/mp4" && length >= 12)
                videoLength = length
            }
        }
        require(videoLength > 0 && total < size)
        Segment(size - videoLength, videoLength)
    } catch (_: Exception) { null }
}

internal object ImportedGifMetadata {
    data class Info(val width: Int, val height: Int, val durationMs: Long, val frames: Int)

    fun read(source: InputStream): Info? { return try {
        val input = DataInputStream(source)
        val signature = ByteArray(6).also(input::readFully).toString(Charsets.US_ASCII)
        require(signature == "GIF87a" || signature == "GIF89a")
        fun word() = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
        fun skip(count: Int) { var remaining = count; while (remaining > 0) { val skipped = input.skipBytes(remaining); if (skipped == 0) input.readUnsignedByte(); remaining -= if (skipped == 0) 1 else skipped } }
        fun blocks() { while (true) { val length = input.readUnsignedByte(); if (length == 0) break; skip(length) } }
        val width = word(); val height = word()
        require(width > 0 && height > 0)
        val packed = input.readUnsignedByte()
        skip(2)
        if (packed and 128 != 0) skip(3 * (1 shl ((packed and 7) + 1)))
        var frames = 0; var delay = 0L; var duration = 0L
        while (true) when (input.readUnsignedByte()) {
            0x3b -> { require(frames > 0); return Info(width, height, duration, frames) }
            0x21 -> if (input.readUnsignedByte() == 0xf9) {
                require(input.readUnsignedByte() == 4)
                input.readUnsignedByte(); delay = word() * 10L; skip(1)
                require(input.readUnsignedByte() == 0)
            } else blocks()
            0x2c -> {
                skip(8)
                val descriptor = input.readUnsignedByte()
                if (descriptor and 128 != 0) skip(3 * (1 shl ((descriptor and 7) + 1)))
                input.readUnsignedByte(); blocks()
                frames++; duration += delay; delay = 0
            }
            else -> error("GIF 数据不完整")
        }
        @Suppress("UNREACHABLE_CODE") null
    } catch (_: Exception) { null } }
}
