package com.coda.workbench.ui.work

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coda.workbench.core.usecase.HomeUseCase
import com.coda.workbench.core.usecase.ManualWorkUseCase
import com.coda.workbench.data.local.WorkLogEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WorkLogDetailUiState(
    val loading: Boolean = true,
    val log: WorkLogEntity? = null,
    val faultId: String? = null,
    val saving: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class WorkLogDetailViewModel @Inject constructor(
    private val useCase: HomeUseCase,
    private val manualWorkUseCase: ManualWorkUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(WorkLogDetailUiState())
    val state: StateFlow<WorkLogDetailUiState> = _state.asStateFlow()
    private var logId: String? = null

    fun load(id: String) {
        logId = id
        refresh()
    }

    fun refresh() {
        val id = logId ?: return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching {
                val log = useCase.loadWorkLog(id)
                val faultId = log?.takeIf { it.kind == "FAULT_DERIVED" }?.let { useCase.faultIdForDerivedLog(it) }
                _state.value = _state.value.copy(loading = false, log = log, faultId = faultId)
            }.onFailure {
                _state.value = _state.value.copy(loading = false, error = it.message ?: "加载失败")
            }
        }
    }

    fun voidManual() {
        val id = logId ?: return
        _state.value = _state.value.copy(saving = true, error = null)
        viewModelScope.launch {
            runCatching { manualWorkUseCase.void(id) }
                .onSuccess { _state.value = _state.value.copy(saving = false); refresh() }
                .onFailure { _state.value = _state.value.copy(saving = false, error = it.message ?: "操作失败") }
        }
    }
}
