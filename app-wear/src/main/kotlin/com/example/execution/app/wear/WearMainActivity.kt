package com.example.execution.app.wear

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.execution.wear.cache.WatchDisplayState
import com.example.execution.wear.tile.WatchActionMapper
import com.example.execution.wear.tile.WatchButton
import com.example.execution.wear.protocol.WearStateDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Wear state viewer with quick actions:
 *  - shows cached state immediately (Fase 12 offline behaviour),
 *  - updates from the Data Layer via WearDataLayerService,
 *  - action buttons send commands back to the phone (Interrupt/Resume/Finish/Start/+5/Skip).
 */
class WearMainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var label: TextView
    private lateinit var buttonRow1: LinearLayout
    private lateinit var buttonRow2: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 60, 24, 24)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        label = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        }
        buttonRow1 = row()
        buttonRow2 = row()
        root.addView(label)
        root.addView(buttonRow1)
        root.addView(buttonRow2)
        setContentView(root)

        // Real cache + provider; the listener service pushes into the same provider.
        val cache = DataStoreWearStateCache(this)
        val provider = com.example.execution.wear.cache.WatchStateProvider(cache = cache)
        WearDataLayerBridge.currentProvider = provider

        WearDataLayerBridge.stateConsumer = { display ->
            scope.launch { render(display) }
        }

        scope.launch {
            render(provider.current())
            while (true) {
                kotlinx.coroutines.delay(5_000)
                render(provider.current())
            }
        }
    }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
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
                else if (display.fromCache) append("\n(cached)")
            }
        }

        // rebuild action buttons from the pure mapper
        buttonRow1.removeAllViews()
        buttonRow2.removeAllViews()
        val buttons = WatchActionMapper.buttons(display) { "watch-${System.nanoTime()}" }
        buttons.forEachIndexed { i, b ->
            val target = if (i < 3) buttonRow1 else buttonRow2
            target.addView(button(b))
        }
    }

    private fun button(b: WatchButton): Button = Button(this).apply {
        text = b.label
        textSize = 11f
        setOnClickListener {
            scope.launch {
                runCatching { WearCommandSender.send(this@WearMainActivity, b.command) }
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
