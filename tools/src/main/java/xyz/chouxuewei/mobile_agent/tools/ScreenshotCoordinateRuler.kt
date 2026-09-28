package xyz.chouxuewei.mobile_agent.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import xyz.chouxuewei.mobile_agent.core.ChatImageLimits
import xyz.chouxuewei.mobile_agent.core.Screenshot
import xyz.chouxuewei.mobile_agent.core.localizedText

internal data class CoordinateRulerLayout(
    val screenWidth: Int,
    val screenHeight: Int,
    val padding: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val textSize: Float,
    val xTicks: List<Int>,
    val yTicks: List<Int>,
    val xTickLabels: List<Int>,
    val yTickLabels: List<Int>,
)

internal data class CoordinateRulerImage(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val padding: Int,
)

/**
 * Adds an external ruler around the phone image. Tick labels describe the padded image pixels that
 * the model must return; DeviceToolProvider converts them back to phone-screen pixels locally.
 */
internal fun coordinateRulerLayout(width: Int, height: Int): CoordinateRulerLayout {
    require(width > 0 && height > 0) {
        localizedText("截图尺寸必须大于 0", "Screenshot dimensions must be greater than 0.")
    }
    val shortEdge = minOf(width, height)
    val padding = (shortEdge * RULER_PADDING_RATIO).roundToInt().coerceIn(MIN_RULER_PADDING, MAX_RULER_PADDING)
    val imageWidth = Math.addExact(width, Math.multiplyExact(padding, 2))
    val imageHeight = Math.addExact(height, Math.multiplyExact(padding, 2))
    require(imageWidth.toLong() * imageHeight <= ChatImageLimits.MAX_SINGLE_PIXELS) {
        localizedText("添加坐标标尺后的截图像素过大", "The screenshot is too large after adding the coordinate ruler.")
    }
    val xTicks = ticks(width)
    val yTicks = ticks(height)
    return CoordinateRulerLayout(
        screenWidth = width,
        screenHeight = height,
        padding = padding,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        textSize = (shortEdge * RULER_TEXT_RATIO).coerceIn(MIN_RULER_TEXT_SIZE, MAX_RULER_TEXT_SIZE),
        xTicks = xTicks,
        yTicks = yTicks,
        xTickLabels = xTicks.map { padding + it },
        yTickLabels = yTicks.map { padding + it },
    )
}

internal fun annotateScreenshotWithCoordinateRuler(screenshot: Screenshot): CoordinateRulerImage {
    val layout = coordinateRulerLayout(screenshot.width, screenshot.height)
    val source = checkNotNull(BitmapFactory.decodeByteArray(screenshot.bytes, 0, screenshot.bytes.size)) {
        localizedText("无法解码设备截图", "Could not decode the device screenshot.")
    }
    try {
        require(source.width == screenshot.width && source.height == screenshot.height) {
            localizedText("截图像素尺寸与坐标范围不一致", "Screenshot pixel dimensions do not match its coordinate range.")
        }
        val output = Bitmap.createBitmap(layout.imageWidth, layout.imageHeight, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(source, layout.padding.toFloat(), layout.padding.toFloat(), null)
            drawCoordinateRuler(canvas, layout)
            val (encoded, mimeType) = try {
                encode(output, Bitmap.CompressFormat.PNG, 100) to "image/png"
            } catch (_: IllegalStateException) {
                encode(output, Bitmap.CompressFormat.JPEG, RULER_JPEG_FALLBACK_QUALITY) to "image/jpeg"
            }
            return CoordinateRulerImage(
                bytes = encoded,
                mimeType = mimeType,
                width = layout.imageWidth,
                height = layout.imageHeight,
                padding = layout.padding,
            )
        } finally {
            output.recycle()
        }
    } finally {
        source.recycle()
    }
}

