package xyz.chouxuewei.mobile_agent.device.capture

import java.io.ByteArrayOutputStream
import java.io.InputStream
import xyz.chouxuewei.mobile_agent.core.localizedText

internal const val MAX_CAPTURE_PIXELS = 16_000_000L
internal const val MAX_CAPTURE_INPUT_BYTES = 32 * 1024 * 1024
internal const val MAX_CAPTURE_OUTPUT_BYTES = 8 * 1024 * 1024
internal const val MAX_PREVIEW_OUTPUT_BYTES = 4 * 1024 * 1024

internal fun requireCapturePixels(width: Int, height: Int) {
    require(width > 0 && height > 0 && width.toLong() * height <= MAX_CAPTURE_PIXELS) {
        localizedText("截图像素数超出安全限制", "Screenshot dimensions exceed the safe pixel limit.")
    }
}

internal fun InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = LimitedByteArrayOutputStream(limit)
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal class LimitedByteArrayOutputStream(private val limit: Int) :
    ByteArrayOutputStream(minOf(limit, 64 * 1024)) {
    override fun write(value: Int) {
        ensureCapacityFor(1)
        super.write(value)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        ensureCapacityFor(length)
        super.write(buffer, offset, length)
    }

    private fun ensureCapacityFor(additional: Int) {
        check(count.toLong() + additional <= limit) {
            localizedText("截图编码结果超出安全限制", "Encoded screenshot exceeds the safe size limit.")
        }
    }
}
