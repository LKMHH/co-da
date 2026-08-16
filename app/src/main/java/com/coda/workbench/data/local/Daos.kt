package com.coda.workbench.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(device: DeviceEntity)

    @Query("SELECT * FROM device WHERE id = :id")
    suspend fun findById(id: String): DeviceEntity?

    @Query("SELECT * FROM device WHERE normalizedName = :normalizedName LIMIT 1")
    suspend fun findByNormalizedName(normalizedName: String): DeviceEntity?

    @Query("SELECT * FROM device WHERE isActive = 1 ORDER BY normalizedName")
    fun observeActive(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM device WHERE isActive = 1 ORDER BY updatedAt DESC LIMIT 5")
    fun observeRecent(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM device ORDER BY isActive DESC, normalizedName ASC")
    fun observeAll(): Flow<List<DeviceEntity>>

    @Query("UPDATE device SET name = :name, normalizedName = :normalizedName, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateName(id: String, name: String, normalizedName: String, updatedAt: Long)

    @Query("UPDATE device SET isActive = :active, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setActive(id: String, active: Boolean, updatedAt: Long)

    // ---- M6 搜索 ----
    /** 设备标准名或别名（规范化字段）命中关键词的设备 id 列表。 */
    @Query(
        "SELECT DISTINCT device.id FROM device LEFT JOIN device_alias ON device_alias.deviceId = device.id " +
            "WHERE device.name LIKE :term ESCAPE '\\' OR device.normalizedName LIKE :term ESCAPE '\\' " +
            "OR device_alias.alias LIKE :term ESCAPE '\\' OR device_alias.normalizedAlias LIKE :term ESCAPE '\\'",
    )
    suspend fun idsMatching(term: String): List<String>

    // ---- M7 备份恢复 ----
    @Query("SELECT * FROM device")
    suspend fun all(): List<DeviceEntity>

    @Query("DELETE FROM device")
    suspend fun clear()
}

@Dao
interface DeviceAliasDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(alias: DeviceAliasEntity)

    @Query("SELECT * FROM device_alias WHERE deviceId = :deviceId ORDER BY normalizedAlias")
    fun observeForDevice(deviceId: String): Flow<List<DeviceAliasEntity>>

    @Query("DELETE FROM device_alias WHERE deviceId = :deviceId AND alias = :alias")
    suspend fun deleteByValue(deviceId: String, alias: String)

    // ---- M7 备份恢复 ----
    @Query("SELECT * FROM device_alias")
    suspend fun all(): List<DeviceAliasEntity>

    @Query("DELETE FROM device_alias")
    suspend fun clear()
}

@Dao
interface FaultRecordDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(fault: FaultRecordEntity)

    @Query("SELECT * FROM fault_record WHERE id = :id")
    suspend fun findById(id: String): FaultRecordEntity?

    @Query("UPDATE fault_record SET symptom = :symptom, updatedAt = :updatedAt WHERE id = :faultId")
    suspend fun updateSymptom(faultId: String, symptom: String, updatedAt: Long)

    @Query("UPDATE fault_record SET reportedAt = :reportedAt, updatedAt = :updatedAt WHERE id = :faultId")
    suspend fun updateReportedAt(faultId: String, reportedAt: Long, updatedAt: Long)

    @Query(
        "UPDATE fault_record SET lifecycleStatus = :lifecycleStatus, " +
            "lastProcessingId = :lastProcessingId, updatedAt = :updatedAt WHERE id = :faultId",
    )
    suspend fun updateLifecycle(
        faultId: String,
        lifecycleStatus: String,
        lastProcessingId: String,
        updatedAt: Long,
    )

    @Query("SELECT * FROM fault_record WHERE voidedAt IS NULL ORDER BY reportedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<FaultRecordEntity>

    @Query("SELECT * FROM fault_record WHERE voidedAt IS NULL ORDER BY reportedAt DESC")
    suspend fun all(): List<FaultRecordEntity>

    @Query("UPDATE fault_record SET lifecycleStatus = 'VOIDED', voidedAt = :voidedAt, updatedAt = :updatedAt WHERE id = :faultId AND voidedAt IS NULL")
    suspend fun markVoidedFault(faultId: String, voidedAt: Long, updatedAt: Long): Int

    // ---- M6 搜索 ----
    /** 单关键词 LIKE（已转义 + ESCAPE '\'）匹配故障及其处理字段；设备名/别名命中的设备 id 走 IN 子句。 */
    @Query(
        "SELECT * FROM fault_record WHERE (voidedAt IS NULL OR :includeVoided = 1) AND (" +
            "deviceNameSnapshot LIKE :term ESCAPE '\\' OR symptom LIKE :term ESCAPE '\\' " +
            "OR deviceId IN (:aliasDeviceIds) " +
            "OR id IN (SELECT faultId FROM fault_processing WHERE " +
            "checkResult LIKE :term ESCAPE '\\' OR initialJudgement LIKE :term ESCAPE '\\' " +
            "OR rootCause LIKE :term ESCAPE '\\' OR measures LIKE :term ESCAPE '\\' " +
            "OR verification LIKE :term ESCAPE '\\'))",
    )
    suspend fun searchByTerm(term: String, aliasDeviceIds: List<String>, includeVoided: Boolean): List<FaultRecordEntity>

    // ---- M7 备份恢复 ----
    @Query("DELETE FROM fault_record")
    suspend fun clear()

    /** 备份用全量故障（含已作废；不加 ORDER BY 保持稳定顺序）。首页可见列表仍用 all()。 */
    @Query("SELECT * FROM fault_record")
    suspend fun allIncludingVoided(): List<FaultRecordEntity>
}

@Dao
interface FaultProcessingDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(processing: FaultProcessingEntity)

    @Query("SELECT * FROM fault_processing WHERE id = :id")
    suspend fun findById(id: String): FaultProcessingEntity?

    @Query("SELECT * FROM fault_processing WHERE faultId = :faultId ORDER BY createdAt DESC")
    suspend fun findForFault(faultId: String): List<FaultProcessingEntity>

    @Query("SELECT * FROM fault_processing ORDER BY createdAt DESC LIMIT 1")
    suspend fun findLatest(): FaultProcessingEntity?

    @Query(
        "UPDATE fault_processing SET progressStatus = :progressStatus, startedAt = :startedAt, " +
            "endedAt = :endedAt, restoreResult = :restoreResult, updatedAt = :updatedAt, " +
            "completedAt = :completedAt WHERE id = :processingId",
    )
    suspend fun updateOutcome(
        processingId: String,
        progressStatus: String,
        startedAt: Long?,
        endedAt: Long?,
        restoreResult: String?,
        updatedAt: Long,
        completedAt: Long?,
    )

    @Query(
        "UPDATE fault_processing SET checkResult = :checkResult, initialJudgement = :initialJudgement, " +
            "rootCause = :rootCause, measures = :measures, verification = :verification, updatedAt = :updatedAt " +
            "WHERE id = :processingId",
    )
    suspend fun updateDetails(
        processingId: String,
        checkResult: String?,
        initialJudgement: String?,
        rootCause: String?,
        measures: String?,
        verification: String?,
        updatedAt: Long,
    )

    @Query(
        "SELECT * FROM fault_processing WHERE progressStatus = 'DRAFT' " +
            "AND voidedAt IS NULL ORDER BY updatedAt DESC",
    )
    fun observeDrafts(): Flow<List<FaultProcessingEntity>>

    @Query(
        "SELECT * FROM fault_processing WHERE progressStatus = 'DRAFT' " +
            "AND voidedAt IS NULL ORDER BY updatedAt DESC",
    )
    suspend fun drafts(): List<FaultProcessingEntity>

    @Query("SELECT * FROM fault_processing WHERE faultId = :faultId ORDER BY createdAt ASC")
    fun observeForFault(faultId: String): Flow<List<FaultProcessingEntity>>

    @Query("UPDATE fault_processing SET voidedAt = :voidedAt, updatedAt = :updatedAt WHERE id = :processingId AND voidedAt IS NULL")
    suspend fun markVoided(processingId: String, voidedAt: Long, updatedAt: Long): Int

    // ---- M7 备份恢复 ----
    @Query("SELECT * FROM fault_processing")
    suspend fun all(): List<FaultProcessingEntity>

    @Query("DELETE FROM fault_processing")
    suspend fun clear()
}

@Dao
interface WorkLogDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(workLog: WorkLogEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(workLog: WorkLogEntity): Long

    @Query("SELECT * FROM work_log WHERE sourceType = :sourceType AND sourceId = :sourceId LIMIT 1")
    suspend fun findBySource(sourceType: String, sourceId: String): WorkLogEntity?

    @Query("UPDATE work_log SET voidedAt = :voidedAt, updatedAt = :updatedAt WHERE id = :id AND voidedAt IS NULL")
    suspend fun markVoided(id: String, voidedAt: Long, updatedAt: Long): Int

    @Query("SELECT * FROM work_log WHERE voidedAt IS NULL AND workDate = :workDate ORDER BY updatedAt DESC")
    suspend fun forWorkDate(workDate: String): List<WorkLogEntity>

    @Query("SELECT * FROM work_log WHERE workDate = :workDate ORDER BY updatedAt DESC")
    suspend fun forWorkDateIncludingVoided(workDate: String): List<WorkLogEntity>

    @Query("SELECT * FROM work_log WHERE voidedAt IS NULL AND attendanceId = :attendanceId ORDER BY updatedAt DESC")
    suspend fun forAttendance(attendanceId: String): List<WorkLogEntity>

    @Query("SELECT * FROM work_log WHERE attendanceId = :attendanceId ORDER BY updatedAt DESC")
    suspend fun forAttendanceIncludingVoided(attendanceId: String): List<WorkLogEntity>

    @Query("SELECT * FROM work_log ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<WorkLogEntity>

    @Query("SELECT * FROM work_log WHERE (:includeVoided = 1 OR voidedAt IS NULL) AND kind = :kind ORDER BY updatedAt DESC")
    suspend fun listByKind(kind: String, includeVoided: Boolean): List<WorkLogEntity>

    @Query("SELECT * FROM work_log WHERE id = :id")
    suspend fun findById(id: String): WorkLogEntity?

    @Query(
        "UPDATE work_log SET content = :content, workResult = :workResult, area = :area, " +
            "arrangementSource = :arrangementSource, deviceId = :deviceId, deviceNameSnapshot = :deviceNameSnapshot, " +
            "workDate = COALESCE(:workDate, workDate), updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun updateManualFields(
        id: String,
        content: String,
        workResult: String?,
        area: String?,
        arrangementSource: String?,
        deviceId: String?,
        deviceNameSnapshot: String?,
        workDate: String?,
        updatedAt: Long,
    )

    @Query(
        "UPDATE work_log SET attendanceId = :attendanceId, attendanceKindSnapshot = :kind, " +
            "attendanceStartAt = :startAt, attendanceEndAt = :endAt, productionGroupSnapshot = :productionGroup, " +
            "shiftIdSnapshot = :shiftId, shiftBusinessDateSnapshot = :shiftBusinessDate, shiftTypeSnapshot = :shiftType, " +
            "shiftStartAtSnapshot = :shiftStartAt, shiftEndAtSnapshot = :shiftEndAt, " +
            "isShiftChangeSnapshot = :isShiftChange, workDate = :workDate, updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun reSnapAttendance(
        id: String,
        attendanceId: String,
        kind: String,
        startAt: Long?,
        endAt: Long?,
        productionGroup: String?,
        shiftId: String?,
        shiftBusinessDate: String?,
        shiftType: String?,
        shiftStartAt: Long?,
        shiftEndAt: Long?,
        isShiftChange: Boolean?,
        workDate: String,
        updatedAt: Long,
    )

    // ---- M6 搜索 ----
    @Query(
        "SELECT * FROM work_log WHERE (voidedAt IS NULL OR :includeVoided = 1) AND (" +
            "content LIKE :term ESCAPE '\\' OR workResult LIKE :term ESCAPE '\\' " +
            "OR deviceNameSnapshot LIKE :term ESCAPE '\\' OR deviceId IN (:aliasDeviceIds))",
    )
    suspend fun searchByTerm(term: String, aliasDeviceIds: List<String>, includeVoided: Boolean): List<WorkLogEntity>

    // ---- M7 备份恢复 ----
    @Query("SELECT * FROM work_log")
    suspend fun all(): List<WorkLogEntity>

    @Query("DELETE FROM work_log")
    suspend fun clear()
}

@Dao
interface HandoverItemDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: HandoverItemEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(item: HandoverItemEntity): Long

    @Query(
        "SELECT * FROM handover_item WHERE originType = :originType " +
            "AND sourceType = :sourceType AND sourceId = :sourceId LIMIT 1",
    )
    suspend fun findBySource(originType: String, sourceType: String, sourceId: String): HandoverItemEntity?

    @Query("SELECT * FROM handover_item WHERE voidedAt IS NULL AND status IN ('PENDING_HANDOVER','HANDED_OVER','IN_PROGRESS') ORDER BY dueAt IS NULL, dueAt ASC, updatedAt DESC")
    suspend fun pending(): List<HandoverItemEntity>

    @Query("SELECT * FROM handover_item WHERE status IN ('PENDING_HANDOVER','HANDED_OVER','IN_PROGRESS') ORDER BY dueAt IS NULL, dueAt ASC, updatedAt DESC")
    suspend fun pendingIncludingVoided(): List<HandoverItemEntity>

    @Query("SELECT * FROM handover_item WHERE id = :id")
    suspend fun findById(id: String): HandoverItemEntity?

    @Query("UPDATE handover_item SET status = :status, completedAt = :completedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, completedAt: Long?, updatedAt: Long)

    @Query("UPDATE handover_item SET voidedAt = :voidedAt, updatedAt = :updatedAt WHERE id = :id AND voidedAt IS NULL")
    suspend fun markVoided(id: String, voidedAt: Long, updatedAt: Long): Int

    @Query(
        "UPDATE handover_item SET summary = :summary, nextAction = :nextAction, dueKind = :dueKind, " +
            "dueAt = :dueAt, handoverGroup = :handoverGroup, potentialHazardNote = :potentialHazardNote, " +
            "updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun updateFields(
        id: String,
        summary: String,
        nextAction: String,
        dueKind: String,
        dueAt: Long?,
        handoverGroup: String?,
        potentialHazardNote: String?,
        updatedAt: Long,
    )

    @Query("SELECT * FROM handover_item WHERE voidedAt IS NULL AND status IN ('COMPLETED','CANCELED') ORDER BY updatedAt DESC")
    suspend fun finished(): List<HandoverItemEntity>

    @Query("SELECT * FROM handover_item WHERE status IN ('COMPLETED','CANCELED') ORDER BY updatedAt DESC")
    suspend fun finishedIncludingVoided(): List<HandoverItemEntity>

    @Query("SELECT * FROM handover_item WHERE voidedAt IS NULL ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<HandoverItemEntity>

    // ---- M5 本地通知 ----
    @Query(
        "SELECT * FROM handover_item WHERE voidedAt IS NULL " +
            "AND status IN ('PENDING_HANDOVER','HANDED_OVER','IN_PROGRESS') " +
            "AND dueAt IS NOT NULL ORDER BY dueAt ASC",
    )
    suspend fun pendingWithDue(): List<HandoverItemEntity>

    @Query(
        "SELECT * FROM handover_item WHERE voidedAt IS NULL " +
            "AND status IN ('PENDING_HANDOVER','HANDED_OVER','IN_PROGRESS') " +
            "AND dueKind = 'NEXT_SHIFT'",
    )
    suspend fun pendingNextShift(): List<HandoverItemEntity>

    @Query("UPDATE handover_item SET dueAt = :dueAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateDueAt(id: String, dueAt: Long?, updatedAt: Long)

    @Query("UPDATE handover_item SET lastOverdueNoticeDate = :date, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markOverdueNoticed(id: String, date: String, updatedAt: Long)

    // ---- M6 搜索 ----
    @Query(
        "SELECT * FROM handover_item WHERE (voidedAt IS NULL OR :includeVoided = 1) AND (" +
            "summary LIKE :term ESCAPE '\\' OR nextAction LIKE :term ESCAPE '\\' " +
            "OR potentialHazardNote LIKE :term ESCAPE '\\')",
    )
    suspend fun searchByTerm(term: String, includeVoided: Boolean): List<HandoverItemEntity>

    // ---- M7 备份恢复 ----
    @Query("SELECT * FROM handover_item")
    suspend fun all(): List<HandoverItemEntity>

    @Query("DELETE FROM handover_item")
    suspend fun clear()
}

@Dao
interface AttendanceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(attendance: AttendanceEntity)

    @Query("SELECT * FROM attendance WHERE isCurrent = 1 ORDER BY startAt DESC LIMIT 1")
    suspend fun findCurrent(): AttendanceEntity?

    @Query("SELECT * FROM attendance WHERE isCurrent = 1 ORDER BY startAt DESC LIMIT 1")
    fun observeCurrent(): Flow<AttendanceEntity?>

    @Query("SELECT COUNT(*) FROM attendance WHERE isCurrent = 1")
    suspend fun countCurrent(): Int

    // ---- M5 出勤修正 ----
    @Query("SELECT * FROM attendance WHERE id = :id")
    suspend fun findById(id: String): AttendanceEntity?

    @Query(
        "UPDATE attendance SET businessDate = :businessDate, kind = :kind, startAt = :startAt, " +
            "endAt = :endAt, productionGroup = :productionGroup, shiftId = :shiftId, " +
            "shiftBusinessDate = :shiftBusinessDate, shiftType = :shiftType, shiftStartAt = :shiftStartAt, " +
            "shiftEndAt = :shiftEndAt, isShiftChange = :isShiftChange, updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun updateCore(
        id: String,
        businessDate: String,
        kind: String,
        startAt: Long,
        endAt: Long?,
        productionGroup: String?,
        shiftId: String?,
        shiftBusinessDate: String?,
        shiftType: String?,
        shiftStartAt: Long?,
        shiftEndAt: Long?,
        isShiftChange: Boolean,
        updatedAt: Long,
    )

    /** 事务内先清后设（配合部分唯一索引保证最多一条 isCurrent = 1）。 */
    @Query("UPDATE attendance SET isCurrent = 0, updatedAt = :updatedAt WHERE isCurrent = 1")
    suspend fun clearCurrent(updatedAt: Long)

    @Query("UPDATE attendance SET isCurrent = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setCurrent(id: String, updatedAt: Long)

    @Query("SELECT * FROM attendance WHERE businessDate = :businessDate ORDER BY startAt ASC")
    fun observeForDate(businessDate: String): Flow<List<AttendanceEntity>>

    // ---- M7 备份恢复 ----
    @Query("SELECT * FROM attendance")
    suspend fun all(): List<AttendanceEntity>

    @Query("DELETE FROM attendance")
    suspend fun clear()
}

@Dao
interface MonthlyShiftPlanDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(plan: MonthlyShiftPlanEntity)

    // ---- M5 月度排班 ----
    @Query("SELECT * FROM monthly_shift_plan WHERE businessMonth = :businessMonth LIMIT 1")
    suspend fun findByMonth(businessMonth: String): MonthlyShiftPlanEntity?

    @Query("SELECT * FROM monthly_shift_plan WHERE businessMonth = :businessMonth LIMIT 1")
    fun observeByMonth(businessMonth: String): Flow<MonthlyShiftPlanEntity?>

    @Query(
        "UPDATE monthly_shift_plan SET groupADayStart = :groupADayStart, groupBDayStart = :groupBDayStart, " +
            "confirmedAt = :confirmedAt, updatedAt = :updatedAt WHERE businessMonth = :businessMonth",
    )
    suspend fun updateConfirmed(
        businessMonth: String,
        groupADayStart: Int,
        groupBDayStart: Int,
        confirmedAt: Long,
        updatedAt: Long,
    )

    // ---- M7 备份恢复 ----
    @Query("SELECT * FROM monthly_shift_plan")
    suspend fun all(): List<MonthlyShiftPlanEntity>

    @Query("DELETE FROM monthly_shift_plan")
    suspend fun clear()
}

@Dao
interface ShiftSlotDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(slot: ShiftSlotEntity)

    // ---- M5 月度排班 ----
    @Update
    suspend fun update(slot: ShiftSlotEntity)

    @Query("SELECT * FROM shift_slot WHERE id = :id")
    suspend fun findById(id: String): ShiftSlotEntity?

    @Query("SELECT * FROM shift_slot WHERE businessDate LIKE :prefix || '%' ORDER BY startAt ASC")
    fun observeByMonthPrefix(prefix: String): Flow<List<ShiftSlotEntity>>

    /** 当前月已确认/手动修正的未来班次（技术稿 §6.2：跨月 00:00-08:00 片段 businessDate 仍归上月末，前缀匹配可覆盖）。 */
    @Query("SELECT * FROM shift_slot WHERE businessDate LIKE :monthPrefix || '%' AND startAt > :now ORDER BY startAt ASC")
    suspend fun upcomingInMonth(monthPrefix: String, now: Long): List<ShiftSlotEntity>

    /** 按业务日 + 班别查班次（避免在 SQL 里直写 group 关键字列），班组匹配在 Kotlin 侧完成。 */
    @Query("SELECT * FROM shift_slot WHERE businessDate = :businessDate AND shiftType = :shiftType ORDER BY startAt ASC")
    suspend fun forDateAndType(businessDate: String, shiftType: String): List<ShiftSlotEntity>

    // ---- M7 备份恢复 ----
    @Query("SELECT * FROM shift_slot")
    suspend fun all(): List<ShiftSlotEntity>

    @Query("DELETE FROM shift_slot")
    suspend fun clear()
}

@Dao
interface BackupImportLogDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(log: BackupImportLogEntity)
}
