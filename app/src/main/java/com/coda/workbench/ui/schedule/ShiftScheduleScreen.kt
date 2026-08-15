package com.coda.workbench.ui.schedule

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
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coda.workbench.core.model.ProductionGroup
import com.coda.workbench.core.model.ShiftSlotPatch
import com.coda.workbench.core.model.ShiftType
import com.coda.workbench.data.local.ShiftSlotEntity
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/** M5 本月排班：白班起点确认 + 班次建议列表 + 未来班次修正（只影响未来，不改写历史快照）。 */
@Composable
fun MonthScheduleScreen(modifier: Modifier, viewModel: ShiftScheduleViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var editingSlot by remember { mutableStateOf<ShiftSlotEntity?>(null) }
    val schedule = state.schedule
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.CalendarMonth, contentDescription = null)
                Text("本月排班 · ${monthLabel(schedule?.month ?: YearMonth.now().toString())}", fontWeight = FontWeight.SemiBold)
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (schedule == null) {
            Text("正在加载…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        if (schedule.confirmedAt == null) {
            Text("白班起点", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("每月 1 号确认本月 08:00-20:00 由哪个班组上白班", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column {
                listOf(ProductionGroup.A to "甲班", ProductionGroup.B to "乙班").forEach { (group, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { viewModel.selectWhiteDayGroup(group) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = state.selectedWhiteDayGroup == group, onClick = { viewModel.selectWhiteDayGroup(group) })
                        Text(label)
                    }
                }
            }
            Text("尚未确认", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CalendarMonth, contentDescription = null)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("排班未确认不影响记录", fontWeight = FontWeight.SemiBold)
                        Text("这不会阻止你记录工作；确认后生成普通日、15 号和月底转班建议，系统不猜测班组", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            CodaButton(
                onClick = { scope.launch { viewModel.confirm() } },
                enabled = state.selectedWhiteDayGroup != null && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.busy) "生成中…" else "确认本月白班起点") }
        } else {
            val whiteLabel = if (schedule.whiteDayGroup == ProductionGroup.A) "甲班" else "乙班"
            Text("白班起点：$whiteLabel · 已确认", fontWeight = FontWeight.SemiBold)
            Text("班次建议（${schedule.slots.size} 段）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("点击未开始的班次可修正；已开始的班次和历史快照不可修改", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            schedule.slots.forEach { slot ->
                SlotRow(
                    slot = slot,
                    nowMillis = state.nowMillis,
                    onClick = { if (slot.startAt > state.nowMillis) editingSlot = slot },
                )
            }
            Text(
                "跨月说明：00:00-08:00 归属上一班次开始日；08:00-20:00 按新月份排班",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    editingSlot?.let { slot ->
        SlotEditDialog(
            slot = slot,
            busy = state.busy,
            onDismiss = { editingSlot = null },
            onSave = { patch ->
                viewModel.updateSlot(slot.id, patch)
                editingSlot = null
            },
        )
    }
}

@Composable
private fun SlotRow(slot: ShiftSlotEntity, nowMillis: Long, onClick: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    val start = Instant.ofEpochMilli(slot.startAt).atZone(zone).toLocalTime().format(timeFormat)
    val end = Instant.ofEpochMilli(slot.endAt).atZone(zone).toLocalTime().format(timeFormat)
    val groupLabel = if (slot.group == "A") "甲班" else "乙班"
    val typeLabel = if (slot.shiftType == "DAY") "白班" else "夜班"
    val editable = slot.startAt > nowMillis
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = if (slot.isShiftChange) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (slot.isShiftChange) Icons.Outlined.SwapHoriz else Icons.Outlined.CalendarMonth, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(
                    "${slot.businessDate} · $groupLabel · $typeLabel ${start}-${end}" +
                        (if (slot.isShiftChange) "（转班）" else "") +
                        (if (slot.source == "MANUAL") "（手动）" else ""),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (editable) "点击修正" else "已开始，不可修改",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (editable) Text("修改", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SlotEditDialog(slot: ShiftSlotEntity, busy: Boolean, onDismiss: () -> Unit, onSave: (ShiftSlotPatch) -> Unit) {
    val zone = ZoneId.systemDefault()
    val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    val startTime = Instant.ofEpochMilli(slot.startAt).atZone(zone).toLocalTime()
    val endTime = Instant.ofEpochMilli(slot.endAt).atZone(zone).toLocalTime()
    var group by remember { mutableStateOf(if (slot.group == "A") ProductionGroup.A else ProductionGroup.B) }
    var shiftType by remember { mutableStateOf(if (slot.shiftType == "DAY") ShiftType.DAY else ShiftType.NIGHT) }
    var startText by remember { mutableStateOf(startTime.format(timeFormat)) }
    var endText by remember { mutableStateOf(endTime.format(timeFormat)) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("修正班次 · ${slot.businessDate}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("班组", style = MaterialTheme.typography.labelLarge)
                Column {
                    listOf(ProductionGroup.A to "甲班", ProductionGroup.B to "乙班").forEach { (g, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { group = g },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = group == g, onClick = { group = g })
                            Text(label)
                        }
                    }
                }
                Text("班别", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = shiftType == ShiftType.DAY, onClick = { shiftType = ShiftType.DAY }, label = { Text("白班") })
                    FilterChip(selected = shiftType == ShiftType.NIGHT, onClick = { shiftType = ShiftType.NIGHT }, label = { Text("夜班") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(startText, { startText = it }, Modifier.weight(1f), label = { Text("开始 HH:mm") }, singleLine = true)
                    OutlinedTextField(endText, { endText = it }, Modifier.weight(1f), label = { Text("结束 HH:mm") }, singleLine = true)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    val start = runCatching { LocalTime.parse(startText.trim(), timeFormat) }.getOrNull()
                    val end = runCatching { LocalTime.parse(endText.trim(), timeFormat) }.getOrNull()
                    val date = runCatching { java.time.LocalDate.parse(slot.businessDate) }.getOrNull()
                    if (start == null || end == null || date == null) {
                        error = "时间格式应为 HH:mm"
                        return@TextButton
                    }
                    val startInstant = date.atTime(start).atZone(zone).toInstant()
                    val endDate = if (end.isAfter(start)) date else date.plusDays(1)
                    val endInstant = endDate.atTime(end).atZone(zone).toInstant()
                    if (!startInstant.isBefore(endInstant)) {
                        error = "开始时间必须早于结束时间"
                        return@TextButton
                    }
                    onSave(ShiftSlotPatch(group, shiftType, startInstant, endInstant))
                },
            ) { Text(if (busy) "保存中…" else "保存修正") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}

private fun monthLabel(month: String?): String {
    if (month == null) return ""
    return runCatching { YearMonth.parse(month) }
        .map { "${it.year}年${it.monthValue}月" }
        .getOrDefault(month)
}
