package com.coda.workbench.data.repository

import androidx.room.withTransaction
import com.coda.workbench.data.local.CodaDatabase
import com.coda.workbench.data.local.DeviceEntity
import com.coda.workbench.data.local.FaultProcessingEntity
import com.coda.workbench.data.local.FaultRecordEntity
import com.coda.workbench.data.local.WorkLogEntity
import java.time.Clock
import java.time.ZoneId
import java.util.UUID

class FaultDraftRepository(
    private val database: CodaDatabase,
    private val clock: Clock,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun createDraft(
        device: DeviceEntity,
        fault: FaultRecordEntity,
        processing: FaultProcessingEntity,
    ) {
        require(fault.deviceId == device.id) { "fault must reference the inserted device" }
        require(processing.faultId == fault.id) { "processing must reference the inserted fault" }
        require(processing.progressStatus == "DRAFT") { "draft processing must be DRAFT" }
        AttendanceRepository(database, clock = clock).ensureCurrent(zoneId)
        database.withTransaction {
            database.deviceDao().insert(device)
            database.faultRecordDao().insert(fault)
            database.faultProcessingDao().insert(processing)
        }
    }

    suspend fun updateDraftSymptom(faultId: String, symptom: String) {
        database.faultRecordDao().updateSymptom(
            faultId = faultId,
            symptom = symptom,
            updatedAt = clock.millis(),
        )
    }

    suspend fun updateDraftReportedAt(faultId: String, reportedAtMillis: Long) {
        database.faultRecordDao().updateReportedAt(
            faultId = faultId,
            reportedAt = reportedAtMillis,
            updatedAt = clock.millis(),
        )
    }

    suspend fun findFaultIdForProcessing(processingId: String): String =
        database.faultProcessingDao().findById(processingId)?.faultId
            ?: error("processing not found")

    suspend fun createMinimalDraft(
        deviceName: String,
        symptom: String,
        nowMillis: Long = clock.millis(),
        reportedAtMillis: Long = nowMillis,
    ): String {
        val deviceId = UUID.randomUUID().toString()
        val faultId = UUID.randomUUID().toString()
        val processingId = UUID.randomUUID().toString()
        val device = DeviceEntity(
            id = deviceId,
            name = deviceName,
            normalizedName = deviceName,
            isActive = true,
            createdAt = nowMillis,
            updatedAt = nowMillis,
        )
        val fault = FaultRecordEntity(
            id = faultId,
            deviceId = deviceId,
            deviceNameSnapshot = deviceName,
            reportedAt = reportedAtMillis,
            symptom = symptom,
            lifecycleStatus = "OPEN",
            lastProcessingId = processingId,
            createdAt = nowMillis,
            updatedAt = nowMillis,
            voidedAt = null,
        )
        val processing = FaultProcessingEntity(
            id = processingId,
            faultId = faultId,
            progressStatus = "DRAFT",
            restoreResult = null,
            startedAt = null,
            endedAt = null,
            checkResult = null,
            initialJudgement = null,
            rootCause = null,
            measures = null,
            verification = null,
            createdAt = nowMillis,
            updatedAt = nowMillis,
            completedAt = null,
            voidedAt = null,
        )
        createDraft(device, fault, processing)
        return processingId
    }

    suspend fun createManual(
        content: String,
        workDate: String,
        idFactory: () -> String = { UUID.randomUUID().toString() },
    ): String {
        val attendance = AttendanceRepository(database, clock = clock).ensureCurrentEntity(zoneId)
        val now = clock.millis()
        val shiftBusinessDate = attendance.shiftBusinessDate ?: attendance.businessDate
        val workLog = WorkLogEntity(
            id = idFactory(),
            kind = "MANUAL",
            content = content,
            workDate = workDate,
            attendanceId = attendance.id,
            attendanceKindSnapshot = attendance.kind,
            attendanceStartAt = attendance.startAt,
            attendanceEndAt = attendance.endAt,
            productionGroupSnapshot = attendance.productionGroup,
            shiftIdSnapshot = attendance.shiftId,
            shiftBusinessDateSnapshot = shiftBusinessDate,
            shiftTypeSnapshot = attendance.shiftType,
            shiftStartAtSnapshot = attendance.shiftStartAt,
            shiftEndAtSnapshot = attendance.shiftEndAt,
            isShiftChangeSnapshot = attendance.isShiftChange,
            workResult = null,
            deviceId = null,
            area = null,
            deviceNameSnapshot = null,
            processingStartedAt = null,
            processingEndedAt = null,
            processedAt = null,
            restoreResult = null,
            arrangementSource = "MANUAL",
            sourceType = null,
            sourceId = null,
            status = "ACTIVE",
            createdAt = now,
            updatedAt = now,
            voidedAt = null,
        )
        database.workLogDao().insert(workLog)
        return workLog.id
    }
}
