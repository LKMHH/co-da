package com.coda.workbench.data.repository

import com.coda.workbench.data.local.CodaDatabase
import com.coda.workbench.data.local.FaultProcessingEntity
import com.coda.workbench.data.local.FaultRecordEntity
import com.coda.workbench.data.local.WorkLogEntity

data class FaultDetailSnapshot(
    val fault: FaultRecordEntity,
    val processings: List<FaultProcessingEntity>,
    val latestProcessing: FaultProcessingEntity?,
    val derivedLogs: List<WorkLogEntity>,
)

class FaultDetailRepository(private val database: CodaDatabase) {
    suspend fun load(faultId: String): FaultDetailSnapshot {
        val fault = database.faultRecordDao().findById(faultId) ?: error("fault not found")
        val processings = database.faultProcessingDao().findForFault(faultId)
        val logs = processings.mapNotNull {
            database.workLogDao().findBySource("FAULT_PROCESSING", it.id)
        }
        return FaultDetailSnapshot(fault, processings, processings.maxByOrNull { it.createdAt }, logs)
    }
}
