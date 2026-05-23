package com.kosilka.core

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates strictly monotonically increasing message IDs per session.
 * Thread-safe. Must be reset at the start of each new session.
 */
@Singleton
class MessageIdGenerator @Inject constructor() {
    private val counter = AtomicLong(0L)

    /** Returns the next message ID. IDs start at 1. */
    fun next(): Long = counter.incrementAndGet()

    /** Resets the counter to 0. Call at session teardown before the next session starts. */
    fun reset() {
        counter.set(0L)
    }
}
