package xyz.chouxuewei.mobile_agent.overlay

import android.app.Notification
import android.app.NotificationManager

/** Narrow system boundary so notification frequency can be measured and controlled independently. */
internal fun interface OverlayNotificationSink {
    fun notify(id: Int, notification: Notification)
}

internal class SystemOverlayNotificationSink(
    private val manager: NotificationManager,
) : OverlayNotificationSink {
    override fun notify(id: Int, notification: Notification) = manager.notify(id, notification)
}
