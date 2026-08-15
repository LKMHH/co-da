package com.coda.workbench.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coda.workbench.platform.NotificationSettingsUiState
import com.coda.workbench.platform.NotificationSettingsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class NotificationSettingsViewModel @Inject constructor(
    private val useCase: NotificationSettingsUseCase,
) : ViewModel() {
    private val permissionState = MutableStateFlow(useCase.permissionState())

    val state: StateFlow<NotificationSettingsUiState> = combine(
        useCase.observeEnabled(),
        permissionState,
    ) { enabled, permission ->
        NotificationSettingsUiState(enabled, permission)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationSettingsUiState())

    fun refreshPermission() {
        permissionState.value = useCase.permissionState()
    }

    fun openSystemSettingsIntent() = useCase.openSystemSettingsIntent()

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { useCase.setEnabled(enabled) }
        }
    }
}
