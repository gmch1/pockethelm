package com.gmch.codexremote

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RpcRecoveryTest {
    @Test fun blockedOutputTimesOutAndClosesTransportWithoutWaitingForWriter() = runBlocking {
        val input = PipedInputStream()
        val server = PipedOutputStream(input)
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val closes = AtomicInteger()
        val output = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) {
                entered.countDown()
                try { check(released.await(2, TimeUnit.SECONDS)); throw IOException("closed by owner") }
                finally { exited.countDown() }
            }
        }
        val rpc = RpcClient(input, output, {}, {
            closes.incrementAndGet(); released.countDown(); server.close(); input.close()
        })
        try {
            val request = async { runCatching { rpc.call("thread/read", obj(), timeout = 120) }.exceptionOrNull() }
            withContext(Dispatchers.IO) { assertTrue(entered.await(2, TimeUnit.SECONDS)) }
            assertTrue(withTimeout(2_000) { request.await() } is IOException)
            assertTrue(withContext(Dispatchers.IO) { exited.await(2, TimeUnit.SECONDS) })
            assertEquals(1, closes.get())
        } finally { released.countDown(); rpc.close(); server.close(); input.close() }
    }

    @Test fun approvalOutputHasDeadlineAndCannotKeepTheCallerBlocked() = runBlocking {
        val input = PipedInputStream(); val server = PipedOutputStream(input)
        val release = CountDownLatch(1); val exited = CountDownLatch(1)
        val output = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) {
                try { check(release.await(2, TimeUnit.SECONDS)); throw IOException("closed") }
                finally { exited.countDown() }
            }
        }
        val rpc = RpcClient(input, output, {}, { release.countDown(); server.close(); input.close() })
        try {
            val failure = runCatching {
                withContext(RecoveryDeadline(System.nanoTime() + 120_000_000)) { rpc.respond(7, obj()) }
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(withContext(Dispatchers.IO) { exited.await(2, TimeUnit.SECONDS) })
        } finally { release.countDown(); rpc.close(); server.close(); input.close() }
    }

    @Test fun cancellingRequestAlsoCancelsAnAlreadyEnteredSnapshotCallback() = runBlocking {
        val input = PipedInputStream(); val server = PipedOutputStream(input)
        val output = ByteArrayOutputStream()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var applied = false
        val rpc = RpcClient(input, output, {}, {})
        try {
            val request = launch { rpc.call("thread/read", obj(), onResult = {
                entered.complete(Unit); release.await(); applied = true
            }) }
            withTimeout(2_000) { while (output.size() == 0) delay(1) }
            server.write("{\"id\":\"1\",\"result\":{}}\n".toByteArray()); server.flush()
            withTimeout(2_000) { entered.await() }
            // Sending the fence first proves the first writer released the output
            // mutex; this case targets callback cancellation, not partial-write close.
            val fence = async { rpc.call("fence", obj()) }
            withTimeout(2_000) { while (output.toString().lines().count { it.isNotBlank() } < 2) delay(1) }
            request.cancelAndJoin(); release.complete(Unit)
            server.write("{\"id\":\"2\",\"result\":{}}\n".toByteArray()); server.flush()
            withTimeout(2_000) { fence.await() }
            assertFalse(applied)
        } finally { rpc.close(); server.close(); input.close() }
    }

    @Test fun cancellationAfterWriteDoesNotResendAndLateReplyCannotApplySnapshot() = runBlocking {
        val input = PipedInputStream(); val server = PipedOutputStream(input)
        val output = ByteArrayOutputStream()
        var applied = 0
        val rpc = RpcClient(input, output, {}, {})
        try {
            val request = launch { rpc.call("turn/start", obj(), onResult = { applied++ }) }
            withTimeout(2_000) { while (output.size() == 0) delay(1) }
            // Seeing the second frame proves the first writer released the mutex.
            val next = async { rpc.call("thread/read", obj()) }
            withTimeout(2_000) { while (output.toString().lines().count { it.isNotBlank() } != 2) delay(1) }
            request.cancelAndJoin()
            server.write("{\"id\":\"1\",\"result\":{}}\n".toByteArray()); server.flush()
            // A later response fences the reader: when it is applied, the old reply
            // has already been processed, so no timing sleep is needed for the assertion.
            server.write("{\"id\":\"2\",\"result\":{}}\n".toByteArray()); server.flush()
            withTimeout(2_000) { next.await() }
            assertEquals(0, applied)
            assertEquals(1, Regex("turn/start").findAll(output.toString()).count())
        } finally { rpc.close(); server.close(); input.close() }
    }
}
