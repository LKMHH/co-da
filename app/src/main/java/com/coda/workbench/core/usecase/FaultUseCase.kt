package com.coda.workbench.core.usecase

import androidx.room.withTransaction
import com.coda.workbench.core.model.ProcessingStatus
import com.coda.workbench.core.rules.HandoverDueRules
import com.coda.workbench.data.local.CodaDatabase
import com.coda.workbench.data.local.FaultProcessingEntity
import com.coda.workbench.data.local.HandoverItemEntity
import com.coda.workbench.data.local.WorkLogEntity
import com.coda.workbench.data.repository.AttendanceRepository
import java.time.Clock
import java.time.ZoneId
import java.util.UUID

enum class RestoreResult {
    RESTORED,
    TEMPORARY,
    PARTIAL,
    NOT_RESTORED,
    UNKNOWN,
}

enum class HandoverDueKind {
    NONE,
    END_OF_TODAY,
    NEXT_SHIFT,
    SPECIFIC,
}

data class FinishProcessingResult(
    val processingId: String,
    val workLogId: String,
    val handoverId: String?,
    val restoreResult: RestoreResult,
    val notificationScheduled: Boolean,
)

interface FaultNotificationScheduler {
    suspend fun scheduleForFault(faultId: String, handoverId: String)
}

object NoOpFaultNotificationScheduler : FaultNotificationScheduler {
    override suspend fun scheduleForFault(faultId: String, handoverId: String) = Unit
}

