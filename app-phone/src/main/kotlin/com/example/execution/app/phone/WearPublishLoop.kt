package com.example.execution.app.phone

import com.example.execution.wear.PhoneWearBridge
import com.example.execution.wear.protocol.WearCommandDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Integration: publishes the current state to the paired watch every second
 * via the real Wear Data Layer, and executes incoming watch commands.
 *
 * Works when phone + watch are paired (Wear OS app); safe offline
 * (publish returns false, retries next tick).
 */
class WearPublishLoop(
    private val scope: CoroutineScope,
    private val bridge: PhoneWearBridge,
    private val transport: MessageClientWearTransport,
    private val tickMs: Long = 1_000L
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (scope.isActive) {
                runCatching {
                    bridge.publishState(kotlinx.datetime.Instant.fromEpochMilliseconds(System.currentTimeMillis()))
                }
                delay(tickMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        transport.destroy()
    }
}

/** Convenience: build the whole phone->watch stack. */
fun createWearPublishLoop(
    scope: CoroutineScope,
    bridge: PhoneWearBridge,
    transport: MessageClientWearTransport
): WearPublishLoop = WearPublishLoop(scope, bridge, transport)

/** Wire incoming watch commands to the bridge (idempotent via requestId). */
fun wireWatchCommands(
    scope: CoroutineScope,
    transport: MessageClientWearTransport,
    bridge: PhoneWearBridge
) {
    scope.launch {
        transport.commandFlow().collect { cmd: WearCommandDto ->
            runCatching { bridge.onWatchCommand(cmd) }
        }
    }
}
