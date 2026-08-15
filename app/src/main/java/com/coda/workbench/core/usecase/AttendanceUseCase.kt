package com.coda.workbench.core.usecase

import com.coda.workbench.core.model.AttendanceInput
import com.coda.workbench.core.model.AttendancePatch
import com.coda.workbench.data.local.AttendanceEntity
import com.coda.workbench.data.local.CodaDatabase
import com.coda.workbench.data.repository.AttendanceRepository
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

/**
 * M5 出勤用例（技术稿 §5 AttendanceUseCase 契约的等价异常式实现）：
 * save 无当前出勤时自动设为当前；update 修正出勤不改写工作快照；setCurrent 明确切换；
 * ensureDefaultForDate 幂等兜底（已有当前出勤时直接复用，不按业务日期过滤）。
 */
class AttendanceUseCase(
    private val database: CodaDatabase,
    private val clock: Clock,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    private val repository = AttendanceRepository(database, clock = clock)

    suspend fun save(input: AttendanceInput): AttendanceEntity = repository.save(input, zoneId)

    suspend fun update(id: String, patch: AttendancePatch) = repository.update(id, patch)

    suspend fun setCurrent(id: String) = repository.setCurrent(id)

    suspend fun ensureDefaultForDate(date: LocalDate): AttendanceEntity =
        repository.ensureDefaultForDate(date, zoneId)
}

/** M5 出勤只读查询（技术稿 §5 AttendanceQueryUseCase 契约）。 */
class AttendanceQueryUseCase(
    private val database: CodaDatabase,
) {
    fun observeCurrent(): Flow<AttendanceEntity?> = database.attendanceDao().observeCurrent()

    fun observeForDate(date: LocalDate): Flow<List<AttendanceEntity>> =
        database.attendanceDao().observeForDate(date.toString())

    suspend fun findById(id: String): AttendanceEntity? = database.attendanceDao().findById(id)
}
