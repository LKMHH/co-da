package com.coda.workbench.ui.work

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coda.workbench.core.usecase.AttendanceQueryUseCase
import com.coda.workbench.core.usecase.DeviceUseCase
import com.coda.workbench.core.usecase.HomeUseCase
import com.coda.workbench.core.usecase.ManualWorkUseCase
import com.coda.workbench.data.local.AttendanceEntity
import com.coda.workbench.data.local.DeviceEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ManualWorkUiState(
    val content: String = "",
    val deviceName: String = "",
    val recentDevices: List<DeviceEntity> = emptyList(),
    val workResult: String = "",
    val area: String = "",
    val arrangementSource: String = "",
    val saving: Boolean = false,
    val savedLogId: String? = null,
    val editId: String? = null,
    val error: String? = null,
)

@HiltViewModel
class ManualWorkViewModel @Inject constructor(
    private val useCase: ManualWorkUseCase,
    private val homeUseCase: HomeUseCase,
    private val attendanceQuery: AttendanceQueryUseCase,
    private val devices: DeviceUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(ManualWorkUiState())

    init {
        viewModelScope.launch { devices.observeRecent().collect { _state.value = _state.value.copy(recentDevices = it) } }
    }
    val state: StateFlow<ManualWorkUiState> = _state.asStateFlow()

    /** 当前出勤（只读上下文）：记录页顶部展示真实类型与时间，出勤修正后自动更新。 */
    val currentAttendance: StateFlow<AttendanceEntity?> = attendanceQuery.observeCurrent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setContent(value: String) { _state.value = _state.value.copy(content = value, error = null) }
    fun setDeviceName(value: String) { _state.value = _state.value.copy(deviceName = value, error = null) }
    fun setWorkResult(value: String) { _state.value = _state.value.copy(workResult = value, error = null) }
    fun setArea(value: String) { _state.value = _state.value.copy(area = value, error = null) }
    fun setArrangementSource(value: String) { _state.value = _state.value.copy(arrangementSource = value, error = null) }
    fun reset() { _state.value = ManualWorkUiState() }

    fun loadForEdit(id: String) {
        viewModelScope.launch {
            runCatching { homeUseCase.loadWorkLog(id) }
                .onSuccess { log ->
                    log?.let {
                        _state.value = _state.value.copy(
                            editId = id,
                            content = it.content,
                            deviceName = it.deviceNameSnapshot.orEmpty(),
                            workResult = it.workResult.orEmpty(),
                            area = it.area.orEmpty(),
                            arrangementSource = it.arrangementSource.orEmpty(),
                        )
                    }
                }
                .onFailure { _state.value = _state.value.copy(error = it.message ?: "加载失败") }
        }
    }

    /** 保存（新建或编辑）并返回记录 id；成功后由界面拿 id 跳详情。 */
    suspend fun saveOnce(): String? {
        val current = _state.value
        if (current.savedLogId != null && current.editId == null) return current.savedLogId
        if (current.content.isBlank()) {
            _state.value = current.copy(error = "工作内容不能为空")
            return null
        }
        _state.value = current.copy(saving = true, error = null)
        return try {
            val id = if (current.editId != null) {
                useCase.update(current.editId, current.content, current.workResult, current.area, current.arrangementSource, current.deviceName.takeIf { it.isNotBlank() })
                current.editId!!
            } else {
                val newId = useCase.save(current.content)
                if (current.workResult.isNotBlank() || current.area.isNotBlank() || current.arrangementSource.isNotBlank() || current.deviceName.isNotBlank()) {
                    useCase.update(newId, current.content, current.workResult, current.area, current.arrangementSource, current.deviceName.takeIf { it.isNotBlank() })
                }
                newId
            }
            _state.value = _state.value.copy(saving = false, savedLogId = id)
            id
        } catch (e: Exception) {
            _state.value = _state.value.copy(saving = false, error = e.message ?: "保存失败")
            null
        }
    }

    fun reSnapAttendance() {
        val id = _state.value.editId ?: return
        _state.value = _state.value.copy(saving = true, error = null)
        viewModelScope.launch {
            runCatching { useCase.reSnapAttendance(id) }
                .onSuccess { _state.value = _state.value.copy(saving = false, error = "已按当前出勤修正快照") }
                .onFailure { _state.value = _state.value.copy(saving = false, error = it.message ?: "操作失败") }
        }
    }
}
