package xyz.chouxuewei.mobile_agent.overlay

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

/** Owns WindowManager attachment identity so add/update/remove remain idempotent after failures. */
internal class OverlayWindowController(context: Context) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val resources = context.resources

    var view: View? = null
        private set
    var layout: WindowManager.LayoutParams? = null
        private set

    fun attach(candidate: View, params: WindowManager.LayoutParams): Boolean {
        if (view != null) return false
        return runCatching {
            windows.addView(candidate, params)
            view = candidate
            layout = params
        }.isSuccess
    }

    fun update(): Boolean {
        val attached = view ?: return false
        val params = layout ?: return false
        return runCatching { windows.updateViewLayout(attached, params) }
            .fold(
                onSuccess = { true },
                onFailure = {
                    // A revoked overlay permission or dead window token invalidates this attachment.
                    detach()
                    false
                },
            )
    }

    fun detach(): Int? {
        val attached = view
        val y = layout?.y
        view = null
        layout = null
        if (attached != null) runCatching { windows.removeView(attached) }
        return y
    }

    fun safeBounds(): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windows.currentWindowMetrics
            return Rect(metrics.bounds).apply {
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                left += insets.left
                top += insets.top
                right -= insets.right
                bottom -= insets.bottom
            }
        }
        @Suppress("DEPRECATION")
        return Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
    }
}
