package xyz.chouxuewei.mobile_agent.core

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Conflates high-frequency reply snapshots into bounded recovery checkpoints.
 * Terminal state is still written by ConversationStore.finishRun; this writer only reduces the
 * amount of recoverable text lost if the process is killed during a stream.
 */
internal class ReplyCheckpointWriter(
    scope: CoroutineScope,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val persist: suspend (StreamingReplySnapshot) -> Unit,
) {
    private val latest = AtomicReference<StreamingReplySnapshot?>()
    private val persistedRevision = AtomicLong(0)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val persistGate = Mutex()
    private val worker: Job = scope.launch(CoroutineName("reply-checkpoint-writer")) {
        for (ignored in requests) {
            delay(intervalMillis)
            while (requests.tryReceive().isSuccess) Unit
            try {
                persistLatest()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // A later delta or the terminal flush retries the newest revision. Streaming must
                // not fail merely because one best-effort recovery checkpoint could not be saved.
                AgentLog.e("Runtime", failure) { "reply_checkpoint_failed" }
            }
        }
    }

    fun submit(snapshot: StreamingReplySnapshot) {
        latest.set(snapshot)
        requests.trySend(Unit)
    }

    suspend fun flush(snapshot: StreamingReplySnapshot): Result<Unit> {
        latest.set(snapshot)
        return runCatching { persistLatest() }
    }

    suspend fun closeAndFlush(snapshot: StreamingReplySnapshot): Result<Unit> {
        latest.set(snapshot)
        requests.close()
        worker.cancelAndJoin()
        return runCatching { persistLatest() }
    }

    private suspend fun persistLatest() = persistGate.withLock {
        val snapshot = latest.get() ?: return@withLock
        if (snapshot.revision <= persistedRevision.get()) return@withLock
        persist(snapshot)
        persistedRevision.set(snapshot.revision)
    }

    private companion object {
        const val DEFAULT_INTERVAL_MILLIS = 120L
    }
}
