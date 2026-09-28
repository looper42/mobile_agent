package xyz.chouxuewei.mobile_agent.tools

internal data class ImageCoordinateTransform(
    val screenWidth: Int,
    val screenHeight: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val padding: Int,
) {
    init {
        require(screenWidth > 0 && screenHeight > 0)
        require(padding >= 0)
        require(imageWidth == screenWidth + padding * 2)
        require(imageHeight == screenHeight + padding * 2)
    }

    val contentLeft: Int get() = padding
    val contentTop: Int get() = padding
    val contentRightExclusive: Int get() = padding + screenWidth
    val contentBottomExclusive: Int get() = padding + screenHeight

    fun mapPoint(imageX: Int, imageY: Int): ImagePointMapping {
        val screenX = imageX - padding
        val screenY = imageY - padding
        return ImagePointMapping(
            imageX = imageX,
            imageY = imageY,
            screenX = screenX,
            screenY = screenY,
            insideContent = imageX in contentLeft until contentRightExclusive &&
                    imageY in contentTop until contentBottomExclusive,
        )
    }

    fun imageX(screenX: Int): Int = screenX + padding
    fun imageY(screenY: Int): Int = screenY + padding
}

internal data class ImagePointMapping(
    val imageX: Int,
    val imageY: Int,
    val screenX: Int,
    val screenY: Int,
    val insideContent: Boolean,
)

internal data class ImageTargetBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(right > left && bottom > top) { "Target box bounds are invalid" }
    }

    val centerX: Int get() = left + (right - left) / 2
    val centerY: Int get() = top + (bottom - top) / 2
}

internal data class CoordinateDebugImage(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
)

internal data class ModelCoordinateObservation(
    val sessionId: String,
    val observationId: String,
    val transform: ImageCoordinateTransform,
    val debugImage: CoordinateDebugImage?,
)

internal data class CoordinateDebugRecord(
    val observation: ModelCoordinateObservation,
    val action: String,
    val points: List<ImagePointMapping>,
    val targetBox: ImageTargetBox? = null,
)

internal fun interface DeviceCoordinateDebugSink {
    /** Must return immediately; rendering and disk I/O belong to the sink's background scope. */
    fun record(value: CoordinateDebugRecord)
}
