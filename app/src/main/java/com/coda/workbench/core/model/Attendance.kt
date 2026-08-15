package com.coda.workbench.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

enum class AttendanceKind {
    NORMAL,
    TOP_DAY,
    TOP_NIGHT,
    CUSTOM,
}

data class Attendance(
    val id: String,
    val startAt: Instant,
    val endAt: Instant?,
    val kind: AttendanceKind,
    val isCurrent: Boolean,
    val businessDate: LocalDate,
)

sealed interface AttendanceResolution {
    data class Reused(val attendance: Attendance) : AttendanceResolution
    data class CreatedDefault(val attendance: Attendance) : AttendanceResolution
}

object AttendanceDefaults {
    fun createNormal(
        id: String,
        date: LocalDate,
        zoneId: ZoneId,
    ): Attendance {
        val start = LocalDateTime.of(date, LocalTime.of(8, 0)).atZone(zoneId).toInstant()
        val end = LocalDateTime.of(date, LocalTime.of(18, 0)).atZone(zoneId).toInstant()
        return Attendance(
            id = id,
            startAt = start,
            endAt = end,
            kind = AttendanceKind.NORMAL,
            isCurrent = true,
            businessDate = date,
        )
    }
}

enum class ProductionGroup {
    A,
    B,
}

enum class ShiftType {
    DAY,
    NIGHT,
}

data class ShiftSlotSuggestion(
    val businessDate: LocalDate,
    val group: ProductionGroup,
    val shiftType: ShiftType,
    val startAt: Instant,
    val endAt: Instant,
    val isShiftChange: Boolean,
)
