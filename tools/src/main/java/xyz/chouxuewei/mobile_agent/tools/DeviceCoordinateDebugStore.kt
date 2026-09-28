package xyz.chouxuewei.mobile_agent.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.chouxuewei.mobile_agent.core.AgentLog

/** Debug-only asynchronous recorder for the latest model coordinate decision. */
internal class DeviceCoordinateDebugStore(
    private val directory: File,
) : DeviceCoordinateDebugSink {
    private val generation = AtomicLong()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun record(value: CoordinateDebugRecord) {
        val source = value.observation.debugImage ?: return
        val currentGeneration = generation.incrementAndGet()
        scope.launch {
            runCatching {
                val rendered = renderCoordinateDebugImage(source, value)
                ensureActive()
                if (generation.get() != currentGeneration) return@runCatching
                val path = withContext(Dispatchers.IO) { publish(rendered) }
                AgentLog.i("Device") {
                    "coordinate_debug_saved observation=${value.observation.observationId} action=${value.action} path=$path"
                }
            }.onFailure { error ->
                AgentLog.w("Device", error) {
                    "coordinate_debug_failed observation=${value.observation.observationId} action=${value.action}"
                }
            }
        }
    }

    private fun renderCoordinateDebugImage(
        source: CoordinateDebugImage,
        record: CoordinateDebugRecord,
    ): ByteArray {
        val decoded = checkNotNull(BitmapFactory.decodeByteArray(source.bytes, 0, source.bytes.size)) {
            "Could not decode coordinate debug image"
        }
        try {
            val output = decoded.copy(Bitmap.Config.ARGB_8888, true)
            try {
                val canvas = Canvas(output)
                val scale = minOf(output.width, output.height) / 720f
                val stroke = (3f * scale).coerceIn(3f, 8f)
                val outerRadius = (22f * scale).coerceIn(18f, 42f)
                val innerRadius = outerRadius * 0.55f
                val rawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.RED
                    style = Paint.Style.STROKE
                    strokeWidth = stroke
                }
                val actualPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.GREEN
                    style = Paint.Style.STROKE
                    strokeWidth = stroke
                }
                val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.YELLOW
                    style = Paint.Style.STROKE
                    strokeWidth = stroke
                }
                val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.CYAN
                    style = Paint.Style.STROKE
                    strokeWidth = stroke
                }

                drawLegend(canvas, record, scale)
                record.targetBox?.let { box ->
                    canvas.drawRect(
                        RectF(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat()),
                        boxPaint,
                    )
                }
                record.points.zipWithNext().forEach { (start, end) ->
                    canvas.drawLine(
                        start.imageX.toFloat(), start.imageY.toFloat(),
                        end.imageX.toFloat(), end.imageY.toFloat(),
                        pathPaint,
                    )
                }
                record.points.forEach { point ->
                    val x = point.imageX.toFloat()
                    val y = point.imageY.toFloat()
                    canvas.drawCircle(x, y, outerRadius, rawPaint)
                    canvas.drawLine(x - outerRadius, y, x + outerRadius, y, rawPaint)
                    canvas.drawLine(x, y - outerRadius, x, y + outerRadius, rawPaint)
                    if (point.insideContent) {
                        val projectedX = record.observation.transform.imageX(point.screenX).toFloat()
                        val projectedY = record.observation.transform.imageY(point.screenY).toFloat()
                        canvas.drawCircle(projectedX, projectedY, innerRadius, actualPaint)
                    }
                }
                return ByteArrayOutputStream().use { bytes ->
                    check(output.compress(Bitmap.CompressFormat.JPEG, DEBUG_JPEG_QUALITY, bytes)) {
                        "Could not encode coordinate debug image"
                    }
                    bytes.toByteArray()
                }
            } finally {
                output.recycle()
            }
        } finally {
            decoded.recycle()
        }
    }

    private fun drawLegend(canvas: Canvas, record: CoordinateDebugRecord, scale: Float) {
        val textSize = (22f * scale).coerceIn(18f, 38f)
        val padding = (10f * scale).coerceAtLeast(8f)
        val lines = buildList {
            add("${record.action}  RED=model  GREEN=mapped screen point  YELLOW=target box")
            record.points.take(4).forEachIndexed { index, point ->
                add(
                    "P${index + 1} image=(${point.imageX},${point.imageY}) -> " +
                            if (point.insideContent) "screen=(${point.screenX},${point.screenY})"
                            else "OUTSIDE SCREEN CONTENT",
                )
            }
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            this.textSize = textSize
        }
        val lineHeight = textSize * 1.25f
        val background = Paint().apply { color = 0xCC000000.toInt() }
        canvas.drawRect(
            0f,
            0f,
            canvas.width.toFloat(),
            padding * 2 + lineHeight * lines.size,
            background,
        )
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, padding, padding + textSize + lineHeight * index, textPaint)
        }
    }

    private fun publish(bytes: ByteArray): String {
        check(directory.isDirectory || directory.mkdirs()) {
            "Could not create coordinate debug directory: ${directory.absolutePath}"
        }
        val target = File(directory, DEBUG_FILE_NAME)
        val temporary = File.createTempFile("coordinate-debug-", ".tmp", directory)
        try {
            temporary.outputStream().buffered().use { it.write(bytes) }
            if (target.exists()) check(target.delete()) {
                "Could not replace coordinate debug image: ${target.absolutePath}"
            }
            check(temporary.renameTo(target)) {
                "Could not publish coordinate debug image: ${target.absolutePath}"
            }
        } finally {
            temporary.delete()
        }
        return target.absolutePath
    }

    private companion object {
        const val DEBUG_FILE_NAME = "latest-coordinate.jpg"
        const val DEBUG_JPEG_QUALITY = 94
    }
}
