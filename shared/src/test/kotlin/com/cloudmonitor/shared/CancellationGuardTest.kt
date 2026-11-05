package com.cloudmonitor.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class CancellationGuardTest {

    @Test
    fun `successful block returns success`() {
        val result = CancellationGuard.runCatchingPreservingCancellation { 42 }
        assertTrue(result.isSuccess)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `ordinary failure becomes a failed Result`() {
        val result = CancellationGuard.runCatchingPreservingCancellation {
            error("boom")
        }
        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test(expected = CancellationException::class)
    fun `CancellationException is rethrown instead of being swallowed`() {
        CancellationGuard.runCatchingPreservingCancellation {
            throw CancellationException("job cancelled")
        }
    }

    @Test
    fun `rethrowCancellation only rethrows cancellations`() {
        var ordinary = false
        try {
            CancellationGuard.rethrowCancellation(IllegalStateException("keep going"))
            ordinary = true
        } catch (_: Throwable) {
            // must not happen
        }
        assertTrue(ordinary)

        assertFalse(
            runCatching {
                CancellationGuard.rethrowCancellation(CancellationException("stop"))
            }.isSuccess,
        )
    }
}
