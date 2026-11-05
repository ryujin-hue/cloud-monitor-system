package com.cloudmonitor.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM checks for the parts of root detection that do not need an Android device.
 * Device/emulator-only checks (su prompt, SELinux, uid=0 under Android) are recorded in
 * docs/TROUBLESHOOTING.md and the README testing status instead of being faked here.
 */
class RootProcessProbeTest {

    @Test
    fun `uid=0 is recognised only for the real root user`() {
        assertTrue(RootProcessProbe.isUidZero("uid=0(root) gid=0(root) groups=0(root)"))
        assertTrue(RootProcessProbe.isUidZero("context=u:r:shell:s0\nuid=0(root) gid=0(root)"))
        assertTrue(RootProcessProbe.isUidZero("uid=0"))
        assertFalse(RootProcessProbe.isUidZero("uid=10123(u0_a123) gid=10123(u0_a123)"))
        assertFalse(RootProcessProbe.isUidZero("uid=0123(u0_a123)"))
        assertFalse(RootProcessProbe.isUidZero("permission denied"))
        assertFalse(RootProcessProbe.isUidZero(""))
    }

    @Test
    fun `reading isAvailable never performs detection`() {
        val probe = RootProcessProbe()
        // A fresh probe must report "unknown / no privileges" without starting a shell.
        assertFalse(probe.isAvailable)
        assertEquals(RootStatus(), probe.rootStatus)
        assertEquals("not checked yet", probe.rootStatus.detail)
    }

    @Test
    fun `detection is explicit, suspending and self-consistent`() {
        val probe = RootProcessProbe()
        val status = runBlocking { probe.refreshRootStatus() }

        assertTrue(status.detail.isNotBlank())
        assertEquals(status.rootGranted, probe.isAvailable)
        assertEquals(status, probe.rootStatus)
        // A su binary alone is never enough: rootGranted must come from an actual uid=0.
        if (status.suBinaryFound && !status.rootGranted) {
            assertTrue(
                status.detail.contains("did not grant", ignoreCase = true) ||
                    status.detail.contains("no output", ignoreCase = true) ||
                    status.detail.contains("unprivileged", ignoreCase = true),
            )
        }
    }
}
