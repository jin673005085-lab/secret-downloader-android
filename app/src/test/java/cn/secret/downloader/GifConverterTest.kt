package cn.secret.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GifConverterTest {
    @Test fun defaultsAndFifteenSecondLimitKeepInputBoundsAndEvenAspectRatio() {
        val normal = GifParameters.parse("{}", 5_000, 1920, 1080)
        assertEquals(480, normal.width); assertEquals(270, normal.height)
        assertEquals(15, normal.fps); assertEquals(0, normal.loop)
        assertEquals(5_000L, normal.clipMs); assertFalse(normal.trimmed); assertFalse(normal.high)
        val long = GifParameters.parse("{\"startMs\":20000,\"endMs\":100000,\"width\":0}", 90_000, 641, 359)
        assertEquals(20_000L, long.startMs); assertEquals(15_000L, long.clipMs); assertTrue(long.trimmed)
        assertEquals(640, long.width); assertEquals(358, long.height)
        val portrait = GifParameters.parse("{\"width\":720,\"speed\":0.5,\"loop\":\"一次\",\"quality\":\"高\"}", 15_000, 1080, 1920)
        assertEquals(720, portrait.width); assertEquals(1280, portrait.height); assertEquals(-1, portrait.loop)
        assertEquals(30_000_000.0, portrait.outputDurationUs, 0.0); assertTrue(portrait.high)
    }

    @Test fun invalidSelectionsAndUnsupportedEncodingParametersFailBeforeStartingFfmpeg() {
        for (json in listOf("{\"startMs\":-1}", "{\"startMs\":1000,\"endMs\":1000}",
            "{\"startMs\":5000}", "{\"fps\":0}", "{\"width\":-1}", "{\"speed\":0}",
            "{\"speed\":3}", "{\"quality\":\"unknown\"}", "{\"loop\":\"unknown\"}")) {
            assertThrows(json, IllegalArgumentException::class.java) { GifParameters.parse(json, 5_000, 1920, 1080) }
        }
        assertThrows(IllegalArgumentException::class.java) { GifParameters.parse("{}", 0, 1920, 1080) }
        assertThrows(IllegalArgumentException::class.java) { GifParameters.parse("{\"width\":0}", 1000, 100_000, 1) }
    }

    @Test fun commandsClipBeforeInputThenApplySpeedAndUseOnePaletteFrameForTheHighQualityPass() {
        val plan = GifParameters.parse("{\"startMs\":2000,\"endMs\":7000,\"quality\":\"high\",\"speed\":2,\"loop\":\"once\"}", 9_000, 1920, 1080, 2)
        val palette = plan.paletteCommand("/video with spaces.mp4", "/palette.png")
        assertEquals(listOf("-ss", "2.000000", "-t", "5.000000", "-i", "/video with spaces.mp4"), palette.take(6))
        assertEquals("1", palette[palette.indexOf("-frames:v") + 1])
        assertTrue(palette.any { "palettegen=stats_mode=full" in it })
        val command = plan.outputCommand("/video.mp4", "/output.gif", "/palette.png")
        assertTrue(command.any { "[0:2]" in it && "setpts=(PTS-STARTPTS)/2.0" in it && "paletteuse=" in it })
        assertEquals("-1", command[command.indexOf("-loop") + 1])
        assertEquals(listOf("-progress", "pipe:1", "/output.gif"), command.takeLast(3))
        val standard = plan.copy(high = false).outputCommand("/video.mp4", "/output.gif")
        assertFalse(standard.any { "paletteuse" in it || "palettegen" in it })
        assertTrue(standard.any { "format=rgb8" in it })
    }

    @Test fun progressUsesOutputTimeAndNeverRegressesAcrossPassesOrNegativeEncoderTimestamps() {
        val values = mutableListOf<Float>()
        val progress = GifProgress(10_000_000.0, values::add)
        progress.emit(0f)
        progress.line("out_time_us=5000000", 0f, .5f)
        progress.line("out_time_us=-1000", 0f, .5f)
        progress.emit(.5f)
        progress.line("out_time_us=2500000", .5f, .5f)
        progress.line("out_time_us=999999999", .5f, .5f)
        progress.line("speed=1.5x", 0f, 1f)
        progress.emit(1f)
        assertEquals(listOf(0f, .25f, .5f, .625f, 1f), values)
    }
}
