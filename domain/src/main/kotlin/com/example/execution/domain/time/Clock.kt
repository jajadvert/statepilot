package com.example.execution.domain.time

import kotlinx.datetime.Instant

interface Clock {
    fun now(): Instant
}

class FixedClock(private var instant: Instant) : Clock {
    override fun now(): Instant = instant
    fun advanceBy(duration: kotlin.time.Duration) {
        instant += duration
    }
    fun set(instant: Instant) { this.instant = instant }
}
