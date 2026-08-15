package com.coda.workbench.ui.attendance

import com.coda.workbench.ui.theme.CodaButton

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coda.workbench.core.model.AttendanceKind
import com.coda.workbench.core.model.ProductionGroup
import com.coda.workbench.data.local.AttendanceEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/** M5 出勤与排班入口页：当前出勤 + 当日多段出勤列表 + 修正/新增表单 + 本月排班入口。 */
@Composable
fun AttendanceScreen(modifier: Modifier, onOpenSchedule: () -> Unit, viewModel: AttendanceViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { viewModel.refreshToday() }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.current?.let { current ->
            AttendanceSummaryCard(current, state.dayAttendances.size, onEdit = { viewModel.edit(current) })
        }
        Card(modifier = Modifier.fillMaxWidth(), onClick = onOpenSchedule) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CalendarMonth, contentDescription = null)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("本月排班", fontWeight = FontWeight.SemiBold)
                    Text("生成当月班次建议并修正未来班次", style = MaterialTheme.typography.bodySmall)
                }
                Text("查看", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("当日出勤", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { viewModel.newAttendance() }) { Text("添加") }
        }
        if (state.dayAttendances.isEmpty()) {
            Text("今天还没有其他出勤记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.dayAttendances.forEach { attendance ->
            AttendanceRow(
                attendance = attendance,
                onEdit = { viewModel.edit(attendance) },
                onSetCurrent = { viewModel.setCurrent(attendance.id) },
                busy = state.busy,
            )
        }
        Text("修正 / 新增出勤", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            if (state.form.editingId != null) "正在修正所选出勤；保存只影响后续记录，不改写已保存的工作快照" else "保存新出勤；本机还没有当前出勤时会自动设为当前",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(AttendanceKind.NORMAL to "普通班", AttendanceKind.TOP_DAY to "顶白班", AttendanceKind.TOP_NIGHT to "顶夜班", AttendanceKind.CUSTOM to "自定义").forEach { (kind, label) ->
                FilterChip(selected = state.form.kind == kind, onClick = { viewModel.selectKind(kind) }, label = { Text(label) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(state.form.startText, viewModel::setStartText, Modifier.weight(1f), label = { Text("开始 HH:mm") }, singleLine = true)
            OutlinedTextField(state.form.endText, viewModel::setEndText, Modifier.weight(1f), label = { Text("结束 HH:mm") }, singleLine = true)
        }
        Text("结束早于或等于开始视为次日结束（顶夜班跨午夜）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.form.kind == AttendanceKind.TOP_NIGHT) {
            Text("顶夜班跨午夜：如 20:00 至次日 08:00", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        if (state.form.kind == AttendanceKind.TOP_DAY || state.form.kind == AttendanceKind.TOP_NIGHT) {
            Text("顶班班组", style = MaterialTheme.typography.labelLarge)
            Column {
                listOf(ProductionGroup.A to "甲班", ProductionGroup.B to "乙班").forEach { (group, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { viewModel.setGroup(group) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = state.form.group == group, onClick = { viewModel.setGroup(group) })
                        Text(label)
                    }
                }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        CodaButton(
            onClick = { scope.launch { viewModel.save() } },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state.busy) "保存中…" else if (state.form.editingId != null) "保存出勤修正" else "保存新出勤") }
    }
}

@Composable
private fun AttendanceSummaryCard(current: AttendanceEntity, dayCount: Int, onEdit: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(current.startAt).atZone(zone)
    val end = current.endAt?.let { Instant.ofEpochMilli(it).atZone(zone) }
    val timeLabel = buildString {
        append(start.format(DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE)))
        if (end != null) append(" 至 ").append(end.format(DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE)))
    }
    val groupLabel = current.productionGroup?.let { if (it == "A") "甲班" else "乙班" }
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onEdit,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CalendarMonth, contentDescription = null)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("当前出勤", style = MaterialTheme.typography.labelLarge)
                Text(
                    attendanceKindLabel(current.kind) + (groupLabel?.let { " · $it" } ?: ""),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(timeLabel, style = MaterialTheme.typography.bodySmall)
                Text("点击修正当前出勤", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (dayCount > 0) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun AttendanceRow(
    attendance: AttendanceEntity,
    onEdit: () -> Unit,
    onSetCurrent: () -> Unit,
    busy: Boolean,
) {
    val zone = ZoneId.systemDefault()
    val format = DateTimeFormatter.ofPattern("HH:mm")
    val start = Instant.ofEpochMilli(attendance.startAt).atZone(zone).toLocalTime().format(format)
    val end = attendance.endAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime().format(format) } ?: ""
    val groupLabel = attendance.productionGroup?.let { if (it == "A") " · 甲班" else " · 乙班" } ?: ""
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (attendance.isCurrent) Icons.Outlined.CheckCircle else Icons.Outlined.EditNote, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(
                    attendanceKindLabel(attendance.kind) + groupLabel + (if (attendance.isCurrent) "（当前）" else ""),
                    fontWeight = FontWeight.SemiBold,
                )
                Text("$start-$end", style = MaterialTheme.typography.bodySmall)
            }
            if (!attendance.isCurrent) {
                TextButton(onClick = onSetCurrent, enabled = !busy) { Text("设为当前") }
            }
            TextButton(onClick = onEdit, enabled = !busy) { Text("修正") }
        }
    }
}

private fun attendanceKindLabel(kind: String): String = when (kind) {
    "TOP_DAY" -> "顶白班"
    "TOP_NIGHT" -> "顶夜班"
    "CUSTOM" -> "自定义出勤"
    else -> "普通班"
}
