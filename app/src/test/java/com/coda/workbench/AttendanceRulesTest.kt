package com.coda.workbench

import com.coda.workbench.core.model.Attendance
import com.coda.workbench.core.model.AttendanceKind
import com.coda.workbench.core.model.AttendanceResolution
import com.coda.workbench.core.rules.AttendanceRules
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AttendanceRulesTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun attendanceKindsMatchTheTechnicalContract() {
        assertEquals(
            setOf("NORMAL", "TOP_DAY", "TOP_NIGHT", "CUSTOM"),
            AttendanceKind.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun currentNightAttendanceIsReusedAfterMidnight() {
        val nightShift = Attendance(
            id = UUID.randomUUID().toString(),
            startAt = Instant.parse("2026-08-13T12:00:00Z"),
            endAt = Instant.parse("2026-08-14T00:00:00Z"),
            kind = AttendanceKind.TOP_NIGHT,
            isCurrent = true,
            businessDate = LocalDate.of(2026, 8, 13),
        )

        val result = AttendanceRules.resolveCurrent(
            existing = listOf(nightShift),
            now = fixedClock("2026-08-13T18:00:00Z"),
            zoneId = zoneId,
        )

        val reused = assertIs<AttendanceResolution.Reused>(result).attendance
        assertEquals(nightShift.id, reused.id)
        assertEquals(LocalDate.of(2026, 8, 13), AttendanceRules.derivedWorkDate(reused))
    }

    @Test
    fun anyCurrentAttendancePreventsAutomaticDefaultCreation() {
        val custom = Attendance(
            id = UUID.randomUUID().toString(),
            startAt = Instant.parse("2026-08-13T14:00:00Z"),
            endAt = null,
            kind = AttendanceKind.CUSTOM,
            isCurrent = true,
            businessDate = LocalDate.of(2026, 8, 13),
        )

        val result = AttendanceRules.resolveCurrent(
            existing = listOf(custom),
            now = fixedClock("2026-08-14T04:00:00Z"),
            zoneId = zoneId,
        )

        assertEquals(custom, assertIs<AttendanceResolution.Reused>(result).attendance)
    }

    @Test
    fun firstUseCreatesNormalEightToEighteenAttendanceWithUuidV4() {
        val result = AttendanceRules.resolveCurrent(
            existing = emptyList(),
            now = fixedClock("2026-08-14T03:00:00Z"),
            zoneId = zoneId,
        )

        val created = assertIs<AttendanceResolution.CreatedDefault>(result).attendance
        val id = UUID.fromString(created.id)
        assertEquals(4, id.version())
        assertEquals(AttendanceKind.NORMAL, created.kind)
        assertTrue(created.isCurrent)
        assertEquals(LocalDate.of(2026, 8, 14), created.businessDate)
        assertEquals(Instant.parse("2026-08-14T00:00:00Z"), created.startAt)
        assertEquals(Instant.parse("2026-08-14T10:00:00Z"), created.endAt)
    }

    @Test
    fun idFactoryCanBeInjectedForDeterministicTests() {
        val expectedId = "9d24a520-c3bb-4bcc-b5ed-1c63f25f74ac"
        val result = AttendanceRules.resolveCurrent(
            existing = emptyList(),
            now = fixedClock("2026-08-14T03:00:00Z"),
            zoneId = zoneId,
            idFactory = { expectedId },
        )

        assertEquals(
            expectedId,
            assertIs<AttendanceResolution.CreatedDefault>(result).attendance.id,
        )
    }

    private fun fixedClock(instant: String): Clock = Clock.fixed(Instant.parse(instant), zoneId)
}
