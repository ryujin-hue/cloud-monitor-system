package com.cloudmonitor.shared

import java.util.concurrent.CancellationException

/**
 * Coroutine-safe replacements for `runCatching {}`.
 *
 * `kotlin.runCatching` catches **every** `Throwable`, including `CancellationException`.
 * When it is used inside a long-running loop or an HTTP client, a cancelled coroutine is
 * silently turned into a logged error (or a `NetworkError` result) and the loop keeps
 * running instead of unwinding. This breaks structured cancellation, leaks jobs and can
 * keep a foreground service alive after it was stopped.
 *
 * On the JVM and on Android, `kotlinx.coroutines.CancellationException` is a typealias of
 * [java.util.concurrent.CancellationException], so this pure-Kotlin helper works for both
 * modules without adding a coroutines dependency to `:shared`.
 */
object CancellationGuard {

    /**
     * Run [block] and wrap a failure in [Result], but always rethrow a cancellation so the
     * coroutine unwinds normally.
     */
    inline fun <T> runCatchingPreservingCancellation(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }

    /** Rethrow [error] when it is a cancellation. Use as the first line of a `catch`. */
    fun rethrowCancellation(error: Throwable) {
        if (error is CancellationException) throw error
    }
}
