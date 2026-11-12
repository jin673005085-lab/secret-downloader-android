package cn.secret.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ConversionEstimateTest {
    @Test fun liveKeepsTheWholeRangeWhileGifTrimsToFifteenSecondsAndCanStartAnywhere() {
        assertEquals(0L to 120_000L, ConversionEstimate.range(120_000, 0, 120_000))
        assertEquals(3_600_000L to 7_200_000L, ConversionEstimate.range(7_200_000, 3_600_000, 7_200_000))
        assertEquals(0L to 15_000L, ConversionEstimate.gifRange(120_000, 0, 120_000))
        assertEquals(3_600_000L to 3_615_000L, ConversionEstimate.gifRange(7_200_000, 3_600_000, 7_200_000))
        assertEquals(999L to 1_000L, ConversionEstimate.range(1_000, 5_000, 0))
        assertEquals(0L to 1L, ConversionEstimate.range(1_000, -100, -1))
        assertThrows(IllegalArgumentException::class.java) { ConversionEstimate.range(0, 0, 1) }
    }

    @Test fun framesAndEstimateFollowDurationSpeedResolutionAndQualityWithoutLoopMultiplication() {
        assertEquals(75L, ConversionEstimate.frames(5_000, 15, 1.0))
        assertEquals(150L, ConversionEstimate.frames(5_000, 15, .5))
        assertEquals(38L, ConversionEstimate.frames(5_000, 15, 2.0))
        val normal = ConversionEstimate.bytes(75, 480, 270, false)
        assertEquals(1_166_400L, normal)
        assertTrue(ConversionEstimate.bytes(75, 480, 270, true) > normal)
        assertTrue(ConversionEstimate.bytes(75, 720, 405, false) > normal)
        assertTrue(ConversionEstimate.frames(7_200_000, 20, .5) > 0)
        assertThrows(IllegalArgumentException::class.java) { ConversionEstimate.frames(1_000, 15, 0.0) }
    }
}
