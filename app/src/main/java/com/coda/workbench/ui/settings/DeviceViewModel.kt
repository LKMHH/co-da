package com.coda.workbench.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coda.workbench.core.usecase.DeviceUseCase
import com.coda.workbench.data.local.DeviceAliasEntity
import com.coda.workbench.data.local.DeviceEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DeviceUiState(
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class DeviceViewModel @Inject constructor(
    private val useCase: DeviceUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(DeviceUiState())
    val state: StateFlow<DeviceUiState> = _state.asStateFlow()

    fun devices(): Flow<List<DeviceEntity>> = useCase.observeAll()

    fun aliases(deviceId: String): Flow<List<DeviceAliasEntity>> = useCase.observeAliases(deviceId)

    fun create(name: String, onDone: (String) -> Unit) {
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            runCatching { useCase.create(name) }
                .onSuccess { _state.value = _state.value.copy(busy = false); onDone(it) }
                .onFailure { _state.value = _state.value.copy(busy = false, error = it.message ?: "操作失败") }
        }
    }

    fun rename(id: String, name: String) = act { useCase.rename(id, name) }

    fun addAlias(deviceId: String, alias: String) = act { useCase.addAlias(deviceId, alias) }

    fun removeAlias(deviceId: String, alias: String) = act { useCase.removeAlias(deviceId, alias) }

    fun setActive(id: String, active: Boolean) = act { useCase.setActive(id, active) }

    private fun act(action: suspend () -> Unit) {
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            runCatching { action() }
                .onSuccess { _state.value = _state.value.copy(busy = false) }
                .onFailure { _state.value = _state.value.copy(busy = false, error = it.message ?: "操作失败") }
        }
    }
}
