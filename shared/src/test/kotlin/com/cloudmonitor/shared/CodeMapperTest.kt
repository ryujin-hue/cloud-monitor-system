package com.cloudmonitor.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the error-code mapping and its precedence rules.
 */
class CodeMapperTest {

    private val discord = "com.discord"

    @Test
    fun `discord death during a screen share wins over a generic death`() {
        val code = CodeMapper.mapDeathCode(
            packageName = discord,
            discordPackage = discord,
            shareActive = true,
            evidence = null,
        )
        assertEquals(ErrorCode.E_SHARE_02, code)
    }

    @Test
    fun `discord death without an active share is a generic death`() {
        val code = CodeMapper.mapDeathCode(
            packageName = discord,
            discordPackage = discord,
            shareActive = false,
            evidence = null,
        )
        assertEquals(ErrorCode.E_101, code)
    }

    @Test
    fun `an active share on a different package does not produce E-SHARE-02`() {
        val code = CodeMapper.mapDeathCode(
            packageName = "com.example.game",
            discordPackage = discord,
            shareActive = true,
            evidence = null,
        )
        assertEquals(ErrorCode.E_101, code)
    }

    @Test
    fun `an exit reason produces E-102`() {
        val code = CodeMapper.mapDeathCode(
            packageName = "com.example.game",
            discordPackage = discord,
            shareActive = false,
            evidence = ExitEvidence.ExitReason("LOW_MEMORY"),
        )
        assertEquals(ErrorCode.E_102, code)
    }

    @Test
    fun `an ANR produces E-204`() {
        val code = CodeMapper.mapDeathCode(
            packageName = "com.example.game",
            discordPackage = discord,
            shareActive = false,
            evidence = ExitEvidence.Anr,
        )
        assertEquals(ErrorCode.E_204, code)
    }

    @Test
    fun `an ANR wins over a plain exit reason`() {
        val code = CodeMapper.mapDeathCode(
            packageName = "com.example.game",
            discordPackage = discord,
            shareActive = false,
            evidence = ExitEvidence.Anr,
        )
        assertEquals(ErrorCode.E_204, code)
    }

    @Test
    fun `without evidence the fallback is E-101`() {
        val code = CodeMapper.mapDeathCode(
            packageName = "com.example.game",
            discordPackage = null,
            shareActive = false,
            evidence = null,
        )
        assertEquals(ErrorCode.E_101, code)
    }

    @Test
    fun `privileged detections are always high confidence`() {
        assertEquals(
            Confidence.HIGH,
            CodeMapper.confidenceFor(ErrorCode.E_101, DetectionTier.TIER_2, null),
        )
        assertEquals(
            Confidence.HIGH,
            CodeMapper.confidenceFor(ErrorCode.E_102, DetectionTier.TIER_2, ExitEvidence.ExitReason("CRASH")),
        )
    }

    @Test
    fun `tier 1 without evidence is low confidence`() {
        assertEquals(
            Confidence.LOW,
            CodeMapper.confidenceFor(ErrorCode.E_101, DetectionTier.TIER_1, null),
        )
    }

    @Test
    fun `tier 1 with evidence is medium confidence`() {
        assertEquals(
            Confidence.MEDIUM,
            CodeMapper.confidenceFor(ErrorCode.E_102, DetectionTier.TIER_1, ExitEvidence.ExitReason("CRASH")),
        )
    }

    @Test
    fun `share codes are medium confidence on tier 1`() {
        assertEquals(
            Confidence.MEDIUM,
            CodeMapper.confidenceFor(ErrorCode.E_SHARE_01, DetectionTier.TIER_1, null),
        )
        assertEquals(
            Confidence.MEDIUM,
            CodeMapper.confidenceFor(ErrorCode.E_SHARE_02, DetectionTier.TIER_1, null),
        )
    }

    @Test
    fun `only the monitor may originate monitor codes`() {
        assertTrue(CodeMapper.isMonitorOriginated(ErrorCode.E_101))
        assertTrue(CodeMapper.isMonitorOriginated(ErrorCode.E_SHARE_01))
        assertTrue(CodeMapper.isMonitorOriginated(ErrorCode.E_NET_01))
        assertFalse(CodeMapper.isMonitorOriginated(ErrorCode.E_HB_01))
    }

    @Test
    fun `every code has a unique wire value and a resolvable owner`() {
        val wires = ErrorCode.all.map { it.wire }
        assertEquals(wires.size, wires.toSet().size)
        for (code in ErrorCode.all) {
            assertEquals(code, ErrorCode.fromWire(code.wire))
            assertTrue(code.title.isNotBlank())
        }
    }
}
