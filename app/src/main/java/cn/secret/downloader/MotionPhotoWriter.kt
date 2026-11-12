package cn.secret.downloader

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

object MotionPhotoWriter {
    fun write(image: File, video: File, output: File, presentationTimestampUs: Long = 0): File {
        require(image.isFile && image.length() >= 4 && video.isFile && video.length() >= 12) { "实况素材不完整" }
        require(output.canonicalFile !in setOf(image.canonicalFile, video.canonicalFile)) { "不能覆盖原始素材" }
        RandomAccessFile(image, "r").use {
            require(it.readUnsignedShort() == 0xffd8) { "封面不是 JPEG 图片" }
            it.seek(it.length() - 2)
            require(it.readUnsignedShort() == 0xffd9) { "JPEG 封面不完整" }
        }
        RandomAccessFile(video, "r").use {
            val header = ByteArray(12)
            it.readFully(header)
            require(String(header, 4, 4, Charsets.US_ASCII) == "ftyp") { "动态素材不是 MP4 视频" }
        }
        val exif = segment(0xe1, oplusExif())
        val xmp = segment(0xe1, "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII) +
            metadata(video.length(), presentationTimestampUs).toByteArray(Charsets.UTF_8))
        val imageLength = image.length() + exif.size + xmp.size + 74L
        val mpf = segment(0xe2, mpf(imageLength))
        check(output.createNewFile()) { "实况输出文件已存在" }
        try {
            output.outputStream().buffered().use { target ->
                target.write(byteArrayOf(0xff.toByte(), 0xd8.toByte()))
                target.write(exif)
                target.write(xmp)
                target.write(mpf)
                image.inputStream().buffered().use { source ->
                    check(source.skip(2) == 2L)
                    copy(source, target)
                }
                video.inputStream().buffered().use { copy(it, target) }
            }
            check(output.length() == imageLength + video.length()) { "实况文件写入不完整" }
            return output
        } catch (error: Exception) {
            output.delete()
            throw error
        }
    }

    internal fun metadata(videoLength: Long, timestampUs: Long): String {
        require(videoLength > 0 && timestampUs >= -1)
        // Oplus tags and MPF follow real-device output documented by Young-Spark/oppo-live-photo-maker.
        return """<?xpacket begin="${'\uFEFF'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about=""
            xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
            xmlns:OpCamera="http://ns.oplus.com/photos/1.0/camera/"
            xmlns:Container="http://ns.google.com/photos/1.0/container/"
            xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
            GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1" GCamera:MotionPhotoPresentationTimestampUs="$timestampUs"
            OpCamera:MotionPhotoPrimaryPresentationTimestampUs="${timestampUs.coerceAtLeast(0)}" OpCamera:MotionPhotoOwner="oplus" OpCamera:OLivePhotoVersion="2"
            OpCamera:VideoLength="$videoLength" OpCamera:MotionPhotoFeatureFlag="1">
            <Container:Directory><rdf:Seq><rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/></rdf:li>
            <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoLength"/></rdf:li></rdf:Seq></Container:Directory>
            </rdf:Description></rdf:RDF></x:xmpmeta>
            <?xpacket end="w"?>""".trimIndent()
    }

    private fun segment(marker: Int, payload: ByteArray): ByteArray = bytes {
        require(payload.size <= 65533) { "实况元数据过大" }
        writeByte(0xff)
        writeByte(marker)
        writeShort(payload.size + 2)
        write(payload)
    }

    private fun oplusExif(): ByteArray = bytes {
        write("Exif\u0000\u0000MM".toByteArray(Charsets.US_ASCII))
        writeShort(42)
        writeInt(8)
        writeShort(1)
        writeShort(0x8769); writeShort(4); writeInt(1); writeInt(26)
        writeInt(0)
        val comment = "ASCII\u0000\u0000\u0000Oplus_8388608".toByteArray(Charsets.US_ASCII)
        writeShort(1)
        writeShort(0x9286); writeShort(7); writeInt(comment.size); writeInt(44)
        writeInt(0)
        write(comment)
    }

    private fun mpf(imageLength: Long): ByteArray = bytes {
        require(imageLength in 1..0xffffffffL) { "JPEG 封面过大" }
        write("MPF\u0000MM".toByteArray(Charsets.US_ASCII))
        writeShort(42)
        writeInt(8)
        writeShort(3)
        writeShort(0xb000); writeShort(7); writeInt(4); writeBytes("0100")
        writeShort(0xb001); writeShort(4); writeInt(1); writeInt(1)
        writeShort(0xb002); writeShort(7); writeInt(16); writeInt(50)
        writeInt(0)
        writeInt(0x00030000); writeInt(imageLength.toInt()); writeInt(0); writeShort(0); writeShort(0)
    }

    private fun bytes(block: DataOutputStream.() -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use(block)
        return output.toByteArray()
    }

    private fun copy(source: InputStream, target: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("转换已停止")
            val count = source.read(buffer)
            if (count < 0) break
            target.write(buffer, 0, count)
        }
    }
}
