package com.cloudmonitor.domain

import com.cloudmonitor.shared.Confidence
import com.cloudmonitor.shared.DetectionTier
import com.cloudmonitor.shared.ErrorCode

/**
 * A single "something is wrong with this app" observation.
 */
data class DeathSignal(
    val packageName: String,
    val code: ErrorCode,
    val tier: DetectionTier,
    val confidence: Confidence,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Result of one detection scan.
 */
sealed interface DetectionOutcome {
    /** The app is (probably) gone. */
    data class Death(val signal: DeathSignal) : DetectionOutcome

    /** The app is back. */
    data class Recovery(val packageName: String) : DetectionOutcome
}
