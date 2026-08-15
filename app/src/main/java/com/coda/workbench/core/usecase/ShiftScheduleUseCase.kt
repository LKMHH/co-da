package com.coda.workbench.core.usecase

import com.coda.workbench.core.model.ProductionGroup
import com.coda.workbench.core.model.ShiftSlotPatch
import com.coda.workbench.data.local.CodaDatabase
import com.coda.workbench.data.repository.MonthScheduleUiState
import com.coda.workbench.data.repository.ShiftScheduleRepository
import java.time.Clock
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

/**
 * M5 排班用例（技术稿 §5 ShiftScheduleUseCase 契约的等价异常式实现）：
 * confirmMonth 确认白班起点并生成当月建议，已确认月份幂等；updateFutureSlot 只允许修改未开始班次。
 * 排班变化后触发 reconcileAll 重算 NEXT_SHIFT 与全部未来提醒（调度失败不回滚排班数据）。
 */
class ShiftScheduleUseCase(
    private val database: CodaDatabase,
    private val clock: Clock,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val notificationTrigger: ShiftScheduleNotificationTrigger = NoOpShiftScheduleNotificationTrigger,
) {
    private val repository = ShiftScheduleRepository(database, clock, zoneId)

    suspend fun confirmMonth(month: YearMonth, whiteDayGroup: ProductionGroup) {
        repository.confirmMonth(month, whiteDayGroup)
        runCatching { notificationTrigger.reconcileAll() }
    }

    suspend fun updateFutureSlot(id: String, patch: ShiftSlotPatch) {
        repository.updateFutureSlot(id, patch)
        runCatching { notificationTrigger.reconcileAll() }
    }
}

/** M5 排班只读查询（技术稿 §5 ShiftScheduleQueryUseCase.observeMonth 契约）。 */
class ShiftScheduleQueryUseCase(
    private val database: CodaDatabase,
    private val clock: Clock,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    private val repository = ShiftScheduleRepository(database, clock, zoneId)

    fun currentMonth(): YearMonth = YearMonth.now(clock.withZone(zoneId))

    fun observeCurrentMonth(): Flow<MonthScheduleUiState> =
        repository.observeMonth(currentMonth())
}
