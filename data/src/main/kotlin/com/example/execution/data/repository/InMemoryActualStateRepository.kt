package com.example.execution.data.repository

import com.example.execution.domain.repository.ActualStateRepository
import com.example.execution.domain.state.ActualState
import kotlinx.datetime.Instant

/**
 * In-memory implementation. Guarantees:
 *  - MAX 1 active ActualState (getCurrent returns the single open state)
 *  - closed states stay immutable (finish throws on already-closed state)
 */
class InMemoryActualStateRepository : ActualStateRepository {
    private val states = linkedMapOf<String, ActualState>()

    override suspend fun getCurrent(): ActualState? =
        states.values.firstOrNull { it.endedAt == null }

    override suspend fun getById(id: String): ActualState? = states[id]

    override suspend fun insert(state: ActualState) {
        if (state.endedAt == null && getCurrent() != null && getCurrent()!!.id != state.id) {
            error("Invariant violated: inserting active state while another is active")
        }
        require(states[state.id] == null) { "state ${state.id} already exists" }
        states[state.id] = state
    }

    override suspend fun finish(id: String, endedAt: Instant) {
        val s = states.getValue(id)
        check(s.endedAt == null) { "closed state $id is immutable" }
        check(!endedAt.isBeforeInstant(s.startedAt)) { "endedAt before startedAt for $id" }
        states[id] = s.copy(endedAt = endedAt)
    }

    override suspend fun getHistory(from: Instant, to: Instant): List<ActualState> =
        states.values.filter { !it.startedAt.isBeforeInstant(from) && !it.startedAt.isAfterInstant(to) }

    private fun Instant.isBeforeInstant(other: Instant) = this < other
    private fun Instant.isAfterInstant(other: Instant) = this > other
}
