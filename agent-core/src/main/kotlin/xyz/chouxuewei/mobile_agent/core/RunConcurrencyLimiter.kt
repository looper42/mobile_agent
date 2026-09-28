package xyz.chouxuewei.mobile_agent.core

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Bounds simultaneous model/tool loops while queued conversations remain visible to the UI. */
internal class RunConcurrencyLimiter(maxConcurrentRuns: Int = DEFAULT_MAX_CONCURRENT_RUNS) {
    private val permits = Semaphore(maxConcurrentRuns.coerceAtLeast(1))

    suspend fun <T> run(block: suspend () -> T): T = permits.withPermit { block() }

    private companion object {
        const val DEFAULT_MAX_CONCURRENT_RUNS = 3
    }
}
