package xyz.chouxuewei.mobile_agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceImageCoordinatesTest {
    private val transform = ImageCoordinateTransform(
        screenWidth = 720,
        screenHeight = 1280,
        imageWidth = 800,
        imageHeight = 1360,
        padding = 40,
    )

    @Test fun paddedImagePointMapsBackToOriginalScreenPixel() {
        val mapped = transform.mapPoint(340, 640)

        assertTrue(mapped.insideContent)
        assertEquals(300, mapped.screenX)
        assertEquals(600, mapped.screenY)
        assertEquals(340, transform.imageX(mapped.screenX))
        assertEquals(640, transform.imageY(mapped.screenY))
    }

    @Test fun contentEdgesMapExactlyAndBlackBorderIsRejected() {
        assertEquals(0, transform.mapPoint(40, 40).screenX)
        assertEquals(0, transform.mapPoint(40, 40).screenY)
        assertEquals(719, transform.mapPoint(759, 1319).screenX)
        assertEquals(1279, transform.mapPoint(759, 1319).screenY)
        assertFalse(transform.mapPoint(39, 640).insideContent)
        assertFalse(transform.mapPoint(760, 640).insideContent)
        assertFalse(transform.mapPoint(340, 39).insideContent)
        assertFalse(transform.mapPoint(340, 1320).insideContent)
    }

    @Test fun targetBoxUsesItsCenterInImageCoordinates() {
        val box = ImageTargetBox(left = 320, top = 600, right = 360, bottom = 680)

        assertEquals(340, box.centerX)
        assertEquals(640, box.centerY)
        val mapped = transform.mapPoint(box.centerX, box.centerY)
        assertEquals(300, mapped.screenX)
        assertEquals(600, mapped.screenY)
    }

    @Test fun targetBoxMustHavePositiveArea() {
        assertThrows(IllegalArgumentException::class.java) {
            ImageTargetBox(left = 100, top = 200, right = 100, bottom = 240)
        }
    }
}
