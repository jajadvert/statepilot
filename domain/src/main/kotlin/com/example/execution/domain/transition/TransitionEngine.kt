package com.example.execution.domain.transition

import com.example.execution.domain.activity.TransitionPolicy
import com.example.execution.domain.schedule.ScheduleStatus
import kotlinx.datetime.Instant

/** Evidence that a transition is warranted. */
enum class EvidenceType { CALENDAR_DUE, GEOFENCE_EXIT, GEOFENCE_ENTER, MANUAL_INPUT, MOTION_STATE, CURRENT_WIFI, BLUETOOTH_CONNECTION }

data class TransitionEvidence(
    val type: EvidenceType,
    val detail: String,
    /** Contribution to confidence (0..1). */
    val weight: Double
)

/**
 * A suggestion to transition to a target activity, with confidence and
 * a fully explainable evidence trail.
 */
data class TransitionSuggestion(
    val targetActivityTypeId: String,
    val confidence: Double,
    val evidence: List<TransitionEvidence>,
    val recommendedAction: TransitionPolicy
)

/** Inputs the TransitionEngine needs for one evaluation round. */
data class TransitionContext(
    val now: Instant,
    val schedule: ScheduleStatus,
    /** True when a geofence exit was detected around now. */
    val geofenceExit: Boolean,
    /** True when a geofence enter was detected around now. */
    val geofenceEnter: Boolean,
    /** True when motion is detected (walking/driving). */
    val motionDetected: Boolean,
    val currentActivityTypeId: String?,
    /** Per-activity automation policy; default SUGGEST. */
    val policyFor: (String) -> TransitionPolicy = { TransitionPolicy.SUGGEST }
)

/**
 * Fase 17: computes explainable transition suggestions with confidence.
 * Weights are configurable; defaults follow the plan example:
 *  calendar due +0.40, geofence exit +0.35, motion +0.20.
 */
class TransitionEngine(
    private val calendarDueWeight: Double = 0.40,
    private val geofenceExitWeight: Double = 0.35,
    private val geofenceEnterWeight: Double = 0.25,
    private val motionWeight: Double = 0.20
) {

    /**
     * Evaluate a transition to [targetActivityTypeId] (usually the next
     * planned block). Confidence = sum of present evidence weights,
     * clamped to 0..1. Recommended action follows the activity policy.
     */
    fun suggest(targetActivityTypeId: String, ctx: TransitionContext): TransitionSuggestion {
        val evidence = mutableListOf<TransitionEvidence>()
        var confidence = 0.0

        val next = ctx.schedule.nextPlannedBlock
        val due = next != null && ctx.schedule.transitionStatus.name in setOf("OVERDUE", "DUE")
        if (due) {
            evidence += TransitionEvidence(EvidenceType.CALENDAR_DUE, "planned start ${next!!.plannedStart}", calendarDueWeight)
            confidence += calendarDueWeight
        }

        if (ctx.geofenceExit) {
            evidence += TransitionEvidence(EvidenceType.GEOFENCE_EXIT, "geofence exit detected", geofenceExitWeight)
            confidence += geofenceExitWeight
        }

        if (ctx.geofenceEnter) {
            evidence += TransitionEvidence(EvidenceType.GEOFENCE_ENTER, "geofence enter detected", geofenceEnterWeight)
            confidence += geofenceEnterWeight
        }

        if (ctx.motionDetected) {
            evidence += TransitionEvidence(EvidenceType.MOTION_STATE, "movement detected", motionWeight)
            confidence += motionWeight
        }

        val clamped = confidence.coerceIn(0.0, 1.0)
        val policy = ctx.policyFor(targetActivityTypeId)

        return TransitionSuggestion(
            targetActivityTypeId = targetActivityTypeId,
            confidence = clamped,
            evidence = evidence,
            recommendedAction = when {
                clamped >= 0.9 && policy == TransitionPolicy.AUTO -> TransitionPolicy.AUTO
                clamped >= 0.3 -> TransitionPolicy.SUGGEST
                else -> TransitionPolicy.MANUAL
            }
        )
    }

    /** Human-readable explanation, e.g. for the notification body. */
    fun explain(suggestion: TransitionSuggestion): String = buildString {
        append(suggestion.targetActivityTypeId.replaceFirstChar { it.uppercase() } + " suggested because:")
        suggestion.evidence.forEach { e ->
            append("\n- ${e.detail}")
        }
    }
}
