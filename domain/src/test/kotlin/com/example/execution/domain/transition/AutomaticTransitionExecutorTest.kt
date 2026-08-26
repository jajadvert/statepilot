package com.example.execution.domain.transition

import com.example.execution.domain.activity.TransitionPolicy
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Fase 18 test list:
 *  - AUTO + high confidence -> executes + audit record
 *  - SUGGEST policy -> never executes
 *  - low confidence -> never executes
 *  - no evidence -> never executes
 *  - audit trail always written (DoD)
 */
class AutomaticTransitionExecutorTest {

    private val audit = InMemoryAutomationAuditRepository()
    private var idc = 0
    private val executed = mutableListOf<String>()

    private val executor = AutomaticTransitionExecutor(
        audit = audit,
        idGenerator = { "audit-${++idc}" },
        executeTransition = { target, requestId -> executed.add("$target:$requestId"); true }
    )

    private fun suggestion(policy: TransitionPolicy, confidence: Double, evidenceCount: Int = 1) =
        TransitionSuggestion(
            targetActivityTypeId = "travel",
            confidence = confidence,
            evidence = List(evidenceCount) { TransitionEvidence(EvidenceType.GEOFENCE_EXIT, "exit home", 0.35) },
            recommendedAction = policy
        )

    @Test
    fun `auto with high confidence executes and audits`() = runTest {
        val record = executor.evaluate(suggestion(TransitionPolicy.AUTO, 0.95), "req-1")
        assertNotNull(record)
        assertEquals("travel", record!!.targetActivityTypeId)
        assertEquals(0.95, record.confidence)
        assertEquals(1, record.evidence.size)
        assertEquals("engine", record.executedBy)
        assertEquals(1, executed.size)
        assertEquals(1, audit.getAll().size)
    }

    @Test
    fun `suggest policy never executes`() = runTest {
        val record = executor.evaluate(suggestion(TransitionPolicy.SUGGEST, 0.95), "req-2")
        assertNull(record)
        assertTrue(executed.isEmpty())
        assertTrue(audit.getAll().isEmpty())
    }

    @Test
    fun `manual policy never executes`() = runTest {
        val record = executor.evaluate(suggestion(TransitionPolicy.MANUAL, 0.95), "req-3")
        assertNull(record)
        assertTrue(executed.isEmpty())
    }

    @Test
    fun `low confidence never executes`() = runTest {
        val record = executor.evaluate(suggestion(TransitionPolicy.AUTO, 0.5), "req-4")
        assertNull(record)
        assertTrue(executed.isEmpty())
        assertTrue(audit.getAll().isEmpty())
    }

    @Test
    fun `no evidence never executes`() = runTest {
        val record = executor.evaluate(suggestion(TransitionPolicy.AUTO, 0.95, evidenceCount = 0), "req-5")
        assertNull(record)
        assertTrue(executed.isEmpty())
    }

    @Test
    fun `failed execution leaves no audit record`() = runTest {
        val failingExecutor = AutomaticTransitionExecutor(
            audit = audit,
            idGenerator = { "audit-x" },
            executeTransition = { _, _ -> false }
        )
        val record = failingExecutor.evaluate(suggestion(TransitionPolicy.AUTO, 0.95), "req-6")
        assertNull(record)
        assertTrue(audit.getAll().isEmpty())
    }
}
