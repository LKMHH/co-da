package com.coda.workbench.core.usecase

import com.coda.workbench.data.local.CodaDatabase
import com.coda.workbench.data.local.FaultProcessingEntity
import com.coda.workbench.data.repository.FaultDetailRepository
import com.coda.workbench.data.repository.FaultDetailSnapshot
import java.time.Clock

class FaultDetailUseCase(
    private val repository: FaultDetailRepository,
    private val faultUseCase: FaultUseCase,
    private val database: CodaDatabase,
    private val clock: Clock,
) {
    suspend fun load(faultId: String): FaultDetailSnapshot = repository.load(faultId)

    suspend fun start(processingId: String): FaultProcessingEntity = faultUseCase.startProcessing(processingId)

    suspend fun continueProcessing(processingId: String): FaultProcessingEntity =
        faultUseCase.continueProcessing(processingId)

    suspend fun cancel(processingId: String): FaultProcessingEntity = faultUseCase.cancelProcessing(processingId)

    suspend fun voidDerivedLog(logId: String) {
        val now = clock.millis()
        check(database.workLogDao().markVoided(logId, now, now) == 1) { "记录不存在或已作废" }
    }

    suspend fun voidFault(faultId: String) = faultUseCase.voidFault(faultId)

    suspend fun voidProcessing(processingId: String) = faultUseCase.voidProcessing(processingId)

    suspend fun updateReportedAt(faultId: String, reportedAt: Long) {
        database.faultRecordDao().updateReportedAt(faultId, reportedAt, clock.millis())
    }

    suspend fun update(
        processingId: String,
        checkResult: String?,
        initialJudgement: String?,
        rootCause: String?,
        measures: String?,
        verification: String?,
    ): FaultProcessingEntity = faultUseCase.updateProcessingDetails(
        processingId, checkResult, initialJudgement, rootCause, measures, verification,
    )

    suspend fun markPending(processingId: String): FaultProcessingEntity =
        faultUseCase.markPendingVerification(processingId)

    suspend fun resume(processingId: String): FaultProcessingEntity =
        faultUseCase.resumeProcessing(processingId)

    suspend fun finish(
        processingId: String,
        restoreResult: RestoreResult,
        verification: String?,
        nextAction: String?,
        dueAt: Long?,
        dueKind: HandoverDueKind,
    ): FinishProcessingResult = faultUseCase.finishProcessing(
        processingId, restoreResult, verification, nextAction, dueAt, dueKind,
    )
}
