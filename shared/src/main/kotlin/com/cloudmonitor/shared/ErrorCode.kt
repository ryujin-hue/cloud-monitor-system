package com.cloudmonitor.shared

/**
 * How serious an [ErrorCode] is. Used by both apps to pick colours, copy and push priority.
 */
enum class Severity {
    INFO,
    WARNING,
    CRITICAL,
}

/**
 * Which component is allowed to originate an event carrying a given code.
 *
 * The backend rejects events whose code is not owned by the monitor, so a compromised or
 * buggy cloud phone cannot forge backend-originated codes (notably `E-HB-01`).
 */
enum class CodeOwner {
    MONITOR,
    BACKEND,
}

/**
 * The canonical, single source of truth for every error code in the system.
 *
 * These values are used:
 *  * by Cloud Monitor when it originates an event,
 *  * by the Cloudflare backend when it validates / routes an event,
 *  * by Cloud Alert when it renders an alert.
 *
 * The TypeScript mirror lives in `cloudflare/src/types.ts` and a parity test
 * (`cloudflare/test/parity.test.ts`) fails the build if the two lists ever drift apart.
 *
 * NEVER use the wire values as string literals anywhere else.
 */
enum class ErrorCode(
    val wire: String,
    val title: String,
    val severity: Severity,
    val owner: CodeOwner,
) {
    /**
     * The target app's process disappeared and we have no exit-reason or ANR evidence.
     * This is the low-confidence fallback used when only usage statistics are available.
     */
    E_101(
        wire = "E-101",
        title = "App process died",
        severity = Severity.WARNING,
        owner = CodeOwner.MONITOR,
    ),

    /**
     * The target app's process was terminated with a known exit reason (crash, low memory,
     * user request, permission revocation, ...). Requires privileged evidence.
     */
    E_102(
        wire = "E-102",
        title = "App terminated by the system",
        severity = Severity.WARNING,
        owner = CodeOwner.MONITOR,
    ),

    /**
     * The target app is not responding (ANR). Requires ANR evidence: either the OS
     * "isn't responding" dialog observed through an AccessibilityService, or an ANR state
     * seen in a privileged process dump.
     */
    E_204(
        wire = "E-204",
        title = "App not responding (ANR)",
        severity = Severity.CRITICAL,
        owner = CodeOwner.MONITOR,
    ),

    /**
     * Discord's screen-share notification was removed, i.e. the screen share ended or was
     * stopped. Emitted after a 3-5 s debounce so that re-posted notifications do not
     * produce false positives.
     */
    E_SHARE_01(
        wire = "E-SHARE-01",
        title = "Discord screen share stopped",
        severity = Severity.CRITICAL,
        owner = CodeOwner.MONITOR,
    ),

    /**
     * Discord's process died while a screen share was active. Takes precedence over `E-101`:
     * a Discord death is never reported as a generic death while sharing.
     */
    E_SHARE_02(
        wire = "E-SHARE-02",
        title = "Discord died during screen share",
        severity = Severity.CRITICAL,
        owner = CodeOwner.MONITOR,
    ),

    /**
     * The cloud phone stopped sending heartbeats. Originated by the backend cron worker,
     * never by the monitor.
     */
    E_HB_01(
        wire = "E-HB-01",
        title = "Cloud phone stopped reporting",
        severity = Severity.CRITICAL,
        owner = CodeOwner.BACKEND,
    ),

    /**
     * The cloud phone lost network connectivity. Originated locally by Cloud Monitor,
     * queued in the offline queue and delivered later with its ORIGINAL timestamp.
     * Never opens an incident and never triggers a push.
     */
    E_NET_01(
        wire = "E-NET-01",
        title = "Network unavailable",
        severity = Severity.INFO,
        owner = CodeOwner.MONITOR,
    ),
    ;

    companion object {
        private val byWire: Map<String, ErrorCode> = entries.associateBy { it.wire }

        /** Lookup by the wire value, or `null` when the code is unknown. */
        fun fromWire(wire: String): ErrorCode? = byWire[wire]

        /** All codes, in declaration order. */
        val all: List<ErrorCode> = entries.toList()

        /** Codes a Cloud Monitor is allowed to originate. */
        val monitorOwned: List<ErrorCode> = all.filter { it.owner == CodeOwner.MONITOR }

        /** Codes the backend cron worker is allowed to originate. */
        val backendOwned: List<ErrorCode> = all.filter { it.owner == CodeOwner.BACKEND }
    }
}
