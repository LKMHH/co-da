package com.coda.workbench.ui.handover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coda.workbench.core.usecase.HandoverUseCase
import com.coda.workbench.data.local.HandoverItemEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HandoverDetailUiState(
    val loading: Boolean = true,
    val item: HandoverItemEntity? = null,
    val faultId: String? = null,
    val error: String? = null,
    val saving: Boolean = false,
)

@HiltViewModel
class HandoverViewModel @Inject constructor(
    private val useCase: HandoverUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(HandoverDetailUiState())
    val state: StateFlow<HandoverDetailUiState> = _state.asStateFlow()
    private var itemId: String? = null

    fun load(id: String) {
        if (itemId == id && _state.value.item != null) return
        itemId = id
        refresh()
    }

    fun refresh() {
        val id = itemId ?: return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching {
                val item = useCase.load(id)
                val faultId = item?.let { useCase.faultLink(it) }
                _state.value = _state.value.copy(loading = false, item = item, faultId = faultId)
            }.onFailure {
                _state.value = _state.value.copy(loading = false, error = it.message ?: "加载失败")
            }
        }
    }

    fun markHandedOver() = act { useCase.markHandedOver(requireId()) }
    fun markInProgress() = act { useCase.markInProgress(requireId()) }
    fun complete() = act { useCase.complete(requireId()) }
    fun cancel() = act { useCase.cancel(requireId()) }
    fun void() = act { useCase.void(requireId()) }

    private fun requireId(): String = itemId ?: error("事项不存在")

    private fun act(action: suspend () -> Unit) {
        _state.value = _state.value.copy(saving = true, error = null)
        viewModelScope.launch {
            runCatching { action() }
                .onSuccess { _state.value = _state.value.copy(saving = false); refresh() }
                .onFailure { _state.value = _state.value.copy(saving = false, error = it.message ?: "操作失败") }
        }
    }
}
