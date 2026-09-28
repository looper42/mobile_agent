package xyz.chouxuewei.mobile_agent.chat

import java.util.concurrent.ConcurrentHashMap
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
 * Persists only the latest draft for each conversation. Each conversation has an independent
 * serial writer, so a slow save cannot let an older draft overwrite a newer one.
 */
internal class DraftPersistenceCoordinator(
    private val scope: CoroutineScope,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val persist: suspend (PendingDraft) -> Unit,
    private val onFailure: (Exception) -> Unit,
) {
    data class PendingDraft(
        val conversationId: String,
        val draft: ComposerDraft,
        val version: Long,
        val cleanupAttachments: Boolean,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun submit(conversationId: String, draft: ComposerDraft, cleanupAttachments: Boolean) {
        entries.computeIfAbsent(conversationId) { Entry(conversationId) }
            .submit(draft, cleanupAttachments)
    }

    suspend fun flush(conversationId: String) {
        entries[conversationId]?.flush()
    }

    suspend fun flushAndRemove(conversationId: String) {
        entries.remove(conversationId)?.close(flush = true)
    }

    suspend fun discard(conversationId: String) {
        entries.remove(conversationId)?.close(flush = false)
    }

    suspend fun flushAll() {
        for (entry in entries.values.toList()) entry.flush()
    }

    private inner class Entry(private val conversationId: String) {
        private val nextVersion = AtomicLong()
        private val persistedVersion = AtomicLong()
        private val latest = AtomicReference<PendingDraft?>()
        private val requests = Channel<Unit>(Channel.CONFLATED)
        private val persistGate = Mutex()
        private val worker: Job = scope.launch(CoroutineName("draft-persistence-$conversationId")) {
            for (ignored in requests) {
                delay(debounceMillis)
                while (requests.tryReceive().isSuccess) Unit
                saveLatest(reportFailure = true)
            }
        }

        fun submit(draft: ComposerDraft, cleanupAttachments: Boolean) {
            val version = nextVersion.incrementAndGet()
            latest.updateAndGet { previous ->
                PendingDraft(
                    conversationId = conversationId,
                    draft = draft,
                    version = version,
                    cleanupAttachments = cleanupAttachments || previous?.cleanupAttachments == true,
                )
            }
            requests.trySend(Unit)
        }

        suspend fun flush() {
            saveLatest(reportFailure = true)
        }

        suspend fun close(flush: Boolean) {
            requests.close()
            worker.cancelAndJoin()
            if (flush) saveLatest(reportFailure = true)
        }

        private suspend fun saveLatest(reportFailure: Boolean) {
            try {
                persistGate.withLock {
                    val pending = latest.get() ?: return@withLock
                    if (pending.version <= persistedVersion.get()) return@withLock
                    persist(pending)
                    persistedVersion.set(pending.version)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (reportFailure) onFailure(failure)
            }
        }
    }

    private companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 250L
    }
}
