package com.atay.iz.group

import org.junit.Assert.*
import org.junit.Test

class GroupPolicyTest {
    @Test fun cadenceUsesMovementAndNeverReplaysOldFixes() {
        assertFalse(GroupPolicy.shouldPublish(now=100_000, fixAt=69_999, speed=2f, lastSentAt=0))
        assertFalse(GroupPolicy.shouldPublish(now=100_000, fixAt=106_000, speed=2f, lastSentAt=0))
        assertTrue(GroupPolicy.shouldPublish(now=100_000, fixAt=99_000, speed=2f, lastSentAt=90_000))
        assertFalse(GroupPolicy.shouldPublish(now=100_000, fixAt=99_000, speed=0f, lastSentAt=90_000))
        assertTrue(GroupPolicy.shouldPublish(now=100_000, fixAt=99_000, speed=0f, lastSentAt=70_000))
    }
    @Test fun markerAgingRetainsOfflineMembershipButHidesCoordinates() {
        assertFalse(GroupPolicy.delayed(30_000, 0))
        assertTrue(GroupPolicy.delayed(30_001, 0))
        assertTrue(GroupPolicy.visible(300_000, 0))
        assertFalse(GroupPolicy.visible(300_001, 0))
    }
    @Test fun unexpectedOrFailedResponseCannotMasqueradeAsSuccess() {
        assertThrows(Exception::class.java) { GroupWire.requireSuccess(200, "{}") }
        assertThrows(Exception::class.java) { GroupWire.requireSuccess(401, "{\"ok\":true}") }
        assertThrows(Exception::class.java) { GroupWire.requireSuccess(200, "{\"ok\":false,\"error\":\"denied\"}") }
        assertEquals(true, GroupWire.requireSuccess(200, "{\"ok\":true}").getBoolean("ok"))
    }
}
