package com.example.execution.app.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.PendingIntent

class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        val blockId = intent.getStringExtra(EXTRA_BLOCK_ID)
        PhoneAppGraph.handleNotificationAction(action, blockId)
    }

    companion object {
        const val EXTRA_ACTION = "action"
        const val EXTRA_BLOCK_ID = "block_id"

        fun pendingIntent(context: Context, action: String, blockId: String?): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                (action + blockId).hashCode(),
                Intent(context, NotificationActionReceiver::class.java)
                    .putExtra(EXTRA_ACTION, action)
                    .putExtra(EXTRA_BLOCK_ID, blockId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}

object PhoneAppGraph {
    private var handler: ((action: String, plannedBlockId: String?) -> Unit)? = null

    fun registerCommandHandler(h: (action: String, plannedBlockId: String?) -> Unit) {
        handler = h
    }

    fun handleNotificationAction(action: String, plannedBlockId: String?) {
        handler?.invoke(action, plannedBlockId)
    }
}
