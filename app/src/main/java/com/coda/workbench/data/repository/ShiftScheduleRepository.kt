package com.coda.workbench.data.repository

import androidx.room.withTransaction
import com.coda.workbench.core.model.ProductionGroup
import com.coda.workbench.core.model.ShiftSlotPatch
import com.coda.workbench.core.rules.AttendanceRules
import com.coda.workbench.core.rules.ShiftScheduleRules
import com.coda.workbench.data.local.CodaDatabase
import com.coda.workbench.data.local.MonthlyShiftPlanEntity
import com.coda.workbench.data.local.ShiftSlotEntity
import java.time.Clock
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class MonthScheduleUiState(
    val month: String,
    val confirmedAt: Long?,
    val whiteDayGroup: ProductionGroup?,
    val slots: List<ShiftSlotEntity>,
)

class ShiftScheduleRepository(
    private val database: CodaDatabase,
    private val clock: Clock,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    /**
     * 确认本月白班起点并生成班次建议。已确认的月份幂等返回，不重生成、不覆盖用户手动修正。
     * 生成普通日、15 号转班和月底转班建议（ShiftScheduleRules 已锁规则）。
     */
    suspend fun confirmMonth(
        month: YearMonth,
        whiteDayGroup: ProductionGroup,
    ): MonthlyShiftPlanEntity {
        val monthStr = month.toString()
        val existing = database.monthlyShiftPlanDao().findByMonth(monthStr)
        if (existing?.confirmedAt != null) return existing
        val now = clock.millis()
        database.withTransaction {
            val planId = existing?.id ?: UUID.randomUUID().toString()
            val groupADayStart = if (whiteDayGroup == ProductionGroup.A) 1 else 16
            val groupBDayStart = if (whiteDayGroup == ProductionGroup.A) 16 else 1
            if (existing == null) {
                database.monthlyShiftPlanDao().insert(
                    MonthlyShiftPlanEntity(
                        id = planId,
                        businessMonth = monthStr,
                        groupADayStart = groupADayStart,
                        groupBDayStart = groupBDayStart,
                        confirmedAt = now,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            } else {
                database.monthlyShiftPlanDao().updateConfirmed(
                    businessMonth = monthStr,
                    groupADayStart = groupADayStart,
                    groupBDayStart = groupBDayStart,
                    confirmedAt = now,
                    updatedAt = now,
                )
            }
            ShiftScheduleRules.generateMonth(month, whiteDayGroup, zoneId).forEach { suggestion ->
                database.shiftSlotDao().insert(
                    ShiftSlotEntity(
                        id = UUID.randomUUID().toString(),
                        planId = planId,
                        businessDate = suggestion.businessDate.toString(),
                        group = suggestion.group.name,
                        shiftType = suggestion.shiftType.name,
                        startAt = suggestion.startAt.toEpochMilli(),
                        endAt = suggestion.endAt.toEpochMilli(),
                        isShiftChange = suggestion.isShiftChange,
                        source = "SUGGESTED",
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
        }
        return database.monthlyShiftPlanDao().findByMonth(monthStr)!!
    }

    /** 修改未来班次：只允许未开始的班次，落 source = MANUAL，不改写任何历史出勤/工作快照。 */
    suspend fun updateFutureSlot(id: String, patch: ShiftSlotPatch) {
        val slot = database.shiftSlotDao().findById(id) ?: error("班次不存在")
        check(slot.startAt > clock.millis()) { "已开始的班次不能修改" }
        AttendanceRules.validateRange(patch.startAt, patch.endAt)
        val clash = database.shiftSlotDao().forDateAndType(slot.businessDate, patch.shiftType.name)
            .any { it.group == patch.group.name && it.id != id }
        check(!clash) { "该日期同一班组已存在相同班别" }
        database.shiftSlotDao().update(
            slot.copy(
                group = patch.group.name,
                shiftType = patch.shiftType.name,
                startAt = patch.startAt.toEpochMilli(),
                endAt = patch.endAt.toEpochMilli(),
                source = "MANUAL",
                updatedAt = clock.millis(),
            ),
        )
    }

    fun observeMonth(month: YearMonth): Flow<MonthScheduleUiState> {
        val monthStr = month.toString()
        return combine(
            database.monthlyShiftPlanDao().observeByMonth(monthStr),
            database.shiftSlotDao().observeByMonthPrefix("$monthStr-"),
        ) { plan, slots ->
            MonthScheduleUiState(
                month = monthStr,
                confirmedAt = plan?.confirmedAt,
                whiteDayGroup = plan?.let { if (it.groupADayStart == 1) ProductionGroup.A else ProductionGroup.B },
                slots = slots,
            )
        }
    }
}
