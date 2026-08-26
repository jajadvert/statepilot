package com.example.execution.app.wear

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.example.execution.wear.cache.WatchDisplayState
import com.example.execution.wear.protocol.WearStateDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Wear state viewer wired to the real data path:
 *  - shows cached state immediately (Fase 12 offline behaviour),
 *  - updates whenever WearDataLayerService receives a fresh state,
 *  - shows a stale marker when the cached copy is old.
 */
class WearMainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var label: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        label = TextView(this)
        label.setPadding(40, 80, 40, 40)
        setContentView(label)

        // Real cache + provider; the service pushes into the same provider.
        val cache = DataStoreWearStateCache(this)
        val provider = com.example.execution.wear.cache.WatchStateProvider(cache = cache)
        WearDataLayerBridge.currentProvider = provider

        // The listener service notifies us of fresh/offline display states.
        WearDataLayerBridge.stateConsumer = { display ->
            scope.launch { render(display) }
        }

        // Initial render: cached state (or placeholder when nothing yet).
        scope.launch {
            render(provider.current())
            // watch live updates while connected
            while (true) {
                kotlinx.coroutines.delay(5_000)
                render(provider.current())
            }
        }
    }

    private fun render(display: WatchDisplayState) {
        val state = display.state
        val activity = state?.currentActivity
        label.text = when {
            activity == null -> "No current state"
            else -> buildString {
                append(activity.label)
                state.currentStateStartedAtEpochMs?.let {
                    val elapsedSec = (System.currentTimeMillis() - it) / 1000
                    append("\n").append(formatElapsed(elapsedSec))
                }
                if (display.isStale) append("\n(offline)")
            }
        }
    }

    /** Public for instrumentation: push a state and re-render (Fase 10 API kept). */
    fun render(state: WearStateDto) {
        scope.launch {
            val display = WearDataLayerBridge.currentProvider.onIncoming(state)
            render(display)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}

private fun formatElapsed(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