/** Coordinates the fault processing transaction; notifications are deliberately post-commit. */
class FaultUseCase(
    private val database: CodaDatabase,
    private val clock: Clock,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val notificationScheduler: FaultNotificationScheduler = NoOpFaultNotificationScheduler,
    private val transactionHook: (() -> Unit)? = null,
    private val attendanceRepository: AttendanceRepository = AttendanceRepository(database, clock = clock),
) {
    suspend fun startProcessing(processingId: String): FaultProcessingEntity = database.withTransaction {
        val processing = requireProcessing(processingId)
        check(processing.progressStatus == ProcessingStatus.DRAFT.name) { "processing is not a draft" }
        val now = clock.millis()
        database.faultProcessingDao().updateOutcome(
            processingId = processing.id,
            progressStatus = ProcessingStatus.IN_PROGRESS.name,
            startedAt = now,
            endedAt = processing.endedAt,
            restoreResult = processing.restoreResult,
            updatedAt = now,
            completedAt = processing.completedAt,
        )
        database.faultProcessingDao().findById(processing.id)!!
    }

    suspend fun continueProcessing(processingId: String): FaultProcessingEntity = database.withTransaction {
        val previous = requireProcessing(processingId)
        val fault = database.faultRecordDao().findById(previous.faultId)
            ?: error("fault not found")
        check(
            fault.lifecycleStatus != "CLOSED" && fault.lifecycleStatus != "VOIDED",
        ) { "cannot continue a closed or voided fault" }
        check(
            previous.progressStatus == ProcessingStatus.ENDED.name ||
                previous.progressStatus == ProcessingStatus.CANCELED.name,
        ) { "only a terminal processing can be continued" }
        val now = clock.millis()
        val next = FaultProcessingEntity(
            id = UUID.randomUUID().toString(),
            faultId = previous.faultId,
            progressStatus = ProcessingStatus.IN_PROGRESS.name,
            restoreResult = null,
            startedAt = now,
            endedAt = null,
            checkResult = null,
            initialJudgement = null,
            rootCause = null,
            measures = null,
            verification = null,
            createdAt = now,
            updatedAt = now,
            completedAt = null,
            voidedAt = null,
        )
        database.faultProcessingDao().insert(next)
        database.faultRecordDao().updateLifecycle(
            faultId = previous.faultId,
            lifecycleStatus = "OPEN",
            lastProcessingId = next.id,
            updatedAt = now,
        )
        next
    }

    suspend fun cancelProcessing(processingId: String): FaultProcessingEntity = database.withTransaction {
        val processing = requireProcessing(processingId)
        check(
            processing.progressStatus == ProcessingStatus.DRAFT.name ||
                processing.progressStatus == ProcessingStatus.IN_PROGRESS.name ||
                processing.progressStatus == ProcessingStatus.PENDING_VERIFICATION.name,
        ) { "processing is not cancellable" }
        val now = clock.millis()
        database.faultProcessingDao().updateOutcome(
            processingId = processing.id,
            progressStatus = ProcessingStatus.CANCELED.name,
            startedAt = processing.startedAt,
            endedAt = now,
            restoreResult = processing.restoreResult,
            updatedAt = now,
            completedAt = now,
        )
        database.faultProcessingDao().findById(processing.id)!!
    }

    suspend fun updateProcessingDetails(
        processingId: String,
        checkResult: String?,
        initialJudgement: String?,
        rootCause: String?,
        measures: String?,
        verification: String?,
    ): FaultProcessingEntity = database.withTransaction {
        val processing = requireProcessing(processingId)
        check(
            processing.progressStatus == ProcessingStatus.IN_PROGRESS.name ||
                processing.progressStatus == ProcessingStatus.PENDING_VERIFICATION.name,
        ) { "processing details are not editable" }
        database.faultProcessingDao().updateDetails(
            processingId = processingId,
            checkResult = checkResult,
            initialJudgement = initialJudgement,
            rootCause = rootCause,
            measures = measures,
            verification = verification,
            updatedAt = clock.millis(),
        )
        database.faultProcessingDao().findById(processingId)!!
    }

    suspend fun markPendingVerification(processingId: String): FaultProcessingEntity = database.withTransaction {
        val processing = requireProcessing(processingId)
        check(processing.progressStatus == ProcessingStatus.IN_PROGRESS.name) { "processing is not in progress" }
        val now = clock.millis()
        database.faultProcessingDao().updateOutcome(
            processingId = processingId,
            progressStatus = ProcessingStatus.PENDING_VERIFICATION.name,
            startedAt = processing.startedAt,
            endedAt = processing.endedAt,
            restoreResult = processing.restoreResult,
            updatedAt = now,
            completedAt = processing.completedAt,
        )
        database.faultProcessingDao().findById(processingId)!!
    }

    suspend fun resumeProcessing(processingId: String): FaultProcessingEntity = database.withTransaction {
        val processing = requireProcessing(processingId)
        check(processing.progressStatus == ProcessingStatus.PENDING_VERIFICATION.name) { "processing is not pending verification" }
        val now = clock.millis()
        database.faultProcessingDao().updateOutcome(
            processingId = processingId,
            progressStatus = ProcessingStatus.IN_PROGRESS.name,
            startedAt = processing.startedAt,
            endedAt = processing.endedAt,
            restoreResult = processing.restoreResult,
            updatedAt = now,
            completedAt = processing.completedAt,
        )
        database.faultProcessingDao().findById(processingId)!!
    }

    suspend fun finishProcessing(
        processingId: String,
        restoreResult: RestoreResult,
        verification: String? = null,
        nextAction: String? = null,
        dueAt: Long? = null,
        dueKind: HandoverDueKind = if (dueAt == null) HandoverDueKind.NONE else HandoverDueKind.SPECIFIC,
    ): FinishProcessingResult {
        val attendance = attendanceRepository.ensureCurrentEntity(zoneId)
        val result = database.withTransaction {
            val processing = requireProcessing(processingId)
            val existingWorkLog = database.workLogDao().findBySource(SOURCE_TYPE, processingId)
            if (processing.progressStatus == ProcessingStatus.ENDED.name && existingWorkLog != null) {
                val existingHandover = database.handoverItemDao().findBySource(
                    AUTO_ORIGIN,
                    SOURCE_TYPE,
                    processingId,
                )
                return@withTransaction FinishProcessingResult(
                    processingId = processingId,
                    workLogId = existingWorkLog.id,
                    handoverId = existingHandover?.id,
                    restoreResult = RestoreResult.valueOf(processing.restoreResult ?: restoreResult.name),
                    notificationScheduled = false,
                )
            }
            check(
                processing.progressStatus == ProcessingStatus.IN_PROGRESS.name ||
                    processing.progressStatus == ProcessingStatus.PENDING_VERIFICATION.name,
            ) { "processing cannot be finished" }
            val fault = database.faultRecordDao().findById(processing.faultId)
                ?: error("fault not found")
            val now = clock.millis()
            val shiftBusinessDate = attendance.shiftBusinessDate ?: attendance.businessDate
            val workDate = shiftBusinessDate
            val workLog = existingWorkLog ?: WorkLogEntity(
                id = UUID.randomUUID().toString(),
                kind = "FAULT_DERIVED",
                content = fault.symptom,
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
                deviceId = fault.deviceId,
                area = null,
                deviceNameSnapshot = fault.deviceNameSnapshot,
                processingStartedAt = processing.startedAt,
                processingEndedAt = now,
                processedAt = now,
                restoreResult = restoreResult.name,
                arrangementSource = "FAULT_PROCESSING",
                sourceType = SOURCE_TYPE,
                sourceId = processingId,
                status = "ACTIVE",
                createdAt = now,
                updatedAt = now,
                voidedAt = null,
            )
            val persistedWorkLog = if (existingWorkLog == null) {
                database.workLogDao().insertIgnore(workLog)
                database.workLogDao().findBySource(SOURCE_TYPE, processingId)!!
            } else {
                existingWorkLog
            }
            transactionHook?.invoke()

            val handover = if (restoreResult == RestoreResult.RESTORED) {
                null
            } else {
                database.handoverItemDao().findBySource(AUTO_ORIGIN, SOURCE_TYPE, processingId)
                    ?: run {
                        // M5 期限换算：END_OF_TODAY 按当前出勤 endAt（缺省当天 18:00）写快照；NEXT_SHIFT 取当前月下一班次开始时间
                        val monthPrefix = java.time.YearMonth.now(clock.withZone(zoneId)).toString() + "-"
                        val resolvedDueAt = HandoverDueRules.resolveDueAt(
                            dueKindName = dueKind.name,
                            explicitDueAt = dueAt,
                            attendanceEndAt = attendance.endAt,
                            upcomingStarts = database.shiftSlotDao().upcomingInMonth(monthPrefix, now).map { it.startAt },
                            now = clock.instant(),
                            zoneId = zoneId,
                        )
                        val candidate = HandoverItemEntity(
                            id = UUID.randomUUID().toString(),
                            summary = fault.deviceNameSnapshot + fault.symptom,
                            status = "PENDING_HANDOVER",
                            nextAction = nextAction?.trim().takeUnless { it.isNullOrEmpty() } ?: "待跟进",
                            dueKind = dueKind.name,
                            dueAt = resolvedDueAt,
                            originType = AUTO_ORIGIN,
                            sourceType = SOURCE_TYPE,
                            sourceId = processingId,
                            handoverGroup = null,
                            potentialHazardNote = null,
                            lastOverdueNoticeDate = null,
                            createdAt = now,
                            updatedAt = now,
                            completedAt = null,
                            voidedAt = null,
                        )
                        database.handoverItemDao().insertIgnore(candidate)
                        database.handoverItemDao().findBySource(AUTO_ORIGIN, SOURCE_TYPE, processingId)!!
                    }
            }
            database.faultProcessingDao().updateDetails(
                processingId = processingId,
                checkResult = processing.checkResult,
                initialJudgement = processing.initialJudgement,
                rootCause = processing.rootCause,
                measures = processing.measures,
                verification = verification ?: processing.verification,
                updatedAt = now,
            )
            database.faultProcessingDao().updateOutcome(
                processingId = processingId,
                progressStatus = ProcessingStatus.ENDED.name,
                startedAt = processing.startedAt,
                endedAt = now,
                restoreResult = restoreResult.name,
                updatedAt = now,
                completedAt = now,
            )
            database.faultRecordDao().updateLifecycle(
                faultId = fault.id,
                lifecycleStatus = if (restoreResult == RestoreResult.RESTORED) "CLOSED" else "OPEN",
                lastProcessingId = processingId,
                updatedAt = now,
            )
            FinishProcessingResult(
                processingId = processingId,
                workLogId = persistedWorkLog.id,
                handoverId = handover?.id,
                restoreResult = restoreResult,
                notificationScheduled = false,
            )
        }

        var notificationScheduled = false
        if (result.handoverId != null) {
            notificationScheduled = runCatching {
                notificationScheduler.scheduleForFault(
                    faultId = database.faultProcessingDao().findById(processingId)!!.faultId,
                    handoverId = result.handoverId,
                )
            }.isSuccess
        }
        return result.copy(notificationScheduled = notificationScheduled)
    }

    suspend fun voidFault(faultId: String) {
        val fault = database.faultRecordDao().findById(faultId) ?: error("故障不存在")
        check(fault.lifecycleStatus != "VOIDED") { "故障已作废" }
        val hasUnfinished = database.faultProcessingDao().findForFault(faultId)
            .any { it.progressStatus in setOf("DRAFT", "IN_PROGRESS", "PENDING_VERIFICATION") }
        check(!hasUnfinished) { "存在未结束处理，不能作废故障" }
        val now = clock.millis()
        check(database.faultRecordDao().markVoidedFault(faultId, now, now) == 1) { "故障不存在或已作废" }
    }

    suspend fun voidProcessing(processingId: String) {
        val processing = requireProcessing(processingId)
        check(processing.progressStatus in setOf("ENDED", "CANCELED")) { "未结束的处理不能作废" }
        val now = clock.millis()
        check(database.faultProcessingDao().markVoided(processingId, now, now) == 1) { "处理记录不存在或已作废" }
    }

    private suspend fun requireProcessing(id: String): FaultProcessingEntity =
        database.faultProcessingDao().findById(id) ?: error("processing not found")

    companion object {
        const val SOURCE_TYPE = "FAULT_PROCESSING"
        const val AUTO_ORIGIN = "AUTO_FAULT_PROCESSING"
    }
}
