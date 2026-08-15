package com.coda.workbench.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coda.workbench.core.model.ProductionGroup
import com.coda.workbench.core.model.ShiftSlotPatch
import com.coda.workbench.core.usecase.ShiftScheduleQueryUseCase
import com.coda.workbench.core.usecase.ShiftScheduleUseCase
import com.coda.workbench.data.repository.MonthScheduleUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ShiftScheduleUiState(
    val schedule: MonthScheduleUiState? = null,
    val selectedWhiteDayGroup: ProductionGroup? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val nowMillis: Long = 0L,
)

@HiltViewModel
class ShiftScheduleViewModel @Inject constructor(
    private val queryUseCase: ShiftScheduleQueryUseCase,
    private val useCase: ShiftScheduleUseCase,
    private val clock: Clock,
) : ViewModel() {
    private val selectedGroup = MutableStateFlow<ProductionGroup?>(null)
    private val busyState = MutableStateFlow(false)
    private val errorState = MutableStateFlow<String?>(null)

    val state: StateFlow<ShiftScheduleUiState> = combine(
        queryUseCase.observeCurrentMonth(),
        selectedGroup,
        busyState,
        errorState,
    ) { schedule, selected, busy, error ->
        ShiftScheduleUiState(schedule, selected, busy, error, clock.millis())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShiftScheduleUiState())

    fun selectWhiteDayGroup(group: ProductionGroup) {
        selectedGroup.value = group
        errorState.value = null
    }

    fun confirm() {
        if (busyState.value) return
        val group = selectedGroup.value
        if (group == null) {
            errorState.value = "请先选择本月白班起点班组"
            return
        }
        busyState.value = true
        errorState.value = null
        viewModelScope.launch {
            runCatching { useCase.confirmMonth(queryUseCase.currentMonth(), group) }
                .onSuccess { busyState.value = false }
                .onFailure {
                    busyState.value = false
                    errorState.value = it.message ?: "确认失败"
                }
        }
    }

    fun updateSlot(id: String, patch: ShiftSlotPatch) {
        if (busyState.value) return
        busyState.value = true
        errorState.value = null
        viewModelScope.launch {
            runCatching { useCase.updateFutureSlot(id, patch) }
                .onSuccess { busyState.value = false }
                .onFailure {
                    busyState.value = false
                    errorState.value = it.message ?: "修改失败"
                }
        }
    }
}
