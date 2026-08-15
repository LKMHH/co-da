package com.coda.workbench.ui.handover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coda.workbench.core.usecase.CreateHandoverInput
import com.coda.workbench.core.usecase.HandoverDueKind
import com.coda.workbench.core.usecase.HandoverUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HandoverCreateState(
    val summary: String = "",
    val nextAction: String = "",
    val dueKind: HandoverDueKind? = null,
    val dueAtText: String = "",
    val handoverGroup: String? = null,
    val hazardNote: String = "",
    val saving: Boolean = false,
    val savedId: String? = null,
    val error: String? = null,
)

@HiltViewModel
class HandoverCreateViewModel @Inject constructor(
    private val useCase: HandoverUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(HandoverCreateState())
    val state: StateFlow<HandoverCreateState> = _state.asStateFlow()

    fun setSummary(value: String) { _state.value = _state.value.copy(summary = value, error = null) }
    fun setNextAction(value: String) { _state.value = _state.value.copy(nextAction = value, error = null) }
    fun setDueKind(kind: HandoverDueKind?) { _state.value = _state.value.copy(dueKind = kind, error = null) }
    fun setDueAtText(value: String) { _state.value = _state.value.copy(dueAtText = value, error = null) }
    fun setHandoverGroup(value: String?) { _state.value = _state.value.copy(handoverGroup = value, error = null) }
    fun setHazardNote(value: String) { _state.value = _state.value.copy(hazardNote = value, error = null) }
    fun reset() { _state.value = HandoverCreateState() }

    fun create() {
        val current = _state.value
        if (current.saving || current.savedId != null) return
        if (current.nextAction.isBlank()) {
            _state.value = current.copy(error = "下一步动作必填")
            return
        }
        if (current.dueKind == null) {
            _state.value = current.copy(error = "跟进期限必填")
            return
        }
        val dueAt = if (current.dueKind == HandoverDueKind.SPECIFIC) {
            runCatching {
                java.time.LocalDate.parse(current.dueAtText).atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant().toEpochMilli()
            }.getOrNull()
        } else null
        if (current.dueKind == HandoverDueKind.SPECIFIC && dueAt == null) {
            _state.value = current.copy(error = "指定时间格式应为 yyyy-MM-dd")
            return
        }
        _state.value = current.copy(saving = true, error = null)
        viewModelScope.launch {
            runCatching {
                useCase.create(
                    CreateHandoverInput(
                        summary = current.summary,
                        nextAction = current.nextAction,
                        dueKind = current.dueKind!!,
                        dueAt = dueAt,
                        handoverGroup = current.handoverGroup,
                        potentialHazardNote = current.hazardNote,
                    ),
                )
            }
                .onSuccess { _state.value = _state.value.copy(saving = false, savedId = it) }
                .onFailure { _state.value = _state.value.copy(saving = false, error = it.message ?: "保存失败") }
        }
    }
}
