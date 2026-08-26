package com.example.execution.app.phone

import android.content.Context
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.example.execution.wear.WearTransport
import com.example.execution.wear.protocol.WearCommandDto
import com.example.execution.wear.protocol.WearStateDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json

/**
 * Fase 11: real Wear Data Layer transport on the phone side (§14/§15).
 *
 *  - phone -> watch: publishes WearStateDto as a DataItem (DataClient) under
 *    /statepilot/state; the watch's WearableListenerService picks it up.
 *  - watch -> phone: commands arrive via MessageClient (path /statepilot/command);
 *    each command carries its requestId so execution is idempotent.
 *
 * Wire this into PhoneWearBridge as the transport instead of FakeWearTransport
 * once a paired device is available.
 */
class MessageClientWearTransport(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : WearTransport {

    private val appContext = context.applicationContext
    private val dataClient = Wearable.getDataClient(appContext)
    private val messageClient = Wearable.getMessageClient(appContext)
    private val nodeClient = Wearable.getNodeClient(appContext)

    private val _states = MutableStateFlow(WearStateDto(revision = 0))
    override val states: Flow<WearStateDto> = _states

    /** Incoming watch commands are forwarded to this handler (set by the app). */
    var onCommand: (suspend (WearCommandDto) -> Unit)? = null

    private val messageListener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
        if (event.path == COMMAND_PATH) {
            val cmd = runCatching {
                json.decodeFromString(WearCommandDto.serializer(), event.data.decodeToString())
            }.getOrNull() ?: return@OnMessageReceivedListener
            commandChannel.trySend(cmd)
        }
    }

    private val commandChannel = kotlinx.coroutines.channels.Channel<WearCommandDto>(capacity = 16)

    init {
        messageClient.addListener(messageListener)
        // drain incoming commands on the app scope
    }

    /** Collects incoming watch commands (from the channel). */
    fun commandFlow(): kotlinx.coroutines.flow.Flow<WearCommandDto> = commandChannel.receiveAsFlow()

    /** Publish state to all connected wearable nodes as a DataItem. */
    override suspend fun publish(state: WearStateDto): Boolean {
        _states.value = state
        val bytes = json.encodeToString(WearStateDto.serializer(), state).encodeToByteArray()
        val nodes: List<Node> = nodeClient.connectedNodes.await()
        if (nodes.isEmpty()) return false
        var published = true
        for (node in nodes) {
            runCatching {
                val putReq = com.google.android.gms.wearable.PutDataMapRequest.create(STATE_PATH).apply {
                    dataMap.putByteArray(KEY_STATE, bytes)
                }
                dataClient.putDataItem(putReq.asPutDataRequest()).await()
            }.onFailure { published = false }
        }
        return published
    }

    /** Watch commands are sent BY the watch, not the phone — this is unused on the phone side. */
    override suspend fun sendCommand(command: WearCommandDto): Result<Unit> =
        Result.failure(IllegalStateException("sendCommand is watch->phone; phone side only receives"))

    fun destroy() {
        messageClient.removeListener(messageListener)
    }

    companion object {
        const val STATE_PATH = com.example.execution.wear.protocol.WearDataLayerPaths.STATE_PATH
        const val COMMAND_PATH = com.example.execution.wear.protocol.WearDataLayerPaths.COMMAND_PATH
        const val KEY_STATE = com.example.execution.wear.protocol.WearDataLayerPaths.KEY_STATE
    }
}
