package com.coda.workbench

import com.coda.workbench.platform.PendingIntentKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PendingIntentKeysTest {
    @Test
    fun sameUuidDerivesStableCode() {
        val id = "e3f4a1b2-9c5d-4e6f-8a7b-123456789abc"
        assertEquals(PendingIntentKeys.requestCodeFor(id), PendingIntentKeys.requestCodeFor(id))
    }

    @Test
    fun codeIsAlwaysPositiveForNotificationIds() {
        listOf("a", "bb", "ccc", "00000000-0000-0000-0000-000000000000", "ffffffff-ffff-ffff-ffff-ffffffffffff")
            .forEach { id ->
                assertTrue(PendingIntentKeys.requestCodeFor(id) > 0)
            }
    }

    @Test
    fun notificationIdEqualsRequestCode() {
        val id = "handover-1"
        assertEquals(PendingIntentKeys.requestCodeFor(id), PendingIntentKeys.notificationIdFor(id))
    }
}
