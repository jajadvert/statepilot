package com.example.execution.app.phone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.example.execution.notifications.NotificationGateway
import com.example.execution.notifications.PlannedNotification
import com.example.execution.notifications.NotificationType

class AndroidNotificationGateway(private val context: Context) : NotificationGateway {

    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val shown = linkedMapOf<String, PlannedNotification>()

    init {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "StatePilot transitions", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    override fun show(notification: PlannedNotification) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setAutoCancel(true)

        for (action in actionsFor(notification.type)) {
            builder.addAction(
                0, action,
                NotificationActionReceiver.pendingIntent(context, action, notification.plannedBlockId)
            )
        }

        nm.notify(notification.id.hashCode(), builder.build())
        shown[notification.id] = notification
    }

    override fun cancel(notificationId: String) {
        nm.cancel(notificationId.hashCode())
        shown.remove(notificationId)
    }

    override fun shownNotificationIds(): Set<String> = shown.keys

    override fun notificationFor(id: String): PlannedNotification? = shown[id]

    private fun actionsFor(type: NotificationType): List<String> = when (type) {
        NotificationType.DUE, NotificationType.OVERDUE -> listOf("START", "+10 MIN", "SKIP")
        NotificationType.TRAVEL_WARNING -> listOf("LEAVING NOW", "+5 MIN")
        NotificationType.UPCOMING -> emptyList()
    }

    companion object { const val CHANNEL_ID = "statepilot_transitions" }
}
