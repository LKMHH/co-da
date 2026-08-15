package com.coda.workbench.data.backup

/**
 * M7 备份协议 DTO（技术稿 §10.1）：data.json 使用独立 DTO，
 * 不直接暴露 Room Entity 内部字段布局；协议版本 1。
 */
data class BackupDevice(
    val id: String,
    val name: String,
    val normalizedName: String,
    val isActive: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

data class BackupDeviceAlias(
    val id: String,
    val deviceId: String,
    val alias: String,
    val normalizedAlias: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class BackupFault(
    val id: String,
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

data class BackupProcessing(
    val id: String,
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

data class BackupWorkLog(
    val id: String,
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

data class BackupHandoverItem(
    val id: String,
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

data class BackupAttendance(
    val id: String,
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

data class BackupShiftPlan(
    val id: String,
    val businessMonth: String,
    val groupADayStart: Int,
    val groupBDayStart: Int,
    val confirmedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class BackupShiftSlot(
    val id: String,
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

data class BackupData(
    val devices: List<BackupDevice>,
    val deviceAliases: List<BackupDeviceAlias>,
    val faults: List<BackupFault>,
    val processings: List<BackupProcessing>,
    val workLogs: List<BackupWorkLog>,
    val handoverItems: List<BackupHandoverItem>,
    val attendance: List<BackupAttendance>,
    val shiftPlans: List<BackupShiftPlan>,
    val shiftSlots: List<BackupShiftSlot>,
)

data class BackupCounts(
    val devices: Int = 0,
    val deviceAliases: Int = 0,
    val faults: Int = 0,
    val processings: Int = 0,
    val workLogs: Int = 0,
    val handoverItems: Int = 0,
    val attendance: Int = 0,
    val shiftPlans: Int = 0,
    val shiftSlots: Int = 0,
) {
    fun of(data: BackupData): BackupCounts = BackupCounts(
        devices = data.devices.size,
        deviceAliases = data.deviceAliases.size,
        faults = data.faults.size,
        processings = data.processings.size,
        workLogs = data.workLogs.size,
        handoverItems = data.handoverItems.size,
        attendance = data.attendance.size,
        shiftPlans = data.shiftPlans.size,
        shiftSlots = data.shiftSlots.size,
    )
}

data class BackupManifest(
    val format: String,
    val formatVersion: Int,
    val minReaderVersion: Int,
    val createdAt: Long,
    val appVersion: String,
    val counts: BackupCounts,
    val dataSha256: String,
)

data class BackupPreview(
    val counts: BackupCounts,
    val data: BackupData,
    /** 校验通过时已完成的安全备份（技术稿 §10.3：安全备份在用户确认前完成）。 */
    val safetyFile: java.io.File?,
)

data class BackupExportResult(
    val counts: BackupCounts,
    val dataSha256: String,
)

data class RestoreResult(
    val counts: BackupCounts,
)
