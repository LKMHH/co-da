package com.coda.workbench.data.repository

import androidx.room.withTransaction
import com.coda.workbench.core.model.Attendance
import com.coda.workbench.core.model.AttendanceInput
import com.coda.workbench.core.model.AttendanceKind
import com.coda.workbench.core.model.AttendancePatch
import com.coda.workbench.core.model.ProductionGroup
import com.coda.workbench.core.rules.AttendanceRules
import com.coda.workbench.data.local.AttendanceDao
import com.coda.workbench.data.local.AttendanceEntity
import com.coda.workbench.data.local.CodaDatabase
import com.coda.workbench.data.local.ShiftSlotEntity
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.flow.Flow

class AttendanceRepository(
    private val database: CodaDatabase,
    private val dao: AttendanceDao = database.attendanceDao(),
    private val clock: Clock,
) {
    suspend fun ensureCurrent(
        zoneId: ZoneId,
        idFactory: () -> String = { UUID.randomUUID().toString() },
    ): Attendance = ensureCurrentEntity(zoneId, idFactory).toDomain()

    suspend fun ensureCurrentEntity(
        zoneId: ZoneId,
        idFactory: () -> String = { UUID.randomUUID().toString() },
    ): AttendanceEntity {
        val current = dao.findCurrent()
        if (current != null) return current

        val resolution = AttendanceRules.resolveCurrent(
            existing = emptyList(),
            now = clock,
            zoneId = zoneId,
            idFactory = idFactory,
        )
        val attendance = (resolution as com.coda.workbench.core.model.AttendanceResolution.CreatedDefault)
            .attendance
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE
        database.withTransaction {
            if (dao.findCurrent() == null) {
                dao.insert(attendance.toEntity(formatter))
            }
        }
        return dao.findCurrent() ?: attendance.toEntity(formatter)
    }

    // ---- M5 出勤修正 ----

    /** 幂等兜底：已有任何当前出勤（含前一日跨午夜未结束的夜班）直接复用；否则按传入日期创建并设为当前。 */
    suspend fun ensureDefaultForDate(
        date: LocalDate,
        zoneId: ZoneId,
        idFactory: () -> String = { UUID.randomUUID().toString() },
    ): AttendanceEntity {
        dao.findCurrent()?.let { return it }
        val attendance = AttendanceRules.resolveDefaultForDate(date, zoneId, idFactory)
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE
        database.withTransaction {
            if (dao.findCurrent() == null) {
                dao.insert(attendance.toEntity(formatter))
                dao.setCurrent(attendance.id, clock.millis())
            }
        }
        return dao.findCurrent() ?: attendance.toEntity(formatter)
    }

    /** 保存新出勤；本机没有任何当前出勤时自动设为当前，已有当前出勤时不隐式切换。 */
    suspend fun save(
        input: AttendanceInput,
        zoneId: ZoneId,
        idFactory: () -> String = { UUID.randomUUID().toString() },
    ): AttendanceEntity {
        AttendanceRules.validateRange(input.startAt, input.endAt)
        val now = clock.millis()
        val dateStr = input.businessDate.toString()
        val matched = matchShiftSlot(dateStr, input.productionGroup, input.kind)
        val entity = AttendanceEntity(
            id = idFactory(),
            businessDate = dateStr,
            kind = input.kind.name,
            startAt = input.startAt.toEpochMilli(),
            endAt = input.endAt.toEpochMilli(),
            productionGroup = input.productionGroup?.name,
            shiftId = matched?.id,
            shiftBusinessDate = matched?.businessDate,
            shiftType = matched?.shiftType,
            shiftStartAt = matched?.startAt,
            shiftEndAt = matched?.endAt,
            isShiftChange = matched?.isShiftChange ?: false,
            isCurrent = false,
            createdAt = now,
            updatedAt = now,
        )
        database.withTransaction {
            dao.insert(entity)
            if (dao.countCurrent() == 0) {
                dao.setCurrent(entity.id, now)
            }
        }
        return dao.findById(entity.id) ?: entity
    }

    /** 修正出勤；不触碰 isCurrent，也不改写任何已保存工作记录快照。 */
    suspend fun update(id: String, patch: AttendancePatch) {
        val existing = dao.findById(id) ?: error("出勤记录不存在")
        AttendanceRules.validateRange(patch.startAt, patch.endAt)
        val matched = matchShiftSlot(existing.businessDate, patch.productionGroup, patch.kind)
        dao.updateCore(
            id = id,
            businessDate = existing.businessDate,
            kind = patch.kind.name,
            startAt = patch.startAt.toEpochMilli(),
            endAt = patch.endAt.toEpochMilli(),
            productionGroup = patch.productionGroup?.name,
            shiftId = matched?.id,
            shiftBusinessDate = matched?.businessDate,
            shiftType = matched?.shiftType,
            shiftStartAt = matched?.startAt,
            shiftEndAt = matched?.endAt,
            isShiftChange = matched?.isShiftChange ?: false,
            updatedAt = clock.millis(),
        )
    }

    /** 明确把目标出勤设为当前；事务内先清后设，由部分唯一索引兜底。 */
    suspend fun setCurrent(id: String) {
        dao.findById(id) ?: error("出勤记录不存在")
        database.withTransaction {
            dao.clearCurrent(clock.millis())
            dao.setCurrent(id, clock.millis())
        }
    }

    suspend fun findById(id: String): AttendanceEntity? = dao.findById(id)

    fun observeCurrent(): Flow<AttendanceEntity?> = dao.observeCurrent()

    fun observeForDate(date: LocalDate): Flow<List<AttendanceEntity>> =
        dao.observeForDate(date.toString())

    /** 顶白/顶夜班匹配当日已确认排班中的同班组同班别班次，写入班次快照；未匹配或普通班保持为空，不猜测。 */
    private suspend fun matchShiftSlot(
        businessDate: String,
        group: ProductionGroup?,
        kind: AttendanceKind,
    ): ShiftSlotEntity? {
        if (group == null) return null
        val shiftType = when (kind) {
            AttendanceKind.TOP_DAY -> "DAY"
            AttendanceKind.TOP_NIGHT -> "NIGHT"
            else -> return null
        }
        return database.shiftSlotDao().forDateAndType(businessDate, shiftType)
            .firstOrNull { it.group == group.name }
    }

    private fun AttendanceEntity.toDomain(): Attendance =
        Attendance(
            id = id,
            startAt = java.time.Instant.ofEpochMilli(startAt),
            endAt = endAt?.let(java.time.Instant::ofEpochMilli),
            kind = AttendanceKind.valueOf(kind),
            isCurrent = isCurrent,
            businessDate = LocalDate.parse(businessDate),
        )

    private fun Attendance.toEntity(formatter: DateTimeFormatter): AttendanceEntity =
        AttendanceEntity(
            id = id,
            businessDate = businessDate.format(formatter),
            kind = kind.name,
            startAt = startAt.toEpochMilli(),
            endAt = endAt?.toEpochMilli(),
            productionGroup = null,
            shiftId = null,
            shiftBusinessDate = null,
            shiftType = null,
            shiftStartAt = null,
            shiftEndAt = null,
            isShiftChange = false,
            isCurrent = isCurrent,
            createdAt = clock.millis(),
            updatedAt = clock.millis(),
        )
}
