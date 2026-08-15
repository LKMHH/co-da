package com.coda.workbench.data.repository

import androidx.room.withTransaction
import com.coda.workbench.data.backup.BackupCodec
import com.coda.workbench.data.backup.BackupCounts
import com.coda.workbench.data.backup.BackupData
import com.coda.workbench.data.backup.toBackupDto
import com.coda.workbench.data.backup.toEntity
import com.coda.workbench.data.local.BackupImportLogEntity
import com.coda.workbench.data.local.CodaDatabase
import java.util.UUID

/**
 * M7 数据层替换实现（技术稿 §10.3）：snapshot 读全量 → DTO；apply 在单事务内
 * 按外键依赖顺序清空并写入全部 DTO（不触碰 backup_import_log 与 DataStore 偏好）。
 */
class BackupRepository(private val database: CodaDatabase) {

    suspend fun snapshot(): BackupData = BackupData(
        devices = database.deviceDao().all().map { it.toBackupDto() },
        deviceAliases = database.deviceAliasDao().all().map { it.toBackupDto() },
        faults = database.faultRecordDao().allIncludingVoided().map { it.toBackupDto() },
        processings = database.faultProcessingDao().all().map { it.toBackupDto() },
        workLogs = database.workLogDao().all().map { it.toBackupDto() },
        handoverItems = database.handoverItemDao().all().map { it.toBackupDto() },
        attendance = database.attendanceDao().all().map { it.toBackupDto() },
        shiftPlans = database.monthlyShiftPlanDao().all().map { it.toBackupDto() },
        shiftSlots = database.shiftSlotDao().all().map { it.toBackupDto() },
    )

    suspend fun apply(data: BackupData) {
        BackupCodec.validate(data)
        database.withTransaction {
            // 清空顺序：先子后父（部分表有级联，显式清理更稳），保留 backup_import_log
            database.deviceAliasDao().clear()
            database.faultProcessingDao().clear()
            database.shiftSlotDao().clear()
            database.workLogDao().clear()
            database.handoverItemDao().clear()
            database.attendanceDao().clear()
            database.faultRecordDao().clear()
            database.deviceDao().clear()
            database.monthlyShiftPlanDao().clear()

            // 写入顺序：先父后子（外键依赖）
            data.devices.forEach { database.deviceDao().insert(it.toEntity()) }
            data.deviceAliases.forEach { database.deviceAliasDao().insert(it.toEntity()) }
            data.faults.forEach { database.faultRecordDao().insert(it.toEntity()) }
            data.processings.forEach { database.faultProcessingDao().insert(it.toEntity()) }
            data.workLogs.forEach { database.workLogDao().insert(it.toEntity()) }
            data.handoverItems.forEach { database.handoverItemDao().insert(it.toEntity()) }
            data.attendance.forEach { database.attendanceDao().insert(it.toEntity()) }
            data.shiftPlans.forEach { database.monthlyShiftPlanDao().insert(it.toEntity()) }
            data.shiftSlots.forEach { database.shiftSlotDao().insert(it.toEntity()) }
        }
    }

    suspend fun logImport(
        fileSha256: String,
        result: String,
        counts: BackupCounts?,
        errorMessage: String?,
        startedAt: Long,
        endedAt: Long,
    ) {
        runCatching {
            database.backupImportLogDao().insert(
                BackupImportLogEntity(
                    id = UUID.randomUUID().toString(),
                    startedAt = startedAt,
                    endedAt = endedAt,
                    fileSha256 = fileSha256,
                    result = result,
                    countsJson = counts?.let { encodeCounts(it) },
                    errorMessage = errorMessage,
                ),
            )
        }
    }

    private fun encodeCounts(counts: BackupCounts): String =
        "devices=${counts.devices};deviceAliases=${counts.deviceAliases};faults=${counts.faults};" +
            "processings=${counts.processings};workLogs=${counts.workLogs};handoverItems=${counts.handoverItems};" +
            "attendance=${counts.attendance};shiftPlans=${counts.shiftPlans};shiftSlots=${counts.shiftSlots}"
}
