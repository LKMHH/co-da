package com.coda.workbench.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coda.workbench.core.usecase.BackupUseCase
import com.coda.workbench.data.backup.BackupCounts
import com.coda.workbench.data.backup.BackupPreview
import com.coda.workbench.platform.BackupDestination
import com.coda.workbench.platform.BackupSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BackupUiState(
    val busy: Boolean = false,
    val error: String? = null,
    val preview: BackupPreview? = null,
    val previewSource: BackupSource? = null,
    val exportDoneCounts: BackupCounts? = null,
    val restoreDone: Boolean = false,
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val useCase: BackupUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    fun exportTo(destination: BackupDestination) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null, exportDoneCounts = null)
        viewModelScope.launch {
            runCatching { useCase.export(destination) }
                .onSuccess { _state.value = _state.value.copy(busy = false, exportDoneCounts = it.counts) }
                .onFailure {
                    _state.value = _state.value.copy(
                        busy = false,
                        error = it.message ?: "导出失败：请重试",
                    )
                }
        }
    }

    fun inspectFrom(source: BackupSource) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null, preview = null, previewSource = null)
        viewModelScope.launch {
            runCatching { useCase.inspect(source) }
                .onSuccess { _state.value = _state.value.copy(busy = false, preview = it, previewSource = source) }
                .onFailure {
                    _state.value = _state.value.copy(
                        busy = false,
                        error = it.message ?: "备份文件校验失败，文件可能已损坏",
                    )
                }
        }
    }

    fun confirmReplace() {
        val source = _state.value.previewSource ?: return
        val safetyFile = _state.value.preview?.safetyFile
        if (safetyFile == null) {
            _state.value = _state.value.copy(error = "无法保护当前数据，暂不执行恢复")
            return
        }
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            runCatching { useCase.replace(source, safetyFile) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        busy = false,
                        preview = null,
                        previewSource = null,
                        restoreDone = true,
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        busy = false,
                        error = it.message ?: "恢复失败：本机数据未改变",
                    )
                }
        }
    }

    fun dismissPreview() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(preview = null, previewSource = null)
    }

    fun dismissExportNotice() {
        _state.value = _state.value.copy(exportDoneCounts = null)
    }

    fun dismissRestoreNotice() {
        _state.value = _state.value.copy(restoreDone = false)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
