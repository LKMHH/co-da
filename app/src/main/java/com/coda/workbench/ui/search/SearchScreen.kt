package com.coda.workbench.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AssignmentTurnedIn
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coda.workbench.core.model.SearchRecordType
import com.coda.workbench.core.model.SearchResult

/** M6 查找（UI 规格 §12 / 视觉稿 §12）：输入 400ms 查询、类型/日期/状态/出勤筛选、包含已作废开关。 */
@Composable
fun SearchScreen(
    modifier: Modifier,
    onFaultDetail: (String) -> Unit,
    onHandoverDetail: (String) -> Unit,
    onWorkLogDetail: (String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("输入设备名称或关键词") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        )
        Text("记录类型", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                null to "全部",
                SearchRecordType.FAULT to "故障",
                SearchRecordType.WORK_LOG to "工作",
                SearchRecordType.HANDOVER to "交接",
            ).forEach { (type, label) ->
                FilterChip(
                    selected = if (type == null) state.recordTypes.isEmpty() else type in state.recordTypes,
                    onClick = { if (type == null) viewModel.clearRecordTypes() else viewModel.toggleRecordType(type) },
                    label = { Text(label) },
                )
            }
        }
        Text(
            "故障=设备问题；工作=你干的活（处理故障会自动生成一条工作记录）；交接=待跟进事项",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("日期范围", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchDatePreset.entries.forEach { preset ->
                FilterChip(
                    selected = state.datePreset == preset,
                    onClick = { viewModel.setDatePreset(preset) },
                    label = { Text(datePresetLabel(preset)) },
                )
            }
        }
        if (state.recordTypes == setOf(SearchRecordType.FAULT)) {
            Text("处理状态", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "DRAFT" to "草稿",
                    "IN_PROGRESS" to "处理中",
                    "PENDING_VERIFICATION" to "待验证",
                ).forEach { (status, label) ->
                    FilterChip(
                        selected = status in state.processingStatuses,
                        onClick = { viewModel.toggleProcessingStatus(status) },
                        label = { Text(label) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "ENDED" to "已结束",
                    "CANCELED" to "已取消",
                ).forEach { (status, label) ->
                    FilterChip(
                        selected = status in state.processingStatuses,
                        onClick = { viewModel.toggleProcessingStatus(status) },
                        label = { Text(label) },
                    )
                }
            }
        }
        if (state.recordTypes == setOf(SearchRecordType.WORK_LOG)) {
            Text("出勤标记", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "NORMAL" to "普通班",
                    "TOP_DAY" to "顶白班",
                    "TOP_NIGHT" to "顶夜班",
                    "CUSTOM" to "自定义",
                ).forEach { (kind, label) ->
                    FilterChip(
                        selected = kind in state.attendanceKinds,
                        onClick = { viewModel.toggleAttendanceKind(kind) },
                        label = { Text(label) },
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("包含已作废", modifier = Modifier.weight(1f))
            Switch(checked = state.includeVoided, onCheckedChange = viewModel::setIncludeVoided)
        }
        when {
            state.query.isBlank() -> {
                if (state.recent.isEmpty()) {
                    Text("输入设备名称或关键词开始查找", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("最近更新", style = MaterialTheme.typography.titleMedium)
                    state.recent.forEach { result ->
                        SearchResultRow(
                            result = result,
                            onClick = {
                                when (result.type) {
                                    SearchRecordType.FAULT -> onFaultDetail(result.id)
                                    SearchRecordType.WORK_LOG -> onWorkLogDetail(result.id)
                                    SearchRecordType.HANDOVER -> onHandoverDetail(result.id)
                                }
                            },
                        )
                    }
                }
            }
            state.loading && state.results.isEmpty() -> Text("正在查找…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.error != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.error!!, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::retry) { Text("重试查找") }
            }
            state.searched && state.results.isEmpty() -> Text("没有找到匹配记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.results.isNotEmpty() -> {
                Text("共 ${state.results.size} 条结果", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.results.forEach { result ->
                    SearchResultRow(
                        result = result,
                        onClick = {
                            when (result.type) {
                                SearchRecordType.FAULT -> onFaultDetail(result.id)
                                SearchRecordType.WORK_LOG -> onWorkLogDetail(result.id)
                                SearchRecordType.HANDOVER -> onHandoverDetail(result.id)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(result: SearchResult, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(Modifier.size(40.dp), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(searchTypeIcon(result.type), contentDescription = null, modifier = Modifier.padding(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("${searchTypeLabel(result.type)} · ${result.title}", fontWeight = FontWeight.SemiBold)
                Text(result.snippet, style = MaterialTheme.typography.bodySmall)
                if (result.expandedMatch) {
                    Text("已扩展匹配（同义词或设备别名）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(result.statusText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    result.extraTag?.let { tag ->
                        Text(tag, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

private fun searchTypeIcon(type: SearchRecordType): ImageVector = when (type) {
    SearchRecordType.FAULT -> Icons.Outlined.ReportProblem
    SearchRecordType.WORK_LOG -> Icons.Outlined.Build
    SearchRecordType.HANDOVER -> Icons.Outlined.AssignmentTurnedIn
}

private fun searchTypeLabel(type: SearchRecordType): String = when (type) {
    SearchRecordType.FAULT -> "故障"
    SearchRecordType.WORK_LOG -> "工作"
    SearchRecordType.HANDOVER -> "交接"
}

private fun datePresetLabel(preset: SearchDatePreset): String = when (preset) {
    SearchDatePreset.ALL -> "全部时间"
    SearchDatePreset.TODAY -> "今天"
    SearchDatePreset.LAST_7_DAYS -> "近7天"
}
