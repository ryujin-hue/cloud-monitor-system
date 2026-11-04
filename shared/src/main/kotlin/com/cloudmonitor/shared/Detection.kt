package com.cloudmonitor.shared

/**
 * Which detection tier produced an event.
 *
 * Tier 1 must work on a completely stock, non-rooted phone. Tier 2 uses elevated privileges
 * (root or Shizuku) and therefore yields much stronger evidence.
 */
enum class DetectionTier(val wire: String) {
    /** No special privileges required: usage statistics + notification observation. */
    TIER_1("tier1"),

    /** Privileged: `pidof` / `dumpsys activity processes` / exit-info dumps. */
    TIER_2("tier2"),

    /** Detected server-side by the backend cron worker. */
    TIER_BACKEND("backend"),
    ;

    companion object {
        fun fromWire(wire: String): DetectionTier? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * How confident we are that the event reflects reality.
 */
enum class Confidence(val wire: String) {
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low"),
    ;

    companion object {
        fun fromWire(wire: String): Confidence? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * Evidence that upgrades a plain "the process disappeared" observation into a more specific
 * code. Every [ErrorCode.E_102] and [ErrorCode.E_204] event MUST carry such evidence.
 */
sealed interface ExitEvidence {

    /** A short, human readable description of the evidence. */
    val reason: String

    /**
     * A concrete exit reason obtained from a privileged dump
     * (`dumpsys activity exit-info <package>`), e.g. `CRASH`, `LOW_MEMORY`, `USER_REQUESTED`.
     */
    data class ExitReason(override val reason: String) : ExitEvidence

    /**
     * The OS "isn't responding" dialog was observed for this package (AccessibilityService),
     * or the process was seen in an ANR state in a privileged dump.
     */
    data object Anr : ExitEvidence {
        override val reason: String = "ANR"
    }
}

/**
 * Maps raw detection signals onto the canonical error codes.
 *
 * This is the ONLY place where the precedence rules live, so both the Android app and the
 * unit tests agree on them.
 */
object CodeMapper {

    /**
     * Resolve the error code for a detected death.
     *
     * Precedence, highest first:
     *  1. Discord died while a screen share was active -> `E-SHARE-02` (never `E-101`).
     *  2. ANR evidence                               -> `E-204`.
     *  3. Exit-reason evidence                        -> `E-102`.
     *  4. Nothing else                                -> `E-101` (low confidence fallback).
     */
    fun mapDeathCode(
        packageName: String,
        discordPackage: String?,
        shareActive: Boolean,
        evidence: ExitEvidence?,
    ): ErrorCode {
        if (shareActive && discordPackage != null && packageName == discordPackage) {
            return ErrorCode.E_SHARE_02
        }
        return when (evidence) {
            is ExitEvidence.Anr -> ErrorCode.E_204
            is ExitEvidence.ExitReason -> ErrorCode.E_102
            null -> ErrorCode.E_101
        }
    }

    /**
     * Confidence for a detection.
     *
     * Privileged (tier 2) evidence is always high confidence. On tier 1 we only ever reach
     * medium confidence, except for the plain "process disappeared" case which stays low.
     */
    fun confidenceFor(
        code: ErrorCode,
        tier: DetectionTier,
        evidence: ExitEvidence?,
    ): Confidence = when {
        tier == DetectionTier.TIER_2 -> Confidence.HIGH
        code == ErrorCode.E_SHARE_01 || code == ErrorCode.E_SHARE_02 -> Confidence.MEDIUM
        evidence != null -> Confidence.MEDIUM
        else -> Confidence.LOW
    }

    /**
     * True when the code may be originated by a Cloud Monitor. The backend enforces this too.
     */
    fun isMonitorOriginated(code: ErrorCode): Boolean = code.owner == CodeOwner.MONITOR
}
