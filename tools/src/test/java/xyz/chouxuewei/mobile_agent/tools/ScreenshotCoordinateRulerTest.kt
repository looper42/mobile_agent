package xyz.chouxuewei.mobile_agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotCoordinateRulerTest {
    @Test fun portraitLayoutPreservesOriginalPixelCoordinates() {
        val layout = coordinateRulerLayout(720, 1280)

        assertEquals(40, layout.padding)
        assertEquals(800, layout.imageWidth)
        assertEquals(1360, layout.imageHeight)
        assertEquals(listOf(0, 60, 120, 180, 240, 300, 360, 419, 479, 539, 599, 659, 719), layout.xTicks)
        assertEquals(listOf(40, 100, 160, 220, 280, 340, 400, 459, 519, 579, 639, 699, 759), layout.xTickLabels)
        assertEquals(listOf(0, 80, 160, 240, 320, 400, 480, 560, 640, 719, 799, 879, 959, 1039, 1119, 1199, 1279), layout.yTicks)
        assertEquals(listOf(40, 120, 200, 280, 360, 440, 520, 600, 680, 759, 839, 919, 999, 1079, 1159, 1239, 1319), layout.yTickLabels)
    }

    @Test fun landscapeLayoutUsesTheSameUnrotatedScreenSpace() {
        val layout = coordinateRulerLayout(1280, 720)

        assertEquals(40, layout.padding)
        assertEquals(1360, layout.imageWidth)
        assertEquals(800, layout.imageHeight)
        assertEquals(listOf(0, 80, 160, 240, 320, 400, 480, 560, 640, 719, 799, 879, 959, 1039, 1119, 1199, 1279), layout.xTicks)
        assertEquals(listOf(40, 120, 200, 280, 360, 440, 520, 600, 680, 759, 839, 919, 999, 1079, 1159, 1239, 1319), layout.xTickLabels)
        assertEquals(listOf(0, 60, 120, 180, 240, 300, 360, 419, 479, 539, 599, 659, 719), layout.yTicks)
        assertEquals(listOf(40, 100, 160, 220, 280, 340, 400, 459, 519, 579, 639, 699, 759), layout.yTickLabels)
    }

    @Test fun paddingAndTextRemainBoundedAcrossDisplaySizes() {
        val small = coordinateRulerLayout(320, 480)
        val large = coordinateRulerLayout(2160, 3840)

        assertEquals(32, small.padding)
        assertEquals(80, large.padding)
        assertTrue(small.textSize >= 14f)
        assertTrue(large.textSize <= 30f)
    }
}
