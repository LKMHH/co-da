package com.coda.workbench.core.usecase

import com.coda.workbench.data.local.DeviceEntity
import com.coda.workbench.data.repository.DeviceRepository
import com.coda.workbench.data.repository.FaultDraftRepository
import kotlinx.coroutines.flow.Flow
import java.time.Clock

class FaultEntryUseCase(
    private val drafts: FaultDraftRepository,
    private val devices: DeviceRepository,
    private val clock: Clock,
) {
    fun observeRecentDevices(): Flow<List<DeviceEntity>> = devices.observeRecent()

    suspend fun save(
        deviceName: String,
        reportedAtMillis: Long,
        symptom: String,
    ): String {
        require(deviceName.isNotBlank()) { "设备名称不能为空" }
        require(symptom.isNotBlank()) { "现象不能为空" }
        val processingId = drafts.createMinimalDraft(
            deviceName = deviceName.trim(),
            symptom = symptom.trim(),
            nowMillis = clock.millis(),
            reportedAtMillis = reportedAtMillis,
        )
        return drafts.findFaultIdForProcessing(processingId)
    }

    suspend fun updateDraft(faultId: String, symptom: String, reportedAtMillis: Long) {
        drafts.updateDraftSymptom(faultId, symptom.trim())
        drafts.updateDraftReportedAt(faultId, reportedAtMillis)
    }
}
