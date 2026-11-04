package com.gmch.codexremote

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

enum class RecoveryPhase { Cached, Restoring, Ready, Failed }
internal data class RecoveryTarget(val environment: String, val engine: String, val generation: Int,
    val target: String, val selection: Int = 0)
internal class RecoveryTimeout : IOException("恢复超时，请重试；已有内容和草稿已保留")

/** A monotonic deadline propagated through each RPC, including its output-lock wait. */
internal class RecoveryDeadline(val endsAtNanos: Long) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RecoveryDeadline>
    fun remainingMillis(): Long = ((endsAtNanos - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
}

/** Main-thread owned. A lane owns one target, not one engine-wide in-flight boolean.
 * Old finally blocks can only retire their own entry. No network work holds a state lock.
 */
internal class SessionRecovery(private val scope: CoroutineScope) {
    private data class Entry(val target: RecoveryTarget, val job: Job)
    private val entries = mutableMapOf<String, Entry>()
    private val auxiliary = Semaphore(2)
    var detailBudgetMs = 60_000L
    var readBudgetMs = 45_000L

    fun launch(lane: String, target: RecoveryTarget, current: () -> Boolean,
        started: () -> Unit = {}, failed: (Throwable) -> Unit,
        finished: () -> Unit = {}, block: suspend () -> Unit): Job {
        entries[lane]?.takeIf { it.target == target && it.job.isActive }?.let { return it.job }
        cancel(lane)
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            if (!current()) return@launch
            started()
            val budget = if (lane == "detail") detailBudgetMs else readBudgetMs
            try {
                val complete = withTimeoutOrNull(budget) {
                    withContext(RecoveryDeadline(System.nanoTime() + budget * 1_000_000)) {
                        if (lane.startsWith("aux:")) auxiliary.withPermit { block() } else block()
                    }
                    true
                }
                if (complete == null && current() && entries[lane]?.job === job) failed(RecoveryTimeout())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current() && entries[lane]?.job === job) failed(e) }
            finally {
                if (entries[lane]?.job === job) {
                    entries.remove(lane)
                    if (current()) finished()
                }
            }
        }
        entries[lane] = Entry(target, job)
        job.start()
        return job
    }

    fun activeLanes(): Set<String> = entries.filterValues { it.job.isActive }.keys.toSet()

    fun cancel(lane: String) { entries.remove(lane)?.job?.cancel() }
    fun cancelAll() { val old = entries.values.toList(); entries.clear(); old.forEach { it.job.cancel() } }
}
