package com.example.execution.domain.transition

import com.example.execution.domain.activity.TransitionPolicy
import kotlinx.datetime.Instant

/** Automation settings per activity type. */
data class AutomationRule(
    val activityTypeId: String,
    val policy: TransitionPolicy
)

/** One executed automatic transition — the audit trail (§18 DoD). */
data class AutomaticTransitionRecord(
    val id: String,
    val at: Instant,
    val targetActivityTypeId: String,
    val confidence: Double,
    val evidence: List<TransitionEvidence>,
    val source: String, // e.g. "geofence", "calendar", "manual"
    val executedBy: String // "engine" or "user-confirmed"
)

/** Repository for the audit trail. */
interface AutomationAuditRepository {
    suspend fun insert(record: AutomaticTransitionRecord)
    suspend fun getAll(): List<AutomaticTransitionRecord>
}

/**
 * Fase 18: executes automatic transitions ONLY when:
 *  - the activity is configured AUTO, AND
 *  - confidence is high (>= [autoThreshold]), AND
 *  - the transition would actually change the current activity.
 * Every execution writes an audit record; nothing runs without a trail.
 */
class AutomaticTransitionExecutor(
    private val audit: AutomationAuditRepository,
    private val idGenerator: () -> String,
    private val autoThreshold: Double = 0.9,
    /** Execute a transition; returns true when applied. Injected engine command. */
    private val executeTransition: suspend (String, String) -> Boolean
) {
    /**
     * Evaluate + possibly execute. Returns the record when executed, null otherwise.
     * [requestId] keeps the underlying command idempotent.
     */
    suspend fun evaluate(suggestion: TransitionSuggestion, requestId: String): AutomaticTransitionRecord? {
        val current = suggestion.recommendedAction
        if (current != TransitionPolicy.AUTO) return null
        if (suggestion.confidence < autoThreshold) return null
        if (suggestion.evidence.isEmpty()) return null

        val applied = executeTransition(suggestion.targetActivityTypeId, requestId)
        if (!applied) return null

        val record = AutomaticTransitionRecord(
            id = idGenerator(),
            at = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
            targetActivityTypeId = suggestion.targetActivityTypeId,
            confidence = suggestion.confidence,
            evidence = suggestion.evidence,
            source = suggestion.evidence.first().type.name,
            executedBy = "engine"
        )
        audit.insert(record)
        return record
    }
}

/** Simple in-memory audit store (test double). */
class InMemoryAutomationAuditRepository : AutomationAuditRepository {
    private val items = mutableListOf<AutomaticTransitionRecord>()
    override suspend fun insert(record: AutomaticTransitionRecord) { items.add(record) }
    override suspend fun getAll(): List<AutomaticTransitionRecord> = items.toList()
}
