package xyz.chouxuewei.mobile_agent.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import xyz.chouxuewei.mobile_agent.R
import xyz.chouxuewei.mobile_agent.core.localizedText

internal data class OverlayNotificationState(
    val channelId: String,
    val title: String,
    val content: String,
    val ongoing: Boolean,
    val interactionPending: Boolean,
    val selectedConversationId: String?,
    val stoppableConversationId: String?,
)

/** Builds and publishes only meaningful notification state changes, independent of token deltas. */
internal class OverlayNotificationController(
    private val service: Service,
    private val sink: OverlayNotificationSink,
    private val notificationId: Int,
    private val normalChannelId: String,
    private val interactionChannelId: String,
    private val openIntent: (String?) -> Intent,
    private val stopIntent: (String) -> Intent,
) {
    private var lastPublished: OverlayNotificationState? = null

    fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        service.getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(
                NotificationChannel(
                    normalChannelId,
                    localizedText("悬浮助手与 AI 任务", "Floating assistant and AI tasks"),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            createNotificationChannel(
                NotificationChannel(
                    interactionChannelId,
                    localizedText("需要确认或回答", "Approval or answer required"),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = localizedText(
                        "工具授权和 AI 询问等待处理时提醒",
                        "Alerts for pending tool approvals and AI questions",
                    )
                },
            )
        }
    }

    fun publish(state: OverlayNotificationState) {
        if (lastPublished == state) return
        lastPublished = state
        sink.notify(notificationId, build(state))
    }

    fun startForeground(state: OverlayNotificationState, microphone: Boolean): Boolean = runCatching {
        // Foreground type changes must be applied even when the visible notification is unchanged.
        lastPublished = state
        val notification = build(state)
        when {
            Build.VERSION.SDK_INT >= 34 -> {
                val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    if (microphone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
                service.startForeground(notificationId, notification, type)
            }
            Build.VERSION.SDK_INT >= 29 && microphone -> service.startForeground(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            else -> service.startForeground(notificationId, notification)
        }
    }.isSuccess

    private fun build(state: OverlayNotificationState): Notification {
        val builder = NotificationCompat.Builder(service, state.channelId)
            .setSmallIcon(R.drawable.lucide_brain_circuit)
            .setContentTitle(state.title)
            .setContentText(state.content)
            .setOngoing(state.ongoing)
            .setOnlyAlertOnce(!state.interactionPending)
            .setPriority(if (state.interactionPending) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setCategory(if (state.interactionPending) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    service,
                    0,
                    openIntent(state.selectedConversationId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        state.stoppableConversationId?.let { conversationId ->
            builder.addAction(
                R.drawable.lucide_square,
                localizedText("停止当前任务", "Stop current task"),
                PendingIntent.getService(
                    service,
                    1,
                    stopIntent(conversationId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        return builder.build()
    }
}
