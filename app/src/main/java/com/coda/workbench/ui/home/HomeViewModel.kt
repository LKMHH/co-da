package com.coda.workbench.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coda.workbench.core.usecase.HomeUseCase
import com.coda.workbench.data.repository.HomeSnapshot
import com.coda.workbench.data.repository.HomeWorkView
import com.coda.workbench.data.repository.WorkKindFilter
import com.coda.workbench.platform.NotificationPermission
import com.coda.workbench.platform.NotificationPermissionState
import com.coda.workbench.platform.NotificationSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import java.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val snapshot: HomeSnapshot? = null,
    val error: String? = null,
    val view: HomeWorkView = HomeWorkView.NATURAL_DAY,
    val kindFilter: WorkKindFilter = WorkKindFilter.ALL,
    val includeVoided: Boolean = false,
    val nowMillis: Long = 0L,
    val promptNotificationPermission: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val useCase: HomeUseCase,
    private val clock: Clock,
    @ApplicationContext private val appContext: Context,
    private val notificationSettings: NotificationSettingsStore,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refresh()
        promptNotificationPermissionOnce()
    }

    fun refresh() {
        val current = _state.value
        _state.value = current.copy(loading = true, error = null, nowMillis = clock.millis())
        viewModelScope.launch {
            runCatching {
                useCase.load(current.view, current.kindFilter, current.includeVoided)
            }.onSuccess { snapshot ->
                _state.value = _state.value.copy(loading = false, snapshot = snapshot)
            }.onFailure { error ->
                _state.value = _state.value.copy(loading = false, error = error.message ?: "加载失败")
            }
        }
    }

    fun setView(view: HomeWorkView) { _state.value = _state.value.copy(view = view); refresh() }
    fun setKindFilter(filter: WorkKindFilter) { _state.value = _state.value.copy(kindFilter = filter); refresh() }
    fun setIncludeVoided(value: Boolean) { _state.value = _state.value.copy(includeVoided = value); refresh() }

    /** Android 13+ 首次启动引导一次系统通知授权（T17）：仅当未允许且从未引导过时置位一次。 */
    private fun promptNotificationPermissionOnce() {
        viewModelScope.launch {
            if (NotificationPermission.current(appContext) != NotificationPermissionState.GRANTED &&
                !notificationSettings.permissionPromptedNow()
            ) {
                notificationSettings.markPermissionPrompted()
                _state.value = _state.value.copy(promptNotificationPermission = true)
            }
        }
    }
}
