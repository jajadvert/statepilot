package com.example.execution.app.wear

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.example.execution.wear.protocol.WearStateDto
import com.example.execution.wear.protocol.WearStateMerger

/**
 * Minimal Wear state viewer (Fase 10). Renders the latest accepted
 * WearStateDto; older revisions are ignored via WearStateMerger.
 */
class WearMainActivity : Activity() {
    private val merger = WearStateMerger()
    private lateinit var label: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        label = TextView(this)
        label.setPadding(40, 80, 40, 40)
        setContentView(label)
        render(WearStateDto(revision = 0))
    }

    /** Public for instrumentation: push a state and re-render. */
    fun render(state: WearStateDto) {
        if (!merger.accept(state)) return // older revision ignored
        val activity = state.currentActivity
        label.text = when {
            activity != null -> buildString {
                append(activity.label)
                state.currentStateStartedAtEpochMs?.let {
                    val elapsedSec = (System.currentTimeMillis() - it) / 1000
                    append("\n").append(formatElapsed(elapsedSec))
                }
            }
            else -> "No current state"
        }
    }
}

private fun formatElapsed(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
