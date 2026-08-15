package com.coda.workbench.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coda.workbench.core.model.SearchFilters
import com.coda.workbench.core.model.SearchRecordType
import com.coda.workbench.core.model.SearchResult
import com.coda.workbench.core.usecase.SearchUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class SearchDatePreset { ALL, TODAY, LAST_7_DAYS }

data class SearchUiState(
    val query: String = "",
    val recordTypes: Set<SearchRecordType> = emptySet(),
    val datePreset: SearchDatePreset = SearchDatePreset.ALL,
    val processingStatuses: Set<String> = emptySet(),
    val attendanceKinds: Set<String> = emptySet(),
    val includeVoided: Boolean = false,
    val results: List<SearchResult> = emptyList(),
    val loading: Boolean = false,
    val searched: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val useCase: SearchUseCase,
    private val clock: Clock,
) : ViewModel() {
    private val zoneId = ZoneId.systemDefault()
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var queryJob: Job? = null

    fun setQuery(value: String) {
        _state.value = _state.value.copy(query = value, error = null)
        queryJob?.cancel()
        if (value.isBlank()) {
            _state.value = _state.value.copy(results = emptyList(), loading = false, searched = false)
            return
        }
        _state.value = _state.value.copy(loading = true)
        queryJob = viewModelScope.launch {
            delay(400) // 视觉稿 §12.3：输入后 400ms 查询
            runSearch()
        }
    }

    fun toggleRecordType(type: SearchRecordType) {
        val current = _state.value.recordTypes
        val next = if (type in current) current - type else current + type
        // 筛选入口随类型隐藏时，清掉不再可见的残留筛选，避免隐形生效
        _state.value = _state.value.copy(
            recordTypes = next,
            processingStatuses = if (SearchRecordType.FAULT in next) _state.value.processingStatuses else emptySet(),
            attendanceKinds = if (SearchRecordType.WORK_LOG in next) _state.value.attendanceKinds else emptySet(),
        )
        refresh()
    }

    fun clearRecordTypes() {
        _state.value = _state.value.copy(recordTypes = emptySet())
        refresh()
    }

    fun setDatePreset(preset: SearchDatePreset) {
        _state.value = _state.value.copy(datePreset = preset)
        refresh()
    }

    fun toggleProcessingStatus(status: String) {
        val current = _state.value.processingStatuses
        _state.value = _state.value.copy(processingStatuses = if (status in current) current - status else current + status)
        refresh()
    }

    fun toggleAttendanceKind(kind: String) {
        val current = _state.value.attendanceKinds
        _state.value = _state.value.copy(attendanceKinds = if (kind in current) current - kind else current + kind)
        refresh()
    }

    fun setIncludeVoided(value: Boolean) {
        _state.value = _state.value.copy(includeVoided = value)
        refresh()
    }

    fun retry() {
        refresh()
    }

    private fun refresh() {
        val current = _state.value
        if (current.query.isBlank()) {
            _state.value = current.copy(results = emptyList(), loading = false, searched = false)
            return
        }
        _state.value = current.copy(loading = true, error = null)
        queryJob?.cancel()
        queryJob = viewModelScope.launch { runSearch() }
    }

    private suspend fun runSearch() {
        val current = _state.value
        val today = LocalDate.now(clock.withZone(zoneId))
        val (dateFrom, dateTo) = when (current.datePreset) {
            SearchDatePreset.ALL -> null to null
            SearchDatePreset.TODAY -> today to today
            SearchDatePreset.LAST_7_DAYS -> today.minusDays(6) to today
        }
        val filters = SearchFilters(
            recordTypes = current.recordTypes,
            dateFrom = dateFrom,
            dateTo = dateTo,
            processingStatuses = current.processingStatuses,
            attendanceKinds = current.attendanceKinds,
            includeVoided = current.includeVoided,
        )
        val results = try {
            useCase.search(current.query, filters).first()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 防抖取消不是业务失败，不能误报"查找失败"，也不能被吞掉
        } catch (e: Exception) {
            _state.value = _state.value.copy(loading = false, searched = true, error = "查找失败：数据库异常")
            return
        }
        _state.value = _state.value.copy(results = results, loading = false, searched = true, error = null)
    }
}
