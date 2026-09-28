package xyz.chouxuewei.mobile_agent.overlay

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.chouxuewei.mobile_agent.device.AndroidDeviceGateway

/** Samples previews only while enabled and visible; cancellation bounds all decode work. */
internal class VirtualScreenPreviewController(
    private val scope: CoroutineScope,
    private val gateway: AndroidDeviceGateway,
    private val shouldSample: () -> Boolean,
    private val onPreview: (VirtualScreenPreview?) -> Unit,
) {
    private var job: Job? = null

    fun setEnabled(enabled: Boolean) {
        job?.cancel()
        job = null
        onPreview(null)
        if (!enabled) return
        job = scope.launch {
            var revision = 0L
            while (isActive) {
                if (shouldSample()) {
                    val frame = try {
                        gateway.latestVirtualDisplayPreview(revision)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    if (frame != null) {
                        revision = frame.revision
                        val preview = withContext(Dispatchers.Default) {
                            BitmapFactory.decodeByteArray(frame.jpegBytes, 0, frame.jpegBytes.size)
                                ?.let { bitmap ->
                                    VirtualScreenPreview(
                                        revision = frame.revision,
                                        image = bitmap.asImageBitmap(),
                                        width = frame.width,
                                        height = frame.height,
                                    )
                                }
                        }
                        if (preview != null) onPreview(preview)
                    }
                }
                delay(PREVIEW_INTERVAL_MILLIS)
            }
        }
    }

    fun close() = setEnabled(false)

    private companion object {
        const val PREVIEW_INTERVAL_MILLIS = 500L
    }
}
