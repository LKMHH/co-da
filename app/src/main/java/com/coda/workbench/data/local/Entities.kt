package com.coda.workbench.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "device",
    indices = [Index(value = ["normalizedName"])],
)
data class DeviceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val normalizedName: String,
    val isActive: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "device_alias",
    foreignKeys = [
        ForeignKey(
            entity = DeviceEntity::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["deviceId"]),
        Index(value = ["normalizedAlias"], unique = true),
    ],
)
data class DeviceAliasEntity(
    @PrimaryKey val id: String,
    val deviceId: String,
    val alias: String,
    val normalizedAlias: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "fault_record",
    foreignKeys = [
        ForeignKey(
            entity = DeviceEntity::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
        ),
    ],
    indices = [Index(value = ["deviceId", "reportedAt"])],
)
data class FaultRecordEntity(
    @PrimaryKey val id: String,
    val deviceId: String,
    val deviceNameSnapshot: String,
    val reportedAt: Long,
    val symptom: String,
    val lifecycleStatus: String,
    val lastProcessingId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val voidedAt: Long?,
)

@Entity(
    tableName = "fault_processing",
    foreignKeys = [
        ForeignKey(
            entity = FaultRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["faultId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["faultId", "createdAt"])],
)
data class FaultProcessingEntity(
    @PrimaryKey val id: String,
    val faultId: String,
    val progressStatus: String,
    val restoreResult: String?,
    val startedAt: Long?,
    val endedAt: Long?,
    val checkResult: String?,
    val initialJudgement: String?,
    val rootCause: String?,
    val measures: String?,
    val verification: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
    val voidedAt: Long?,
)

@Entity(
    tableName = "work_log",
    indices = [
        Index(value = ["workDate", "updatedAt"]),
        Index(value = ["attendanceId", "updatedAt"]),
    ],
)
data class WorkLogEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val content: String,
    val workDate: String,
    val attendanceId: String?,
    val attendanceKindSnapshot: String?,
    val attendanceStartAt: Long?,
    val attendanceEndAt: Long?,
    val productionGroupSnapshot: String?,
    val shiftIdSnapshot: String?,
    val shiftBusinessDateSnapshot: String?,
    val shiftTypeSnapshot: String?,
    val shiftStartAtSnapshot: Long?,
    val shiftEndAtSnapshot: Long?,
    val isShiftChangeSnapshot: Boolean?,
    val workResult: String?,
    val deviceId: String?,
    val area: String?,
    val deviceNameSnapshot: String?,
    val processingStartedAt: Long?,
    val processingEndedAt: Long?,
    val processedAt: Long?,
    val restoreResult: String?,
    val arrangementSource: String?,
    val sourceType: String?,
    val sourceId: String?,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val voidedAt: Long?,
)

@Entity(
    tableName = "handover_item",
    indices = [Index(value = ["status", "dueAt"])],
)
data class HandoverItemEntity(
    @PrimaryKey val id: String,
    val summary: String,
    val status: String,
    val nextAction: String,
    val dueKind: String,
    val dueAt: Long?,
    val originType: String,
    val sourceType: String?,
    val sourceId: String?,
    val handoverGroup: String?,
    val potentialHazardNote: String?,
    val lastOverdueNoticeDate: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
    val voidedAt: Long?,
)

@Entity(
    tableName = "attendance",
    indices = [
        Index(value = ["startAt", "endAt"]),
    ],
)
data class AttendanceEntity(
    @PrimaryKey val id: String,
    val businessDate: String,
    val kind: String,
    val startAt: Long,
    val endAt: Long?,
    val productionGroup: String?,
    val shiftId: String?,
    val shiftBusinessDate: String?,
    val shiftType: String?,
    val shiftStartAt: Long?,
    val shiftEndAt: Long?,
    val isShiftChange: Boolean,
    val isCurrent: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "monthly_shift_plan",
    indices = [Index(value = ["businessMonth"], unique = true)],
)
data class MonthlyShiftPlanEntity(
    @PrimaryKey val id: String,
    val businessMonth: String,
    val groupADayStart: Int,
    val groupBDayStart: Int,
    val confirmedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "shift_slot",
    foreignKeys = [
        ForeignKey(
            entity = MonthlyShiftPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["planId"]),
        Index(value = ["businessDate", "group", "shiftType"], unique = true),
    ],
)
data class ShiftSlotEntity(
    @PrimaryKey val id: String,
    val planId: String,
    val businessDate: String,
    val group: String,
    val shiftType: String,
    val startAt: Long,
    val endAt: Long,
    val isShiftChange: Boolean,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "backup_import_log")
data class BackupImportLogEntity(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val endedAt: Long?,
    val fileSha256: String,
    val result: String,
    val countsJson: String?,
    val errorMessage: String?,
)
