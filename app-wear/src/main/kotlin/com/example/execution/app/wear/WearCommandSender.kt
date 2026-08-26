package com.example.execution.app.wear

import android.content.Context
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.example.execution.wear.protocol.WearCommandDto
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json

/**
 * Sends commands watch -> phone over the Wear Data Layer (MessageClient).
 * The phone executes them idempotently via requestId.
 */
object WearCommandSender {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun send(context: Context, command: WearCommandDto): Result<Unit> {
        val bytes = json.encodeToString(WearCommandDto.serializer(), command).encodeToByteArray()
        val nodes: List<Node> = Wearable.getNodeClient(context).connectedNodes.await()
        if (nodes.isEmpty()) return Result.failure(IllegalStateException("No connected nodes"))
        var lastError: Throwable? = null
        for (node in nodes) {
            runCatching {
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, com.example.execution.wear.protocol.WearDataLayerPaths.COMMAND_PATH, bytes)
                    .await()
                return Result.success(Unit)
            }.onFailure { lastError = it }
        }
        return Result.failure(lastError ?: IllegalStateException("send failed"))
    }
}
