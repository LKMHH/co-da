package com.coda.workbench.core.rules

import com.coda.workbench.core.model.Attendance
import com.coda.workbench.core.model.AttendanceDefaults
import com.coda.workbench.core.model.AttendanceResolution
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

object AttendanceRules {
    fun resolveCurrent(
        existing: List<Attendance>,
        now: Clock,
        zoneId: ZoneId,
        idFactory: () -> String = { UUID.randomUUID().toString() },
    ): AttendanceResolution {
        existing.firstOrNull { it.isCurrent }?.let { return AttendanceResolution.Reused(it) }

        val date = LocalDate.now(now.withZone(zoneId))
        return AttendanceResolution.CreatedDefault(
            AttendanceDefaults.createNormal(idFactory(), date, zoneId),
        )
    }

    /** M5：ensureDefaultForDate 的兜底默认出勤，按指定日期创建 NORMAL 08:00-18:00（不按当前日期猜测）。 */
    fun resolveDefaultForDate(
        date: LocalDate,
        zoneId: ZoneId,
        idFactory: () -> String = { UUID.randomUUID().toString() },
    ): Attendance = AttendanceDefaults.createNormal(idFactory(), date, zoneId)

    /** M5：出勤/班次时段校验，开始必须早于结束。 */
    fun validateRange(startAt: Instant, endAt: Instant) {
        require(startAt.isBefore(endAt)) { "开始时间必须早于结束时间" }
    }

    fun derivedWorkDate(attendance: Attendance): LocalDate = attendance.businessDate
}
