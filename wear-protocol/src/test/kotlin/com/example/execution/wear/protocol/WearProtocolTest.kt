package com.example.execution.wear.protocol

import kotlinx.serialization.json.Json
import kotlin.test.*

class WearProtocolTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `serialization roundtrip`() {
        val state = WearStateDto(
            revision = 7,
            currentActivity = WearActivityDto("deep_work", "Deep Work"),
            currentStateStartedAtEpochMs = 1_000L,
            nextPlannedBlock = WearPlannedBlockDto("pb-1", "Travel", 2_000L, 3_000L),
            transitionStatus = "DUE",
            deviationSeconds = 180
        )
        val decoded = json.decodeFromString<WearStateDto>(json.encodeToString(WearStateDto.serializer(), state))
        assertEquals(state, decoded)
    }

    @Test
    fun `unknown fields ignored`() {
        val s = """{"revision":1,"futureField":"x","transitionStatus":"NONE"}"""
        assertEquals(1L, json.decodeFromString<WearStateDto>(s).revision)
    }

    @Test
    fun `old revision ignored`() {
        val m = WearStateMerger()
        assertTrue(m.accept(WearStateDto(revision = 5)))
        assertFalse(m.accept(WearStateDto(revision = 4)))
    }

    @Test
    fun `new revision accepted`() {
        val m = WearStateMerger()
        m.accept(WearStateDto(revision = 5))
        assertTrue(m.accept(WearStateDto(revision = 6)))
    }

    @Test
    fun `duplicate revision idempotent`() {
        val m = WearStateMerger()
        m.accept(WearStateDto(revision = 5))
        assertTrue(m.accept(WearStateDto(revision = 5))) // >= accepted, no change
        assertEquals(5L, m.currentRevision())
    }

    @Test
    fun `unicode labels survive roundtrip`() {
        val state = WearStateDto(revision = 1, currentActivity = WearActivityDto("deep_work", "Diepe werk – 🧠"))
        assertEquals(state, json.decodeFromString<WearStateDto>(json.encodeToString(WearStateDto.serializer(), state)))
    }

    @Test
    fun `empty planned state`() {
        val state = WearStateDto(revision = 0)
        assertEquals(state, json.decodeFromString<WearStateDto>(json.encodeToString(WearStateDto.serializer(), state)))
    }
}
