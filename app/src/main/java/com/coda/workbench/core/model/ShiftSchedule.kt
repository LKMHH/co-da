package com.coda.workbench.core.model

import java.time.Instant
import java.time.LocalDate

/** M5 出勤修正：新增/修正出勤时段的输入。 */
data class AttendanceInput(
    val businessDate: LocalDate,
    val kind: AttendanceKind,
    val startAt: Instant,
    val endAt: Instant,
    val productionGroup: ProductionGroup?,
)

/** M5 出勤修正：修正既有出勤的补丁；班组为空表示清空。 */
data class AttendancePatch(
    val kind: AttendanceKind,
    val startAt: Instant,
    val endAt: Instant,
    val productionGroup: ProductionGroup?,
)

/** M5 排班修正：修改未来班次的补丁，全部字段由表单提供。 */
data class ShiftSlotPatch(
    val group: ProductionGroup,
    val shiftType: ShiftType,
    val startAt: Instant,
    val endAt: Instant,
)
