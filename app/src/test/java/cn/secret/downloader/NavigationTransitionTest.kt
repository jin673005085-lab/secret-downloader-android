package cn.secret.downloader

import org.junit.Assert.*
import org.junit.Test

class NavigationTransitionTest {
    @Test fun croppedCoverNeverExpandsIntoDifferentViewerAspectRatio() {
        assertNull(sharedElementUniformScale(96, 96, 1080, 2200))
        assertNull(sharedElementUniformScale(180, 240, 1080, 450))
        assertNull(sharedElementUniformScale(0, 96, 240, 240))
    }

    @Test fun matchingBoundsUseOneScaleForBothAxesIncludingPixelRounding() {
        val scale = checkNotNull(sharedElementUniformScale(160, 90, 640, 360))
        assertEquals(640f, 160 * scale, 0f)
        assertEquals(360f, 90 * scale, 0f)
        assertEquals(.25f, checkNotNull(sharedElementUniformScale(640, 360, 160, 90)), 0f)
        assertNotNull(sharedElementUniformScale(123, 123, 999, 1000))
        assertNull(sharedElementUniformScale(100, 100, 101, 103))
    }
}
