package com.coda.workbench.ui.attendance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coda.workbench.core.model.AttendanceInput
import com.coda.workbench.core.model.AttendanceKind
import com.coda.workbench.core.model.AttendancePatch
import com.coda.workbench.core.model.ProductionGroup
import com.coda.workbench.core.usecase.AttendanceQueryUseCase
import com.coda.workbench.core.usecase.AttendanceUseCase
import com.coda.workbench.data.local.AttendanceEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AttendanceFormState(
    val kind: AttendanceKind = AttendanceKind.NORMAL,
    val startText: String = "08:00",
    val endText: String = "18:00",
    val group: ProductionGroup? = null,
    val editingId: String? = null,
)

data class AttendanceUiState(
    val current: AttendanceEntity? = null,
    val dayAttendances: List<AttendanceEntity> = emptyList(),
    val form: AttendanceFormState = AttendanceFormState(),
    val busy: Boolean = false,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AttendanceViewModel @Inject constructor(
    private val queryUseCase: AttendanceQueryUseCase,
    private val useCase: AttendanceUseCase,
    private val clock: Clock,
) : ViewModel() {
    private val zoneId = ZoneId.systemDefault()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private val formState = MutableStateFlow(AttendanceFormState())
    private val busyState = MutableStateFlow(false)
    private val errorState = MutableStateFlow<String?>(null)
    private val todayState = MutableStateFlow(today())

    val state: StateFlow<AttendanceUiState> = combine(
        queryUseCase.observeCurrent(),
        todayState.flatMapLatest { date -> queryUseCase.observeForDate(date) },
        formState,
        busyState,
        errorState,
    ) { current, day, form, busy, error ->
        AttendanceUiState(current, day, form, busy, error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AttendanceUiState())

    /** 屏幕每次进入时刷新"今天"，覆盖跨午夜停留后当日出勤列表不翻转的场景。 */
    fun refreshToday() {
        todayState.value = today()
    }

    fun selectKind(kind: AttendanceKind) {
        val current = formState.value
        val (start, end) = when (kind) {
            AttendanceKind.NORMAL -> "08:00" to "18:00"
            AttendanceKind.TOP_DAY -> "08:00" to "20:00"
            AttendanceKind.TOP_NIGHT -> "20:00" to "08:00"
            AttendanceKind.CUSTOM -> current.startText to current.endText
        }
        val group = if (kind == AttendanceKind.TOP_DAY || kind == AttendanceKind.TOP_NIGHT) current.group else null
        formState.value = current.copy(kind = kind, startText = start, endText = end, group = group)
        errorState.value = null
    }

    fun setStartText(value: String) {
        formState.value = formState.value.copy(startText = value)
        errorState.value = null
    }

    fun setEndText(value: String) {
        formState.value = formState.value.copy(endText = value)
        errorState.value = null
    }

    fun setGroup(value: ProductionGroup?) {
        formState.value = formState.value.copy(group = value)
        errorState.value = null
    }

    fun edit(attendance: AttendanceEntity) {
        val start = Instant.ofEpochMilli(attendance.startAt).atZone(zoneId).toLocalTime()
        val end = attendance.endAt?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalTime() } ?: start
        formState.value = AttendanceFormState(
            kind = runCatching { AttendanceKind.valueOf(attendance.kind) }.getOrDefault(AttendanceKind.NORMAL),
            startText = start.format(timeFormatter),
            endText = end.format(timeFormatter),
            group = attendance.productionGroup?.let { runCatching { ProductionGroup.valueOf(it) }.getOrNull() },
            editingId = attendance.id,
        )
        errorState.value = null
    }

    fun newAttendance() {
        formState.value = AttendanceFormState()
        errorState.value = null
    }

    fun save() {
        if (busyState.value) return
        val form = formState.value
        busyState.value = true
        errorState.value = null
        viewModelScope.launch {
            runCatching {
                val startTime = parseTime(form.startText) ?: error("时间格式应为 HH:mm")
                val endTime = parseTime(form.endText) ?: error("时间格式应为 HH:mm")
                val date = form.editingId
                    ?.let { queryUseCase.findById(it)?.businessDate }
                    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    ?: today()
                val startInstant = date.atTime(startTime).atZone(zoneId).toInstant()
                val endDate = if (endTime.isAfter(startTime)) date else date.plusDays(1)
                val endInstant = endDate.atTime(endTime).atZone(zoneId).toInstant()
                if (!startInstant.isBefore(endInstant)) error("开始时间必须早于结束时间")
                val editingId = form.editingId
                if (editingId != null) {
                    useCase.update(editingId, AttendancePatch(form.kind, startInstant, endInstant, form.group))
                } else {
                    useCase.save(AttendanceInput(date, form.kind, startInstant, endInstant, form.group))
                }
            }.onSuccess {
                formState.value = AttendanceFormState()
                busyState.value = false
            }.onFailure {
                busyState.value = false
                errorState.value = it.message ?: "保存失败"
            }
        }
    }

    fun setCurrent(id: String) {
        if (busyState.value) return
        busyState.value = true
        errorState.value = null
        viewModelScope.launch {
            runCatching { useCase.setCurrent(id) }
                .onSuccess { busyState.value = false }
                .onFailure {
                    busyState.value = false
                    errorState.value = it.message ?: "操作失败"
                }
        }
    }

    private fun today(): LocalDate = LocalDate.now(clock.withZone(zoneId))

    private fun parseTime(text: String): LocalTime? =
        runCatching { LocalTime.parse(text.trim(), timeFormatter) }.getOrNull()
}
