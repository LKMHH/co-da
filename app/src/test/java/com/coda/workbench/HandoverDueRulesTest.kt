package com.coda.workbench

import com.coda.workbench.core.rules.HandoverDueRules
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HandoverDueRulesTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")

    // 2026-08-14 12:00 +08:00
    private val now: Instant = Instant.parse("2026-08-14T04:00:00Z")

    @Test
    fun endOfTodayUsesCurrentAttendanceEndAtSnapshot() {
        val attendanceEnd = Instant.parse("2026-08-14T11:00:00Z").toEpochMilli() // 19:00 +08:00

        val dueAt = HandoverDueRules.resolveDueAt(
            dueKindName = "END_OF_TODAY",
            explicitDueAt = null,
            attendanceEndAt = attendanceEnd,
            upcomingStarts = emptyList(),
            now = now,
            zoneId = zoneId,
        )

        assertEquals(attendanceEnd, dueAt)
    }

    @Test
    fun endOfTodayFallsBackToLocalEighteenWhenNoAttendanceOrEndAt() {
        val expected = Instant.parse("2026-08-14T10:00:00Z").toEpochMilli() // 18:00 +08:00

        assertEquals(expected, HandoverDueRules.resolveDueAt("END_OF_TODAY", null, null, emptyList(), now, zoneId))
        assertEquals(expected, HandoverDueRules.resolveDueAt("END_OF_TODAY", null, null, listOf(1L), now, zoneId))
    }

    @Test
    fun nextShiftPicksEarliestUpcomingSlotStart() {
        val next = Instant.parse("2026-08-14T12:00:00Z").toEpochMilli() // 20:00 +08:00
        val later = Instant.parse("2026-08-15T00:00:00Z").toEpochMilli()

        val dueAt = HandoverDueRules.resolveDueAt("NEXT_SHIFT", null, null, listOf(later, next), now, zoneId)

        assertEquals(next, dueAt)
    }

    @Test
    fun nextShiftWithoutConfirmedSlotsReturnsNullButKeepsKind() {
        assertNull(HandoverDueRules.resolveDueAt("NEXT_SHIFT", null, null, emptyList(), now, zoneId))
    }

    @Test
    fun noneReturnsNullEvenIfDueAtWasProvided() {
        assertNull(HandoverDueRules.resolveDueAt("NONE", 42L, null, emptyList(), now, zoneId))
    }

    @Test
    fun specificPassesThroughExplicitDueAt() {
        assertEquals(42L, HandoverDueRules.resolveDueAt("SPECIFIC", 42L, null, emptyList(), now, zoneId))
    }
}
