package com.coda.workbench.ui.handover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coda.workbench.core.usecase.FaultUseCase
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
    private val faultUseCase: FaultUseCase,
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

    /**
     * 「继续处理故障」：以事项来源的处理记录为基准，创建同一故障的新 IN_PROGRESS 处理记录，
     * 返回其 faultId 供界面跳转；失败时把原因写入 state.error（界面 ErrorNotice 展示）。
     */
    suspend fun continueFaultProcessing(): String? {
        val item = _state.value.item ?: return null
        val sourceId = item.sourceId ?: return null
        return runCatching { faultUseCase.continueProcessing(sourceId).faultId }
            .getOrElse {
                val message = when {
                    it.message?.contains("cannot continue") == true -> "该故障已恢复或已作废，不能继续处理，请新建故障记录"
                    it.message?.contains("terminal") == true -> "该处理记录尚未结束，不能继续处理"
                    else -> it.message ?: "无法继续处理故障"
                }
                _state.value = _state.value.copy(error = message)
                null
            }
    }

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
