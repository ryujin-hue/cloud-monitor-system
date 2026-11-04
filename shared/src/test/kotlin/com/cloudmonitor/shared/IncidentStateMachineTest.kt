package com.cloudmonitor.shared

import com.cloudmonitor.shared.dto.EventDto
import com.cloudmonitor.shared.dto.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the incident state machine.
 *
 * These encode the rules that the backend relies on:
 *  * one open incident per (device, package, code),
 *  * DOWN opens, RECOVERED closes,
 *  * a RECOVERED for an incident that was never opened is ignored.
 */
class IncidentStateMachineTest {

    private fun down(
        occurredAt: Long = 1_000L,
        code: String = ErrorCode.E_101.wire,
        packageName: String? = "com.example.game",
        deviceId: String = "device-1",
    ) = EventDto(
        eventId = "evt-$occurredAt-$code",
        deviceId = deviceId,
        type = EventType.DOWN,
        code = code,
        packageName = packageName,
        detection = DetectionTier.TIER_1.wire,
        confidence = Confidence.LOW.wire,
        occurredAt = occurredAt,
        seq = occurredAt,
    )

    private fun recovered(
        occurredAt: Long = 2_000L,
        code: String = ErrorCode.E_101.wire,
        packageName: String? = "com.example.game",
        deviceId: String = "device-1",
    ) = down(occurredAt, code, packageName, deviceId).copy(
        eventId = "rec-$occurredAt-$code",
        type = EventType.RECOVERED,
    )

    @Test
    fun `incident key is stable and distinguishes packages`() {
        assertEquals(
            "device-1|com.example.game|E-101",
            IncidentStateMachine.incidentKey("device-1", "com.example.game", "E-101"),
        )
        assertEquals(
            "device-1|-|E-HB-01",
            IncidentStateMachine.incidentKey("device-1", null, "E-HB-01"),
        )
        assertEquals(
            "device-2|com.example.game|E-101",
            IncidentStateMachine.incidentKey("device-2", "com.example.game", "E-101"),
        )
    }

    @Test
    fun `first DOWN opens an incident`() {
        val (snapshot, transition) = IncidentStateMachine.reduce(null, down())

        assertTrue(transition is IncidentTransition.Opened)
        assertTrue(transition.shouldPush)
        assertEquals(IncidentState.OPEN, snapshot!!.state)
        assertEquals(1_000L, snapshot.openedAt)
        assertEquals(1, snapshot.eventCount)
        assertNull(snapshot.closedAt)
    }

    @Test
    fun `second DOWN keeps the incident open and does not push again`() {
        val (opened, _) = IncidentStateMachine.reduce(null, down(occurredAt = 1_000L))
        val (snapshot, transition) = IncidentStateMachine.reduce(opened, down(occurredAt = 1_500L))

        assertTrue(transition is IncidentTransition.DuplicateDown)
        assertFalse(transition.shouldPush)
        assertEquals(IncidentState.OPEN, snapshot!!.state)
        assertEquals(1_000L, snapshot.openedAt)
        assertEquals(1_500L, snapshot.lastEventAt)
        assertEquals(2, snapshot.eventCount)
    }

    @Test
    fun `RECOVERED closes an open incident`() {
        val (opened, _) = IncidentStateMachine.reduce(null, down())
        val (snapshot, transition) = IncidentStateMachine.reduce(opened, recovered())

        assertTrue(transition is IncidentTransition.Recovered)
        assertTrue(transition.shouldPush)
        assertEquals(IncidentState.CLOSED, snapshot!!.state)
        assertEquals(2_000L, snapshot.closedAt)
        assertEquals(2, snapshot.eventCount)
    }

    @Test
    fun `RECOVERED without a prior DOWN is ignored`() {
        val (snapshot, transition) = IncidentStateMachine.reduce(null, recovered())

        assertTrue(transition is IncidentTransition.Unchanged)
        assertFalse(transition.shouldPush)
        assertNull(snapshot)
    }

    @Test
    fun `duplicate RECOVERED is ignored`() {
        val (opened, _) = IncidentStateMachine.reduce(null, down())
        val (closed, _) = IncidentStateMachine.reduce(opened, recovered())
        val (snapshot, transition) = IncidentStateMachine.reduce(closed, recovered(occurredAt = 3_000L))

        assertTrue(transition is IncidentTransition.Unchanged)
        assertFalse(transition.shouldPush)
        assertEquals(2_000L, snapshot!!.closedAt)
    }

    @Test
    fun `HEARTBEAT never changes incident state`() {
        val (opened, _) = IncidentStateMachine.reduce(null, down())
        val heartbeat = down(occurredAt = 5_000L).copy(type = EventType.HEARTBEAT)

        val (snapshot, transition) = IncidentStateMachine.reduce(opened, heartbeat)

        assertTrue(transition is IncidentTransition.Unchanged)
        assertFalse(transition.shouldPush)
        assertEquals(1, snapshot!!.eventCount)
    }

    @Test
    fun `a DOWN after a RECOVERED opens a NEW incident`() {
        val (opened, _) = IncidentStateMachine.reduce(null, down(occurredAt = 1_000L))
        val (closed, _) = IncidentStateMachine.reduce(opened, recovered(occurredAt = 2_000L))
        val (snapshot, transition) = IncidentStateMachine.reduce(closed, down(occurredAt = 3_000L))

        assertTrue(transition is IncidentTransition.Opened)
        assertTrue(transition.shouldPush)
        assertEquals(IncidentState.OPEN, snapshot!!.state)
        assertEquals(3_000L, snapshot.openedAt)
        assertNull(snapshot.closedAt)
        assertEquals(1, snapshot.eventCount)
    }

    @Test
    fun `different packages and codes are independent incidents`() {
        val (a, _) = IncidentStateMachine.reduce(null, down(code = "E-101"))
        val (b, _) = IncidentStateMachine.reduce(null, down(code = "E-204"))
        val (c, _) = IncidentStateMachine.reduce(null, down(packageName = "com.other.app"))

        assertEquals("device-1|com.example.game|E-101", a!!.key)
        assertEquals("device-1|com.example.game|E-204", b!!.key)
        assertEquals("device-1|com.other.app|E-101", c!!.key)
    }

    @Test
    fun `reduceAll folds a chronological event list`() {
        val events = listOf(
            down(occurredAt = 3_000L),
            down(occurredAt = 1_000L),
            recovered(occurredAt = 2_000L),
        )

        val (snapshot, transitions) = IncidentStateMachine.reduceAll(null, events)

        assertEquals(3, transitions.size)
        assertTrue(transitions[0] is IncidentTransition.Opened)
        assertTrue(transitions[1] is IncidentTransition.Recovered)
        assertTrue(transitions[2] is IncidentTransition.Opened)
        assertEquals(IncidentState.OPEN, snapshot!!.state)
        assertEquals(3_000L, snapshot.openedAt)
    }
}
