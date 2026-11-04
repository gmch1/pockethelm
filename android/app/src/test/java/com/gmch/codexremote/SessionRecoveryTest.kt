package com.gmch.codexremote

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SessionRecoveryTest {
    private fun target(id: String, selection: Int = 0) = RecoveryTarget("env", "codex", 1, id, selection)

    @Test fun changingTargetDoesNotWaitForOldReadAndSameTargetCoalesces() = runBlocking {
        val recovery = SessionRecovery(this)
        var current = target("A")
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val errors = mutableListOf<Throwable>()
        val a = recovery.launch("detail", current, { current.target == "A" }, failed = errors::add) {
            entered.complete(Unit); never.await()
        }
        entered.await()
        val same = recovery.launch("detail", current, { true }, failed = errors::add) { error("duplicate") }
        assertSame(a, same)
        current = target("B", 1)
        val b = recovery.launch("detail", current, { true }, failed = errors::add) { }
        withTimeout(2_000) { b.join() }
        assertTrue(a.isCancelled); assertTrue(errors.isEmpty())
        current = target("A", 2)
        var restoredAgain = false
        recovery.launch("detail", current, { true }, failed = errors::add) { restoredAgain = true }.join()
        assertTrue("A→B→A must start a fresh selection generation", restoredAgain)
    }

    @Test fun oldFinallyCannotClearNewHandleOrFinishItsLoading() = runBlocking {
        val recovery = SessionRecovery(this)
        val oldEntered = CompletableDeferred<Unit>()
        val finalizer = CompletableDeferred<Unit>()
        val newEntered = CompletableDeferred<Unit>()
        val releaseNew = CompletableDeferred<Unit>()
        var oldFinish = 0
        val old = recovery.launch("detail", target("A"), { true }, failed = { throw it }, finished = { oldFinish++ }) {
            try { oldEntered.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { finalizer.await() } }
        }
        oldEntered.await()
        val b = recovery.launch("detail", target("B"), { true }, failed = { throw it }) {
            newEntered.complete(Unit); releaseNew.await()
        }
        newEntered.await(); finalizer.complete(Unit); old.join()
        assertEquals(0, oldFinish)
        assertSame(b, recovery.launch("detail", target("B"), { true }, failed = { throw it }) { error("duplicated") })
        releaseNew.complete(Unit); b.join()
    }

    @Test fun ownDeadlineIsFailureButLifecycleCancellationIsNot() = runBlocking {
        val recovery = SessionRecovery(this).also { it.detailBudgetMs = 40 }
        val errors = mutableListOf<Throwable>()
        val timeout = recovery.launch("detail", target("A"), { true }, failed = errors::add) { awaitCancellation() }
        withTimeout(2_000) { timeout.join() }
        assertTrue(errors.single() is RecoveryTimeout)
        val entered = CompletableDeferred<Unit>()
        val cancelled = recovery.launch("detail", target("B"), { true }, failed = errors::add) {
            entered.complete(Unit); awaitCancellation()
        }
        entered.await(); recovery.cancelAll(); cancelled.join()
        assertEquals(1, errors.size)
        val ready = recovery.launch("detail", target("B"), { true }, failed = errors::add) { }
        ready.join(); assertEquals(1, errors.size)
    }

    @Test fun listAndDetailAreIndependentAndAuxiliaryConcurrencyIsBounded() = runBlocking {
        val recovery = SessionRecovery(this)
        val listEntered = CompletableDeferred<Unit>()
        val list = recovery.launch("list", target("list"), { true }, failed = { throw it }) {
            listEntered.complete(Unit); awaitCancellation()
        }
        listEntered.await()
        recovery.launch("detail", target("A"), { true }, failed = { throw it }) { }.join()
        assertTrue(list.isActive)
        var running = 0; var peak = 0
        val both = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val jobs = (1..5).map { i -> recovery.launch("aux:$i", target("A"), { true }, failed = { throw it }) {
            running++; peak = maxOf(peak, running); if (running == 2) both.complete(Unit)
            try { release.await() } finally { running-- }
        } }
        both.await(); assertEquals(2, running); release.complete(Unit); jobs.joinAll()
        assertEquals(2, peak); recovery.cancelAll()
    }
}
