package com.coda.workbench.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coda.workbench.data.backup.BackupCounts
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** M7 备份与恢复（UI 规格 §13 / 视觉稿 §17）：导出区与恢复区两个平级区块，四步恢复流程。 */
@Composable
fun BackupScreen(modifier: Modifier, viewModel: BackupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resolver = context.contentResolver

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { viewModel.exportTo(UriBackupDestination(resolver, it)) }
    }
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.inspectFrom(UriBackupSource(resolver, it)) }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.error?.let { error ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::clearError) { Text("知道了") }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.FileUpload, contentDescription = null)
                    Text("导出当前数据", fontWeight = FontWeight.SemiBold)
                }
                Text("将故障、工作、交接、出勤、排班、设备名称及别名保存为备份文件。", style = MaterialTheme.typography.bodySmall)
                Text("文件未加密，请妥善保管。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = {
                        val name = "coda-备份-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.coda-backup"
                        exportLauncher.launch(name)
                    },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.busy) "处理中…" else "导出备份") }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = null)
                    Text("恢复本机数据", fontWeight = FontWeight.SemiBold)
                }
                Text("导入备份会替换本机业务数据。", style = MaterialTheme.typography.bodySmall)
                Text("手机通知等设置不会改变。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(
                    onClick = { pickLauncher.launch(arrayOf("*/*")) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("选择备份文件") }
            }
        }
    }

    state.preview?.let { preview ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) viewModel.dismissPreview() },
            title = { Text("恢复本机数据") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("将恢复以下记录数量：", fontWeight = FontWeight.SemiBold)
                    Text(countsLines(preview.counts))
                    Text(
                        "当前数据已安全备份。\n将替换本机业务数据；手机通知等设置不会改变。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                Button(onClick = viewModel::confirmReplace, enabled = !state.busy) {
                    Text(if (state.busy) "正在替换本机数据…" else "确认替换本机数据")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPreview, enabled = !state.busy) { Text("取消") }
            },
        )
    }

    state.exportDoneCounts?.let { counts ->
        AlertDialog(
            onDismissRequest = viewModel::dismissExportNotice,
            title = { Text("导出完成") },
            text = {
                Text("已导出 ${counts.faults + counts.workLogs + counts.handoverItems + counts.devices + counts.attendance + counts.shiftSlots + counts.shiftPlans + counts.processings + counts.deviceAliases} 条记录。\n文件未加密，请妥善保管。")
            },
            confirmButton = { TextButton(onClick = viewModel::dismissExportNotice) { Text("知道了") } },
        )
    }

    if (state.restoreDone) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRestoreNotice,
            title = { Text("恢复完成") },
            text = { Text("本机数据已替换。未完成草稿仍可继续编辑；已作废记录默认隐藏，可在筛选中查看。") },
            confirmButton = { TextButton(onClick = viewModel::dismissRestoreNotice) { Text("知道了") } },
        )
    }
}

private fun countsLines(counts: BackupCounts): String = buildString {
    append("设备名称：${counts.devices}\n")
    append("设备别名：${counts.deviceAliases}\n")
    append("故障记录：${counts.faults}\n")
    append("处理记录：${counts.processings}\n")
    append("工作记录：${counts.workLogs}\n")
    append("交接事项：${counts.handoverItems}\n")
    append("出勤记录：${counts.attendance}\n")
    append("排班月份：${counts.shiftPlans}\n")
    append("班次：${counts.shiftSlots}")
}
