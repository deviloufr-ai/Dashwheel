package com.openauto.dash.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class IdleGuardTest {

    @Test
    fun quietLinkIsClosedOnce() {
        val closed = CountDownLatch(1)
        val calls = AtomicInteger()
        IdleGuard(timeoutMs = 60, onIdle = { calls.incrementAndGet(); closed.countDown() }, tickMs = 10).start()
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        Thread.sleep(150)
        assertEquals(1, calls.get())
    }

    @Test
    fun hearingFromTheOtherEndKeepsItOpen() {
        val calls = AtomicInteger()
        val guard = IdleGuard(timeoutMs = 120, onIdle = { calls.incrementAndGet() }, tickMs = 10).start()
        repeat(15) {
            Thread.sleep(30)
            guard.kick()
        }
        assertEquals(0, calls.get())
        guard.stop()
    }

    @Test
    fun stoppedGuardNeverCloses() {
        val closed = CountDownLatch(1)
        val guard = IdleGuard(timeoutMs = 40, onIdle = { closed.countDown() }, tickMs = 10).start()
        guard.stop()
        assertFalse(closed.await(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun timeoutCanBeChangedWhileRunning() {
        val closed = CountDownLatch(1)
        val guard = IdleGuard(timeoutMs = 60_000, onIdle = { closed.countDown() }, tickMs = 10).start()
        assertFalse(closed.await(100, TimeUnit.MILLISECONDS))
        guard.timeoutMs = 20
        assertTrue(closed.await(2, TimeUnit.SECONDS))
    }
}
