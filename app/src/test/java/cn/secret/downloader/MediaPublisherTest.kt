package cn.secret.downloader

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class MediaPublisherTest {
    @Test fun motionPhotosArePublishedToTheGalleryWithoutMovingOtherDownloads() {
        assertEquals(MediaPublisher.LIVE_DISPLAY_PATH, MediaPublisher.displayPath(File("clip_MP.jpg")))
        assertEquals(MediaPublisher.DISPLAY_PATH, MediaPublisher.displayPath(File("clip_live.mp4")))
        assertEquals(MediaPublisher.DISPLAY_PATH, MediaPublisher.displayPath(File("photo.jpg")))
    }

    @Test fun repeatedDownloadsNeverOverwritePreviousFiles() {
        val folder = Files.createTempDirectory("secret-download-test").toFile()
        try {
            File(folder, "photo.jpg").writeText("old")
            File(folder, "photo (1).jpg").writeText("old")
            assertEquals("photo (2).jpg", MediaPublisher.unusedFile(folder, "photo.jpg").name)
            assertEquals("clip.mp4", MediaPublisher.unusedFile(folder, "clip.mp4").name)
            File(folder, "clip_MP.jpg").writeText("old")
            assertEquals("clip (1)_MP.jpg", MediaPublisher.unusedFile(folder, "clip_MP.jpg").name)
            assertEquals("old", File(folder, "photo.jpg").readText())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test fun rejectsNamesOutsideDownloadFolder() {
        for (name in listOf("../outside.mp4", "..", ".", "")) {
            assertThrows(IllegalArgumentException::class.java) { MediaPublisher.unusedFile(File("."), name) }
        }
    }
}