private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray =
    BoundedByteArrayOutputStream(MAX_RULER_OUTPUT_BYTES).use { stream ->
        check(bitmap.compress(format, quality, stream)) {
            localizedText("坐标标尺截图编码失败", "Failed to encode the screenshot coordinate ruler.")
        }
        stream.toByteArray()
    }

private fun drawCoordinateRuler(canvas: Canvas, layout: CoordinateRulerLayout) {
    val padding = layout.padding.toFloat()
    val contentRight = padding + layout.screenWidth
    val contentBottom = padding + layout.screenHeight
    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = (layout.padding * 0.045f).coerceIn(1.5f, 3f)
    }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        textSize = layout.textSize
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val tickLength = (layout.padding * 0.22f).coerceAtLeast(6f)

    // Keep the border outside the phone pixels so the screenshot itself is never covered.
    canvas.drawLine(padding - 1f, padding - 1f, contentRight, padding - 1f, linePaint)
    canvas.drawLine(padding - 1f, contentBottom, contentRight, contentBottom, linePaint)
    canvas.drawLine(padding - 1f, padding - 1f, padding - 1f, contentBottom, linePaint)
    canvas.drawLine(contentRight, padding - 1f, contentRight, contentBottom, linePaint)

    val topBaseline = centeredTextBaseline(textPaint, padding * 0.38f)
    val bottomBaseline = centeredTextBaseline(textPaint, contentBottom + padding * 0.62f)
    layout.xTicks.zip(layout.xTickLabels).forEach { (value, label) ->
        val x = padding + value
        canvas.drawLine(x, padding - 1f, x, padding - tickLength, linePaint)
        canvas.drawLine(x, contentBottom, x, contentBottom + tickLength, linePaint)
        canvas.drawText(label.toString(), x, topBaseline, textPaint)
        canvas.drawText(label.toString(), x, bottomBaseline, textPaint)
    }

    val leftCenter = padding * 0.43f
    val rightCenter = contentRight + padding * 0.57f
    layout.yTicks.zip(layout.yTickLabels).forEach { (value, label) ->
        val y = padding + value
        canvas.drawLine(padding - 1f, y, padding - tickLength, y, linePaint)
        canvas.drawLine(contentRight, y, contentRight + tickLength, y, linePaint)
        val baseline = centeredTextBaseline(textPaint, y)
        canvas.drawText(label.toString(), leftCenter, baseline, textPaint)
        canvas.drawText(label.toString(), rightCenter, baseline, textPaint)
    }
}

private fun centeredTextBaseline(paint: Paint, centerY: Float): Float {
    val metrics = paint.fontMetrics
    return centerY - (metrics.ascent + metrics.descent) / 2f
}

private fun ticks(size: Int): List<Int> {
    val divisions = rulerDivisions(size)
    return (0..divisions).map { index ->
        (((size - 1).toDouble() * index) / divisions).roundToInt()
    }.distinct()
}

private fun rulerDivisions(size: Int): Int =
    (size / MIN_RULER_TICK_SPACING).coerceIn(MIN_RULER_DIVISIONS, MAX_RULER_DIVISIONS)

private class BoundedByteArrayOutputStream(private val limit: Int) :
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
            localizedText("坐标标尺截图编码结果过大", "The encoded screenshot coordinate ruler is too large.")
        }
    }
}

private const val RULER_PADDING_RATIO = 0.055f
private const val RULER_TEXT_RATIO = 0.018f
private const val MIN_RULER_PADDING = 32
private const val MAX_RULER_PADDING = 80
private const val MIN_RULER_TEXT_SIZE = 14f
private const val MAX_RULER_TEXT_SIZE = 30f
private const val RULER_JPEG_FALLBACK_QUALITY = 95
private const val MAX_RULER_OUTPUT_BYTES = 12 * 1024 * 1024
private const val MIN_RULER_DIVISIONS = 8
private const val MAX_RULER_DIVISIONS = 16
private const val MIN_RULER_TICK_SPACING = 60
