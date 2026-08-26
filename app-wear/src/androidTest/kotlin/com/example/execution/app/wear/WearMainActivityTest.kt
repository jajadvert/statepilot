package com.example.execution.app.wear

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.execution.wear.protocol.WearActivityDto
import com.example.execution.wear.protocol.WearStateDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 10 instrumented smoke tests, run on the Wear OS emulator.
 * Text-based assertions (no screenshots) per the agent-friendly test strategy.
 */
@RunWith(AndroidJUnit4::class)
class WearMainActivityTest {

    private fun state(revision: Long, label: String?, startedAt: Long? = null) = WearStateDto(
        revision = revision,
        currentActivity = label?.let { WearActivityDto("deep_work", it) },
        currentStateStartedAtEpochMs = startedAt
    )

    @Test
    fun placeholderShownOnLaunch() {
        val scenario: ActivityScenario<WearMainActivity> = ActivityScenario.launch(WearMainActivity::class.java)
        scenario.onActivity { activity: WearMainActivity ->
            assertEquals(
                "No current state",
                (activity.findViewById(android.R.id.content) as android.view.View).rootView.findText()
            )
        }
    }

    @Test
    fun stateLabelReplacesPlaceholderAfterCommand() {
        val scenario: ActivityScenario<WearMainActivity> = ActivityScenario.launch(WearMainActivity::class.java)
        scenario.onActivity { activity: WearMainActivity ->
            activity.render(state(1, "Deep Work"))
            assertTrue((activity.findViewById(android.R.id.content) as android.view.View).rootView.findText().startsWith("Deep Work"))
        }
    }

    @Test
    fun olderRevisionIgnored() {
        val scenario: ActivityScenario<WearMainActivity> = ActivityScenario.launch(WearMainActivity::class.java)
        scenario.onActivity { activity: WearMainActivity ->
            activity.render(state(5, "Travel"))
            activity.render(state(4, "Stale State")) // ignored: revision < 5
            assertEquals("Travel", (activity.findViewById(android.R.id.content) as android.view.View).rootView.findText())
        }
    }
}

/** Tiny helper to pull rendered text out of the view hierarchy. */
private fun android.view.View.findText(): String {
    if (this is android.widget.TextView) return text?.toString() ?: ""
    if (this is android.view.ViewGroup) {
        for (i in 0 until childCount) {
            val t = getChildAt(i).findText()
            if (t.isNotEmpty()) return t
        }
    }
    return ""
}
