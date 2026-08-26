package com.example.execution.app.wear

import android.content.Context
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.example.execution.wear.cache.WatchDisplayState
import com.example.execution.wear.protocol.WearCommandDto
import com.example.execution.wear.protocol.WearStateDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json

/**
 * Fase 11: real Wear Data Layer on the watch side.
 *
 * Receives state pushed by the phone (DataClient DataItem /statepilot/state),
 * feeds it through the cache + revision merger, and updates the app/tile.
 * Commands are sent watch->phone via MessageClient (/statepilot/command)
 * with requestId for idempotent execution on the phone.
 */
class WearDataLayerService : WearableListenerService() {

    private val scope = CoroutineScope(Job() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    /** How the app/tile consumes updated state. Injectable for tests. */
    var stateConsumer: ((WatchDisplayState) -> Unit)? = null

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            if (event.dataItem.uri.path != com.example.execution.wear.protocol.WearDataLayerPaths.STATE_PATH) continue
            val bytes = event.dataItem.data ?: continue
            scope.launch {
                runCatching {
                    val state = json.decodeFromString(WearStateDto.serializer(), bytes.decodeToString())
                    val display = WearDataLayerBridge.currentProvider.onIncoming(state)
                    stateConsumer?.invoke(display)
                    StatePilotTileStateHolder.update(display)
                }
            }
        }
        dataEvents.release()
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != com.example.execution.wear.protocol.WearDataLayerPaths.COMMAND_PATH) return
        scope.launch {
            runCatching {
                val cmd = json.decodeFromString(WearCommandDto.serializer(), messageEvent.data.decodeToString())
                // forward to the app's command executor (idempotent via requestId)
                WearDataLayerBridge.onWatchCommandFromPhone?.invoke(cmd)
            }
        }
    }

    /** Send a command from the watch to the phone. */
    suspend fun sendCommand(command: WearCommandDto): Result<Unit> {
        val bytes = json.encodeToString(WearCommandDto.serializer(), command).encodeToByteArray()
        val nodes: List<Node> = Wearable.getNodeClient(this).connectedNodes.await()
        if (nodes.isEmpty()) return Result.failure(IllegalStateException("No connected nodes"))
        var lastError: Throwable? = null
        for (node in nodes) {
            runCatching {
                Wearable.getMessageClient(this)
                    .sendMessage(node.id, com.example.execution.wear.protocol.WearDataLayerPaths.COMMAND_PATH, bytes)
                    .await()
                return Result.success(Unit)
            }.onFailure { lastError = it }
        }
        return Result.failure(lastError ?: IllegalStateException("send failed"))
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[Job]?.cancel()
    }

    companion object {
        // The service is manifest-driven (BIND_LISTENER); no manual start needed.
    }
}

/** App-side wiring: cache provider + command executor are registered here. */
object WearDataLayerBridge {
    @Volatile
    var currentProvider: com.example.execution.wear.cache.WatchStateProvider =
        com.example.execution.wear.cache.WatchStateProvider(
            cache = com.example.execution.wear.cache.InMemoryWearStateCache()
        )

    @Volatile
    var onWatchCommandFromPhone: (suspend (WearCommandDto) -> Unit)? = null
}
