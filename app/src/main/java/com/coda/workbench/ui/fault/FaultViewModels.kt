package com.coda.workbench.ui.fault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coda.workbench.core.usecase.FaultDetailUseCase
import com.coda.workbench.core.usecase.FaultEntryUseCase
import com.coda.workbench.core.usecase.HandoverDueKind
import com.coda.workbench.core.usecase.RestoreResult
import com.coda.workbench.data.local.DeviceEntity
import com.coda.workbench.data.repository.FaultDetailSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class FaultEntryState(
    val deviceName: String = "",
    val reportedAtMillis: Long = 0L,
    val symptom: String = "",
    val recentDevices: List<DeviceEntity> = emptyList(),
    val saving: Boolean = false,
    val savedFaultId: String? = null,
    val error: String? = null,
)

@HiltViewModel
class FaultEntryViewModel @Inject constructor(
    private val useCase: FaultEntryUseCase,
    private val clock: java.time.Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(FaultEntryState(reportedAtMillis = clock.millis()))
    val state: StateFlow<FaultEntryState> = _state.asStateFlow()
    private var autosaveJob: Job? = null
    private var draftFaultId: String? = null

    init {
        viewModelScope.launch { useCase.observeRecentDevices().collect { _state.value = _state.value.copy(recentDevices = it) } }
    }

    fun setDeviceName(value: String) { _state.value = _state.value.copy(deviceName = value, error = null); scheduleAutosave() }
    fun setReportedAt(value: Long) { _state.value = _state.value.copy(reportedAtMillis = value) }
    fun setSymptom(value: String) { _state.value = _state.value.copy(symptom = value, error = null); scheduleAutosave() }
    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        if (_state.value.deviceName.isBlank() || _state.value.symptom.isBlank()) return
        autosaveJob = viewModelScope.launch {
            delay(500)
            autosave()
        }
    }

    fun reset() {
        autosaveJob?.cancel()
        draftFaultId = null
        _state.value = FaultEntryState(reportedAtMillis = clock.millis())
    }

    fun autosave() {
        val current = _state.value
        if (current.saving || draftFaultId != null || current.savedFaultId != null) return
        if (current.deviceName.isBlank() || current.symptom.isBlank()) return
        _state.value = current.copy(saving = true, error = null)
        viewModelScope.launch {
            runCatching { useCase.save(current.deviceName, current.reportedAtMillis, current.symptom) }
                .onSuccess {
                    draftFaultId = it
                    _state.value = _state.value.copy(saving = false)
                }
                .onFailure { _state.value = _state.value.copy(saving = false, error = it.message ?: "保存失败") }
        }
    }

    /** 显式保存并返回故障 id；由界面拿到 id 后再跳转，避免旧状态引发的误跳转与闪烁。 */
    suspend fun saveOnce(): String? {
        val current = _state.value
        if (current.savedFaultId != null) return current.savedFaultId
        if (current.deviceName.isBlank() || current.symptom.isBlank()) {
            _state.value = current.copy(error = "设备名称和现象必填")
            return null
        }
        autosaveJob?.cancel()
        _state.value = current.copy(saving = true, error = null)
        return try {
            val id = draftFaultId?.let {
                useCase.updateDraft(it, current.symptom, current.reportedAtMillis)
                it
            } ?: useCase.save(current.deviceName, current.reportedAtMillis, current.symptom)
            draftFaultId = id
            _state.value = _state.value.copy(saving = false, savedFaultId = id)
            id
        } catch (e: Exception) {
            _state.value = _state.value.copy(saving = false, error = e.message ?: "保存失败")
            null
        }
    }
}

data class FaultDetailUiState(
    val loading: Boolean = true,
    val snapshot: FaultDetailSnapshot? = null,
    val error: String? = null,
    val saving: Boolean = false,
    val finishDialog: Boolean = false,
)

@HiltViewModel
class FaultDetailViewModel @Inject constructor(private val useCase: FaultDetailUseCase) : ViewModel() {
    private val _state = MutableStateFlow(FaultDetailUiState())
    val state: StateFlow<FaultDetailUiState> = _state.asStateFlow()
    private var faultId: String? = null
    private var updateJob: Job? = null

    fun load(id: String) {
        if (faultId == id && _state.value.snapshot != null) return
        faultId = id
        refresh()
    }

    private fun refresh() {
        val id = faultId ?: return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { useCase.load(id) }
                .onSuccess { _state.value = _state.value.copy(loading = false, snapshot = it) }
                .onFailure { _state.value = _state.value.copy(loading = false, error = it.message ?: "加载失败") }
        }
    }

    fun start() = act { useCase.start(requireProcessingId()) }
    fun continueProcessing(id: String) = act { useCase.continueProcessing(id) }
    fun markPending() = act { useCase.markPending(requireProcessingId()) }
    fun resume() = act { useCase.resume(requireProcessingId()) }
    fun cancel() = act { useCase.cancel(requireProcessingId()) }
    fun voidDerivedLog(logId: String) = act { useCase.voidDerivedLog(logId) }
    fun voidFault() = act { useCase.voidFault(requireFaultId()) }
    fun voidProcessing() = act { useCase.voidProcessing(requireProcessingId()) }
    fun updateReportedAt(reportedAt: Long) = act { useCase.updateReportedAt(requireFaultId(), reportedAt) }

    /** 防抖保存处理字段：400ms 静默后才写入，并取消上一次未完成的写请求，避免乱序覆盖。 */
    fun update(check: String?, judgement: String?, cause: String?, measures: String?, verification: String?) {
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            delay(400)
            act { useCase.update(requireProcessingId(), check, judgement, cause, measures, verification) }
        }
    }

    fun showFinishDialog() { _state.value = _state.value.copy(finishDialog = true) }
    fun hideFinishDialog() { _state.value = _state.value.copy(finishDialog = false) }
    fun finish(
        result: RestoreResult,
        verification: String?,
        nextAction: String?,
        dueAt: Long?,
        dueKind: HandoverDueKind,
    ) = act {
        useCase.finish(requireProcessingId(), result, verification, nextAction, dueAt, dueKind)
    }.also { hideFinishDialog() }

    private fun requireProcessingId(): String = _state.value.snapshot?.latestProcessing?.id ?: error("没有当前处理记录")
    private fun requireFaultId(): String = _state.value.snapshot?.fault?.id ?: error("故障不存在")
    private fun act(action: suspend () -> Any) {
        _state.value = _state.value.copy(saving = true, error = null)
        viewModelScope.launch {
            runCatching { action() }
                .onSuccess { _state.value = _state.value.copy(saving = false); refresh() }
                .onFailure { _state.value = _state.value.copy(saving = false, error = it.message ?: "保存失败") }
        }
    }
}
