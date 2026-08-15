package com.coda.workbench

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AssignmentTurnedIn
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PendingActions
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import com.coda.workbench.core.usecase.RestoreResult
import com.coda.workbench.core.usecase.HandoverDueKind
import com.coda.workbench.core.usecase.BackupUseCase
import com.coda.workbench.core.usecase.RestoreRecoveryState
import com.coda.workbench.data.local.FaultProcessingEntity
import com.coda.workbench.data.repository.HomeWorkView
import com.coda.workbench.platform.NotificationChannels
import com.coda.workbench.platform.NotificationScheduler
import com.coda.workbench.ui.attendance.AttendanceScreen
import com.coda.workbench.ui.fault.FaultDetailViewModel
import com.coda.workbench.ui.fault.FaultEntryViewModel
import com.coda.workbench.ui.handover.HandoverCreateViewModel
import com.coda.workbench.ui.handover.HandoverViewModel
import com.coda.workbench.ui.home.HomeUiState
import com.coda.workbench.ui.home.HomeViewModel
import com.coda.workbench.ui.schedule.MonthScheduleScreen
import com.coda.workbench.ui.search.SearchScreen
import com.coda.workbench.ui.settings.BackupScreen
import com.coda.workbench.ui.settings.DeviceViewModel
import com.coda.workbench.ui.settings.NotificationSettingsScreen
import com.coda.workbench.ui.work.ManualWorkViewModel
import com.coda.workbench.ui.work.WorkLogDetailViewModel
import com.coda.workbench.ui.theme.CodaTheme
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var notificationScheduler: NotificationScheduler

    @Inject
    lateinit var backupUseCase: BackupUseCase

    private val restoreRecovery = MutableStateFlow<RestoreRecoveryState?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationChannels.ensure(this)
        lifecycleScope.launch { runCatching { notificationScheduler.reconcileAll() } }
        lifecycleScope.launch {
            // M7：启动看到 PREPARED 一律按安全备份回滚，之后提示恢复中断（不区分事务此前是否提交）
            restoreRecovery.value = runCatching { backupUseCase.recoverInterruptedRestore() }
                .getOrNull()
                ?.takeIf { it.interrupted }
        }
        setContent {
            val recovery by restoreRecovery.collectAsStateWithLifecycle()
            CodaTheme {
                CodaApp(
                    restoreRecovery = recovery,
                    onInterruptAcknowledged = { restoreRecovery.value = null },
                )
            }
        }
    }
}

private sealed interface AppRoute {
    data object Home : AppRoute
    data object FaultEntry : AppRoute
    data class FaultDetail(val faultId: String) : AppRoute
    data object Search : AppRoute
    data object Handover : AppRoute
    data object Drafts : AppRoute
    data object ManualWork : AppRoute
    data object Settings : AppRoute
    data class HandoverDetail(val id: String) : AppRoute
    data class WorkLogDetail(val logId: String) : AppRoute
    data object FaultList : AppRoute
    data object HandoverCreate : AppRoute
    data object DeviceList : AppRoute
    data class DeviceEdit(val deviceId: String) : AppRoute
    data class WorkLogEdit(val logId: String) : AppRoute
    data object Attendance : AppRoute
    data object MonthSchedule : AppRoute
    data object NotificationSettings : AppRoute
    data object Backup : AppRoute
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CodaApp(
    restoreRecovery: RestoreRecoveryState?,
    onInterruptAcknowledged: () -> Unit,
) {
    var backStack by remember { mutableStateOf(listOf<AppRoute>()) }
    var route by remember { mutableStateOf<AppRoute>(AppRoute.Home) }
    fun navigate(to: AppRoute) {
        backStack = backStack + route
        route = to
    }
    fun goBack() {
        route = backStack.lastOrNull() ?: AppRoute.Home
        backStack = backStack.dropLast(1)
    }
    val title = when (route) {
        AppRoute.Home -> "今日工作台"
        AppRoute.FaultEntry -> "快速记录故障"
        is AppRoute.FaultDetail -> "故障详情"
        AppRoute.Search -> "查找工作"
        AppRoute.Handover -> "交接事项"
        AppRoute.Drafts -> "未完成草稿"
        AppRoute.ManualWork -> "记录普通工作"
        AppRoute.Settings -> "设置"
        is AppRoute.HandoverDetail -> "交接详情"
        is AppRoute.WorkLogDetail -> "工作记录详情"
        AppRoute.FaultList -> "全部故障"
        AppRoute.HandoverCreate -> "写交接"
        AppRoute.DeviceList -> "设备名称与别名"
        is AppRoute.DeviceEdit -> "设备详情"
        is AppRoute.WorkLogEdit -> "编辑工作记录"
        AppRoute.Attendance -> "当前出勤"
        AppRoute.MonthSchedule -> "本月排班"
        AppRoute.NotificationSettings -> "通知设置"
        AppRoute.Backup -> "备份与恢复"
    }
    BackHandler(enabled = route != AppRoute.Home) { goBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    if (route != AppRoute.Home) {
                        IconButton(onClick = ::goBack, modifier = Modifier.size(48.dp)) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "返回",
                                modifier = Modifier.size(30.dp),
                            )
                        }
                    }
                },
                actions = {
                    if (route is AppRoute.Home) {
                        IconButton(onClick = { navigate(AppRoute.Settings) }) {
                            Icon(Icons.Outlined.Settings, contentDescription = "设置")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when (val current = route) {
            AppRoute.Home -> HomeScreen(
                modifier = Modifier.padding(padding),
                onFault = { navigate(AppRoute.FaultEntry) },
                onManual = { navigate(AppRoute.ManualWork) },
                onSearch = { navigate(AppRoute.Search) },
                onFaultDetail = { navigate(AppRoute.FaultDetail(it)) },
                onHandover = { navigate(AppRoute.Handover) },
                onHandoverCreate = { navigate(AppRoute.HandoverCreate) },
                onHandoverDetail = { navigate(AppRoute.HandoverDetail(it)) },
                onDrafts = { navigate(AppRoute.Drafts) },
                onFaultList = { navigate(AppRoute.FaultList) },
                onAttendance = { navigate(AppRoute.Attendance) },
                onOpenSchedule = { navigate(AppRoute.MonthSchedule) },
            )
            AppRoute.FaultEntry -> FaultEntryScreen(
                modifier = Modifier.padding(padding),
                onSaved = { navigate(AppRoute.FaultDetail(it)) },
            )
            is AppRoute.FaultDetail -> FaultDetailScreen(
                modifier = Modifier.padding(padding),
                faultId = current.faultId,
            )
            AppRoute.Search -> SearchScreen(
                Modifier.padding(padding),
                onFaultDetail = { navigate(AppRoute.FaultDetail(it)) },
                onHandoverDetail = { navigate(AppRoute.HandoverDetail(it)) },
                onWorkLogDetail = { navigate(AppRoute.WorkLogDetail(it)) },
            )
            AppRoute.Handover -> HandoverScreen(
                Modifier.padding(padding),
                onHandoverDetail = { navigate(AppRoute.HandoverDetail(it)) },
            )
            AppRoute.Drafts -> DraftListScreen(Modifier.padding(padding), onFaultDetail = { navigate(AppRoute.FaultDetail(it)) })
            AppRoute.ManualWork -> ManualWorkScreen(
                Modifier.padding(padding),
                onSaved = { navigate(AppRoute.WorkLogDetail(it)) },
            )
            AppRoute.Settings -> SettingsScreen(
                Modifier.padding(padding),
                onAttendance = { navigate(AppRoute.Attendance) },
                onDevices = { navigate(AppRoute.DeviceList) },
                onNotifications = { navigate(AppRoute.NotificationSettings) },
                onBackup = { navigate(AppRoute.Backup) },
            )
            is AppRoute.HandoverDetail -> HandoverDetailScreen(
                Modifier.padding(padding),
                current.id,
                onFaultDetail = { navigate(AppRoute.FaultDetail(it)) },
            )
            is AppRoute.WorkLogDetail -> WorkLogDetailScreen(
                Modifier.padding(padding),
                current.logId,
                onFaultDetail = { navigate(AppRoute.FaultDetail(it)) },
                onEdit = { navigate(AppRoute.WorkLogEdit(it)) },
            )
            AppRoute.FaultList -> FaultListScreen(
                Modifier.padding(padding),
                onFaultDetail = { navigate(AppRoute.FaultDetail(it)) },
            )
            AppRoute.HandoverCreate -> HandoverCreateScreen(
                Modifier.padding(padding),
                onSaved = { navigate(AppRoute.HandoverDetail(it)) },
            )
            AppRoute.DeviceList -> DeviceListScreen(
                Modifier.padding(padding),
                onDeviceDetail = { navigate(AppRoute.DeviceEdit(it)) },
            )
            is AppRoute.DeviceEdit -> DeviceEditScreen(Modifier.padding(padding), current.deviceId)
            is AppRoute.WorkLogEdit -> WorkLogEditScreen(
                Modifier.padding(padding),
                current.logId,
                onSaved = { goBack() },
            )
            AppRoute.Attendance -> AttendanceScreen(
                Modifier.padding(padding),
                onOpenSchedule = { navigate(AppRoute.MonthSchedule) },
            )
            AppRoute.MonthSchedule -> MonthScheduleScreen(Modifier.padding(padding))
            AppRoute.NotificationSettings -> NotificationSettingsScreen(Modifier.padding(padding))
            AppRoute.Backup -> BackupScreen(Modifier.padding(padding))
        }
    }
    restoreRecovery?.let { recovery ->
        AlertDialog(
            onDismissRequest = onInterruptAcknowledged,
            title = { Text("恢复未完成") },
            text = {
                Text(
                    if (recovery.rolledBack) {
                        "检测到上次恢复在确认替换后中断。\n本机数据已回滚到恢复前的安全备份，原有记录未被清除。"
                    } else {
                        "检测到上次恢复中断，且未能自动回滚。\n请选择备份文件重新恢复本机数据。"
                    },
                )
            },
            confirmButton = {
                Button(onClick = {
                    onInterruptAcknowledged()
                    navigate(AppRoute.Backup)
                }) { Text("查看备份与恢复") }
            },
            dismissButton = {
                TextButton(onClick = {
                    onInterruptAcknowledged()
                    if (!recovery.rolledBack) navigate(AppRoute.Backup)
                }) { Text(if (recovery.rolledBack) "知道了" else "重新选择备份") }
            },
        )
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier,
    onFault: () -> Unit,
    onManual: () -> Unit,
    onSearch: () -> Unit,
    onFaultDetail: (String) -> Unit,
    onHandover: () -> Unit,
    onHandoverCreate: () -> Unit,
    onHandoverDetail: (String) -> Unit,
    onDrafts: () -> Unit,
    onFaultList: () -> Unit,
    onAttendance: () -> Unit,
    onOpenSchedule: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    LaunchedEffect(Unit) { viewModel.refresh() }
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AttendanceCard(snapshot?.attendance?.let { attendanceSummaryLabel(it) } ?: "普通班 08:00-18:00", onAttendance)
        if (snapshot != null && snapshot.monthShiftConfirmedAt == null) {
            ShiftConfirmBanner(onOpenSchedule)
        }
        QuickActions(onFault, onManual, onHandoverCreate, onSearch)
        ViewModeToggle(state, viewModel)
        if (state.loading && snapshot == null) {
            Text("正在加载今日工作…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.error?.let { ErrorNotice(it) }
        snapshot?.let { home ->
            SectionTitle("今日工作", home.workLogs.size.toString(), onSearch)
            if (home.workLogs.isEmpty()) EmptyNotice(
                if (state.view == HomeWorkView.CURRENT_ATTENDANCE) "当前出勤还没有工作记录" else "今天还没有工作记录",
            )
            home.workLogs.take(3).forEach { log ->
                WorkItem(
                    icon = if (log.kind == "FAULT_DERIVED") Icons.Outlined.Warning else Icons.Outlined.Build,
                    title = log.deviceNameSnapshot ?: log.content,
                    subtitle = log.content,
                    status = if (log.voidedAt == null) "已记录" else "已作废",
                )
            }
            SectionTitle("待跟进", (home.pendingUnfinished.size + home.pendingUpcoming.size + home.pendingOverdue.size).toString(), onHandover)
            val handovers = (home.pendingOverdue + home.pendingUnfinished + home.pendingUpcoming).take(3)
            if (handovers.isEmpty()) EmptyNotice("暂无待跟进事项")
            handovers.forEach { item ->
                Card(modifier = Modifier.fillMaxWidth(), onClick = { onHandoverDetail(item.id) }) {
                    WorkItem(Icons.Outlined.PendingActions, item.summary, item.nextAction, overdueLabel(item, state.nowMillis))
                }
            }
            if (home.drafts.isNotEmpty()) {
                SectionTitle("未完成草稿", home.drafts.size.toString(), onDrafts)
                home.drafts.take(3).forEach { draft ->
                    Card(modifier = Modifier.fillMaxWidth(), onClick = { onFaultDetail(draft.faultId) }) {
                    WorkItem(Icons.Outlined.EditNote, "故障草稿", "草稿（尚未开始）", "草稿")
                    }
                }
            }
            SectionTitle("最近故障", home.allFaults.size.toString(), onFaultList)
            home.recentFaults.take(3).forEach { fault ->
                val status = if (home.drafts.any { it.faultId == fault.id }) {
                    "草稿"
                } else {
                    faultStatusLabel(fault.lifecycleStatus, home.restoreByFault[fault.id])
                }
                Card(modifier = Modifier.fillMaxWidth(), onClick = { onFaultDetail(fault.id) }) {
                    WorkItem(Icons.Outlined.ReportProblem, fault.deviceNameSnapshot, fault.symptom, status)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AttendanceCard(label: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CalendarMonth, contentDescription = null)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("本次出勤", style = MaterialTheme.typography.labelLarge)
                Text(label, fontWeight = FontWeight.SemiBold)
                Text("点击修正出勤", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ShiftConfirmBanner(onConfirm: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CalendarMonth, contentDescription = null)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("本月排班尚未确认", fontWeight = FontWeight.SemiBold)
                Text("这不会阻止你记录工作", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onConfirm) { Text("去确认") }
        }
    }
}

@Composable
private fun QuickActions(onFault: () -> Unit, onManual: () -> Unit, onHandover: () -> Unit, onSearch: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickAction(Modifier.weight(1f), Icons.Outlined.ReportProblem, "记录故障", onFault)
            QuickAction(Modifier.weight(1f), Icons.Outlined.EditNote, "记录工作", onManual)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickAction(Modifier.weight(1f), Icons.Outlined.AssignmentTurnedIn, "写交接", onHandover)
            QuickAction(Modifier.weight(1f), Icons.Outlined.Search, "查找", onSearch)
        }
    }
}

@Composable
private fun QuickAction(modifier: Modifier, icon: ImageVector, label: String, onClick: () -> Unit) {
    Card(modifier = modifier.height(92.dp), onClick = onClick) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(label, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ViewModeToggle(state: HomeUiState, viewModel: HomeViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = state.view == HomeWorkView.NATURAL_DAY, onClick = { viewModel.setView(HomeWorkView.NATURAL_DAY) }, label = { Text("自然日") })
        FilterChip(selected = state.view == HomeWorkView.CURRENT_ATTENDANCE, onClick = { viewModel.setView(HomeWorkView.CURRENT_ATTENDANCE) }, label = { Text("本次出勤") })
        FilterChip(selected = state.includeVoided, onClick = { viewModel.setIncludeVoided(!state.includeVoided) }, label = { Text("包含已作废") })
    }
}

@Composable
private fun SectionTitle(title: String, count: String, onView: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(count, color = MaterialTheme.colorScheme.onSurfaceVariant)
        onView?.let { TextButton(onClick = it) { Text("查看") } }
    }
}

@Composable
private fun WorkItem(icon: ImageVector, title: String, subtitle: String, status: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(Modifier.size(40.dp), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(icon, contentDescription = null, modifier = Modifier.padding(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
                Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun FaultEntryScreen(modifier: Modifier, onSaved: (String) -> Unit, viewModel: FaultEntryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { viewModel.reset() }
    DisposableEffect(Unit) {
        onDispose {
            viewModel.autosave()
        }
    }
    val formatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE) }
    val zone = remember { ZoneId.systemDefault() }
    var reportText by remember(state.reportedAtMillis) { mutableStateOf(formatter.format(Instant.ofEpochMilli(state.reportedAtMillis).atZone(zone))) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("离开页面前会保存当前草稿", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(state.deviceName, viewModel::setDeviceName, Modifier.fillMaxWidth(), label = { Text("设备名称*") }, singleLine = true)
        if (state.recentDevices.isNotEmpty()) {
            Text("最近使用", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.recentDevices.take(3).forEach { device ->
                    FilterChip(selected = state.deviceName == device.name, onClick = { viewModel.setDeviceName(device.name) }, label = { Text(device.name) })
                }
            }
        }
        OutlinedTextField(
            value = reportText,
            onValueChange = { value ->
                reportText = value
                runCatching { viewModel.setReportedAt(java.time.LocalDateTime.parse(value, formatter).atZone(zone).toInstant().toEpochMilli()) }
            },
            modifier = Modifier.fillMaxWidth(), label = { Text("接报时间") }, singleLine = true,
        )
        OutlinedTextField(state.symptom, viewModel::setSymptom, Modifier.fillMaxWidth().height(132.dp), label = { Text("现象*") })
        state.error?.let { ErrorNotice(it) }
        Button(onClick = { scope.launch { viewModel.saveOnce()?.let(onSaved) } }, enabled = !state.saving && state.deviceName.isNotBlank() && state.symptom.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(if (state.saving) "保存中…" else "保存草稿") }
    }
}

@Composable
private fun FaultDetailScreen(modifier: Modifier, faultId: String, viewModel: FaultDetailViewModel = hiltViewModel()) {
    LaunchedEffect(faultId) { viewModel.load(faultId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    var check by remember(snapshot?.latestProcessing?.id) { mutableStateOf(snapshot?.latestProcessing?.checkResult.orEmpty()) }
    var judgement by remember(snapshot?.latestProcessing?.id) { mutableStateOf(snapshot?.latestProcessing?.initialJudgement.orEmpty()) }
    var cause by remember(snapshot?.latestProcessing?.id) { mutableStateOf(snapshot?.latestProcessing?.rootCause.orEmpty()) }
    var measures by remember(snapshot?.latestProcessing?.id) { mutableStateOf(snapshot?.latestProcessing?.measures.orEmpty()) }
    var voidLogId by remember { mutableStateOf<String?>(null) }
    var cancelConfirm by remember { mutableStateOf(false) }
    var voidProcessingConfirm by remember { mutableStateOf(false) }
    var voidFaultConfirm by remember { mutableStateOf(false) }
    var reportEdit by remember { mutableStateOf(false) }
    var reportText by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.loading && snapshot == null) Text("正在加载…")
        state.error?.let { ErrorNotice(it) }
        snapshot?.let { detail ->
            val processing = detail.latestProcessing
            val primaryStatus = processing?.let { processingStatusLabel(it.progressStatus) } ?: lifecycleLabel(detail.fault.lifecycleStatus)
            StatusBanner(primaryStatus, statusIcon(processing?.progressStatus ?: detail.fault.lifecycleStatus))
            Text(detail.fault.deviceNameSnapshot, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(detail.fault.symptom)
            Text(
                "接报：${formatDateTime(detail.fault.reportedAt)}（点击修改）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    reportText = formatDateTimeInput(detail.fault.reportedAt)
                    reportEdit = true
                },
            )
            if (processing != null && processing.startedAt != null && processing.progressStatus != "DRAFT") {
                Text("开始：${formatDateTime(processing.startedAt!!)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (processing != null && processing.progressStatus == "DRAFT") {
                Text("处理记录：草稿（尚未开始）", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::start, enabled = !state.saving, modifier = Modifier.weight(1f)) { Text("开始处理") }
                    var menu by remember { mutableStateOf(false) }
                    Box(contentAlignment = Alignment.TopEnd) {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "更多") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("取消草稿") }, onClick = { menu = false; cancelConfirm = true })
                        }
                    }
                }
            } else if (processing != null && processing.progressStatus in listOf("IN_PROGRESS", "PENDING_VERIFICATION")) {
                Text("处理记录：${processingStatusLabel(processing.progressStatus)}", color = MaterialTheme.colorScheme.primary)
                DetailField("检查结果", check) { check = it; viewModel.update(check, judgement, cause, measures, processing.verification) }
                DetailField("初步判断", judgement) { judgement = it; viewModel.update(check, judgement, cause, measures, processing.verification) }
                DetailField("最终原因", cause) { cause = it; viewModel.update(check, judgement, cause, measures, processing.verification) }
                DetailField("处理措施", measures) { measures = it; viewModel.update(check, judgement, cause, measures, processing.verification) }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (processing.progressStatus == "IN_PROGRESS") {
                        OutlinedButton(onClick = viewModel::markPending, enabled = !state.saving, modifier = Modifier.weight(1f)) { Text("标记为待验证") }
                    } else {
                        OutlinedButton(onClick = viewModel::resume, enabled = !state.saving, modifier = Modifier.weight(1f)) { Text("继续处理") }
                    }
                    Button(onClick = viewModel::showFinishDialog, enabled = !state.saving, modifier = Modifier.weight(1f)) { Text("结束本次处理") }
                }
                var menu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(contentAlignment = Alignment.TopEnd) {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "更多") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("取消本次处理") }, onClick = { menu = false; cancelConfirm = true })
                        }
                    }
                }
            } else {
                Text("处理记录：${processing?.let { processingStatusLabel(it.progressStatus) } ?: "暂无"}${if (processing?.voidedAt != null) "（已作废）" else ""}")
                processing?.let { p ->
                    listOf(
                        "检查结果" to p.checkResult,
                        "初步判断" to p.initialJudgement,
                        "最终原因" to p.rootCause,
                        "处理措施" to p.measures,
                        "验证结果" to p.verification,
                    ).forEach { (label, value) ->
                        val display = value?.takeIf { it.isNotBlank() } ?: "未填写"
                        Text(
                            "$label：$display",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (value.isNullOrBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                val activeDerivedLog = detail.derivedLogs.firstOrNull { it.voidedAt == null }
                if (processing != null &&
                    processing.progressStatus in listOf("ENDED", "CANCELED") &&
                    (detail.fault.lifecycleStatus == "OPEN" || activeDerivedLog != null || processing.voidedAt == null)
                ) {
                    var menu by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Box(contentAlignment = Alignment.TopEnd) {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "更多") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (detail.fault.lifecycleStatus == "OPEN") {
                                    DropdownMenuItem(text = { Text("继续处理故障") }, onClick = { menu = false; viewModel.continueProcessing(processing.id) })
                                }
                                activeDerivedLog?.let { log ->
                                    DropdownMenuItem(text = { Text("作废工作记录") }, onClick = { menu = false; voidLogId = log.id })
                                }
                                if (processing.voidedAt == null) {
                                    DropdownMenuItem(text = { Text("作废处理记录") }, onClick = { menu = false; voidProcessingConfirm = true })
                                }
                                if (detail.fault.lifecycleStatus != "VOIDED") {
                                    DropdownMenuItem(text = { Text("作废故障记录") }, onClick = { menu = false; voidFaultConfirm = true })
                                }
                            }
                        }
                    }
                }
                detail.derivedLogs.forEach { log ->
                    WorkItem(
                        Icons.Outlined.CheckCircle,
                        "派生工作记录",
                        log.content,
                        if (log.voidedAt == null) "已记录" else "已作废",
                    )
                }
            }
        }
    }
    if (cancelConfirm) {
        AlertDialog(
            onDismissRequest = { if (!state.saving) cancelConfirm = false },
            title = { Text("取消本次处理") },
            text = { Text("确认取消？已填写内容会保留，处理进度变为“已取消”。") },
            confirmButton = {
                Button(onClick = { viewModel.cancel(); cancelConfirm = false }, enabled = !state.saving) { Text("确认取消") }
            },
            dismissButton = { TextButton(onClick = { cancelConfirm = false }, enabled = !state.saving) { Text("返回") } },
        )
    }
    if (voidProcessingConfirm) {
        AlertDialog(
            onDismissRequest = { if (!state.saving) voidProcessingConfirm = false },
            title = { Text("作废处理记录") },
            text = { Text("作废后从默认列表隐藏，不会删除、不改写故障状态") },
            confirmButton = {
                Button(onClick = { viewModel.voidProcessing(); voidProcessingConfirm = false }, enabled = !state.saving) { Text("确认作废") }
            },
            dismissButton = { TextButton(onClick = { voidProcessingConfirm = false }, enabled = !state.saving) { Text("取消") } },
        )
    }
    if (voidFaultConfirm) {
        AlertDialog(
            onDismissRequest = { if (!state.saving) voidFaultConfirm = false },
            title = { Text("作废故障记录") },
            text = { Text("作废后故障显示为“已作废”，从默认列表隐藏；关联的处理和工作记录不删除") },
            confirmButton = {
                Button(onClick = { viewModel.voidFault(); voidFaultConfirm = false }, enabled = !state.saving) { Text("确认作废") }
            },
            dismissButton = { TextButton(onClick = { voidFaultConfirm = false }, enabled = !state.saving) { Text("取消") } },
        )
    }
    if (reportEdit) {
        AlertDialog(
            onDismissRequest = { if (!state.saving) reportEdit = false },
            title = { Text("修改接报时间") },
            text = {
                Column {
                    OutlinedTextField(reportText, { reportText = it }, label = { Text("yyyy-MM-dd HH:mm") }, singleLine = true)
                    Text("格式示例：2026-08-14 10:26", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val zone = ZoneId.systemDefault()
                        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE)
                        runCatching { java.time.LocalDateTime.parse(reportText, formatter).atZone(zone).toInstant().toEpochMilli() }
                            .onSuccess { viewModel.updateReportedAt(it); reportEdit = false }
                            .onFailure { /* 保留输入，用户可重试 */ }
                    },
                    enabled = !state.saving,
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { reportEdit = false }, enabled = !state.saving) { Text("取消") } },
        )
    }
    if (state.finishDialog) FinishDialog(state.saving, viewModel)
    voidLogId?.let { logId ->
        AlertDialog(
            onDismissRequest = { if (!state.saving) voidLogId = null },
            title = { Text("作废工作记录") },
            text = { Text("作废后从默认列表隐藏，不会删除") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.voidDerivedLog(logId)
                        voidLogId = null
                    },
                    enabled = !state.saving,
                ) { Text("确认作废") }
            },
            dismissButton = {
                TextButton(onClick = { voidLogId = null }, enabled = !state.saving) { Text("取消") }
            },
        )
    }
}

@Composable
private fun BoxWithMenu(open: Boolean, close: () -> Unit, show: () -> Unit, cancel: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(contentAlignment = Alignment.TopEnd) {
            IconButton(onClick = show) { Icon(Icons.Outlined.MoreVert, contentDescription = "更多") }
            DropdownMenu(expanded = open, onDismissRequest = close) { DropdownMenuItem(text = { Text("取消本次处理") }, onClick = { close(); cancel() }) }
        }
    }
}

@Composable
private fun DetailField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, minLines = 2)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FinishDialog(saving: Boolean, viewModel: FaultDetailViewModel) {
    var result by remember { mutableStateOf<RestoreResult?>(null) }
    var verification by remember { mutableStateOf("") }
    var nextAction by remember { mutableStateOf("") }
    var dueText by remember { mutableStateOf("") }
    var resultExpanded by remember { mutableStateOf(false) }
    var dueKindExpanded by remember { mutableStateOf(false) }
    var dueKind by remember { mutableStateOf<HandoverDueKind?>(null) }
    val needsFollowUp = result != null && result != RestoreResult.RESTORED
    AlertDialog(
        onDismissRequest = { if (!saving) viewModel.hideFinishDialog() },
        title = { Text("结束本次处理") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExposedDropdownMenuBox(expanded = resultExpanded, onExpandedChange = { resultExpanded = !resultExpanded }) {
                    OutlinedTextField(value = result?.let(::restoreLabel).orEmpty(), onValueChange = {}, readOnly = true, label = { Text("恢复结果*") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(resultExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    DropdownMenu(expanded = resultExpanded, onDismissRequest = { resultExpanded = false }) {
                        RestoreResult.values().forEach { option -> DropdownMenuItem(text = { Text(restoreLabel(option)) }, onClick = { result = option; dueKind = if (option == RestoreResult.RESTORED) HandoverDueKind.NONE else null; resultExpanded = false }) }
                    }
                }
                OutlinedTextField(verification, { verification = it }, Modifier.fillMaxWidth(), label = { Text("验证结果（选填）") })
                if (needsFollowUp) {
                    OutlinedTextField(nextAction, { nextAction = it }, Modifier.fillMaxWidth(), label = { Text("下一步动作*") })
                    ExposedDropdownMenuBox(expanded = dueKindExpanded, onExpandedChange = { dueKindExpanded = !dueKindExpanded }) {
                        OutlinedTextField(value = dueKind?.let(::dueKindLabel).orEmpty(), onValueChange = {}, readOnly = true, label = { Text("跟进期限类型*") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(dueKindExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                        DropdownMenu(expanded = dueKindExpanded, onDismissRequest = { dueKindExpanded = false }) {
                            listOf(HandoverDueKind.NONE, HandoverDueKind.END_OF_TODAY, HandoverDueKind.NEXT_SHIFT, HandoverDueKind.SPECIFIC).forEach { option -> DropdownMenuItem(text = { Text(dueKindLabel(option)) }, onClick = { dueKind = option; dueKindExpanded = false }) }
                        }
                    }
                    if (dueKind == HandoverDueKind.SPECIFIC) OutlinedTextField(dueText, { dueText = it }, Modifier.fillMaxWidth(), label = { Text("具体期限*（yyyy-MM-dd）") })
                }
            }
        },
        confirmButton = {
            val dueAt = if (dueKind == HandoverDueKind.SPECIFIC) runCatching { java.time.LocalDate.parse(dueText).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull() else null
            val canConfirm = !saving && result != null && (!needsFollowUp || (nextAction.isNotBlank() && dueKind != null && (dueKind != HandoverDueKind.SPECIFIC || dueAt != null)))
            Button(onClick = { result?.let { viewModel.finish(it, verification.ifBlank { null }, nextAction.ifBlank { null }, dueAt, dueKind ?: HandoverDueKind.NONE) } }, enabled = canConfirm) { Text(if (saving) "保存中…" else "确认结束") }
        },
        dismissButton = { TextButton(onClick = viewModel::hideFinishDialog, enabled = !saving) { Text("取消") } },
    )
}

@Composable
private fun HandoverScreen(modifier: Modifier, onHandoverDetail: (String) -> Unit) {
    val viewModel: HomeViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("待跟进事项按期限分组")
        state.snapshot?.let { snapshot ->
            val groups = listOf("未完成" to snapshot.pendingUnfinished, "即将到期" to snapshot.pendingUpcoming, "已逾期" to snapshot.pendingOverdue)
            groups.forEach { (label, items) ->
                if (items.isNotEmpty()) {
                    SectionTitle(label, items.size.toString())
                    items.forEach { item ->
                        val dueLabel = runCatching { dueKindLabel(HandoverDueKind.valueOf(item.dueKind)) }.getOrDefault(item.dueKind)
                        Card(modifier = Modifier.fillMaxWidth(), onClick = { onHandoverDetail(item.id) }) {
                            WorkItem(Icons.Outlined.PendingActions, item.summary, item.nextAction, dueLabel)
                        }
                    }
                }
            }
            if (snapshot.finishedHandovers.isNotEmpty()) {
                SectionTitle("已完成 / 已取消", snapshot.finishedHandovers.size.toString())
                snapshot.finishedHandovers.forEach { item ->
                    Card(modifier = Modifier.fillMaxWidth(), onClick = { onHandoverDetail(item.id) }) {
                        WorkItem(Icons.Outlined.PendingActions, item.summary, item.nextAction, handoverStatusLabel(item.status))
                    }
                }
            }
        }
    }
}

@Composable
private fun DraftListScreen(modifier: Modifier, onFaultDetail: (String) -> Unit) {
    val viewModel: HomeViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("选择草稿继续录入", style = MaterialTheme.typography.titleMedium)
        state.snapshot?.drafts?.forEach { draft ->
            Card(modifier = Modifier.fillMaxWidth(), onClick = { onFaultDetail(draft.faultId) }) {
                WorkItem(Icons.Outlined.EditNote, "故障草稿", "草稿（尚未开始）", "草稿")
            }
        }
        if (!state.loading && state.snapshot?.drafts.isNullOrEmpty()) EmptyNotice("暂无未完成草稿")
    }
}

@Composable
private fun ManualWorkScreen(modifier: Modifier, onSaved: (String) -> Unit, viewModel: ManualWorkViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var content by remember { mutableStateOf("") }
    var extrasExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.reset(); content = "" }
    val todayLabel = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.SIMPLIFIED_CHINESE)) }
    val currentAttendance by viewModel.currentAttendance.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "$todayLabel · ${currentAttendance?.let { attendanceSummaryLabel(it) } ?: "普通班 08:00-18:00"}（自动带入）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            content,
            { content = it; viewModel.setContent(it) },
            Modifier.fillMaxWidth().height(200.dp),
            label = { Text("工作内容*") },
            minLines = 6,
        )
        TextButton(onClick = { extrasExpanded = !extrasExpanded }) { Text(if (extrasExpanded) "收起补充信息" else "补充信息 ›") }
        if (extrasExpanded) {
            OutlinedTextField(state.workResult, viewModel::setWorkResult, Modifier.fillMaxWidth(), label = { Text("工作结果") })
            OutlinedTextField(state.deviceName, viewModel::setDeviceName, Modifier.fillMaxWidth(), label = { Text("设备名称") }, singleLine = true)
            if (state.recentDevices.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.recentDevices.take(3).forEach { device ->
                        FilterChip(selected = state.deviceName == device.name, onClick = { viewModel.setDeviceName(device.name) }, label = { Text(device.name) })
                    }
                }
            }
            OutlinedTextField(state.area, viewModel::setArea, Modifier.fillMaxWidth(), label = { Text("区域") })
            OutlinedTextField(state.arrangementSource, viewModel::setArrangementSource, Modifier.fillMaxWidth(), label = { Text("安排来源") })
        }
        state.error?.let { ErrorNotice(it) }
        Button(onClick = { scope.launch { viewModel.saveOnce()?.let(onSaved) } }, enabled = content.isNotBlank() && !state.saving, modifier = Modifier.fillMaxWidth()) { Text(if (state.saving) "保存中…" else "保存工作记录") }
    }
}

@Composable
private fun HandoverDetailScreen(modifier: Modifier, id: String, onFaultDetail: (String) -> Unit, viewModel: HandoverViewModel = hiltViewModel()) {
    LaunchedEffect(id) { viewModel.load(id) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmVoid by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.loading && state.item == null) { Text("正在加载…"); return@Column }
        state.error?.let { ErrorNotice(it) }
        state.item?.let { item ->
            val status = handoverStatusLabel(item.status)
            StatusBanner(status, handoverStatusIcon(item.status))
            if (state.faultId != null) {
                var menu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(contentAlignment = Alignment.TopEnd) {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "更多") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("继续处理故障") }, onClick = { menu = false; state.faultId?.let(onFaultDetail) })
                        }
                    }
                }
            }
            Text(item.summary, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("状态：$status")
            Text("下一步动作：${item.nextAction}")
            Text("跟进期限：${runCatching { dueKindLabel(HandoverDueKind.valueOf(item.dueKind)) }.getOrDefault(item.dueKind)}")
            if (item.sourceType != null) Text("来源：${if (item.sourceType == "FAULT_PROCESSING") "故障处理" else item.sourceType}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            when (item.status) {
                "PENDING_HANDOVER" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pendingAction = "markHandedOver" }, enabled = !state.saving, modifier = Modifier.weight(1f)) { Text("标记已交接") }
                    OutlinedButton(onClick = { pendingAction = "cancel" }, enabled = !state.saving, modifier = Modifier.weight(1f)) { Text("取消事项") }
                }
                "HANDED_OVER" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pendingAction = "markInProgress" }, enabled = !state.saving, modifier = Modifier.weight(1f)) { Text("标记处理中") }
                    OutlinedButton(onClick = { pendingAction = "cancel" }, enabled = !state.saving, modifier = Modifier.weight(1f)) { Text("取消事项") }
                }
                "IN_PROGRESS" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pendingAction = "complete" }, enabled = !state.saving, modifier = Modifier.weight(1f)) { Text("标记已完成") }
                    OutlinedButton(onClick = { pendingAction = "cancel" }, enabled = !state.saving, modifier = Modifier.weight(1f)) { Text("取消事项") }
                }
                else -> Text(if (item.status == "CANCELED") "该事项已取消，仅可查看" else "该事项已结束，仅可查看或作废", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (item.status != "CANCELED") {
                TextButton(onClick = { confirmVoid = true }, enabled = !state.saving) {
                    Text("作废事项", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    pendingAction?.let { action ->
        val label = when (action) {
            "markHandedOver" -> "标记已交接"
            "markInProgress" -> "标记处理中"
            "complete" -> "标记已完成"
            else -> "取消事项"
        }
        AlertDialog(
            onDismissRequest = { if (!state.saving) pendingAction = null },
            title = { Text(label) },
            text = { Text("确认执行：$label？") },
            confirmButton = {
                Button(
                    onClick = {
                        when (action) {
                            "markHandedOver" -> viewModel.markHandedOver()
                            "markInProgress" -> viewModel.markInProgress()
                            "complete" -> viewModel.complete()
                            else -> viewModel.cancel()
                        }
                        pendingAction = null
                    },
                    enabled = !state.saving,
                ) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { pendingAction = null }, enabled = !state.saving) { Text("取消") } },
        )
    }
    if (confirmVoid) {
        AlertDialog(
            onDismissRequest = { if (!state.saving) confirmVoid = false },
            title = { Text("作废事项") },
            text = { Text("作废后从默认列表隐藏，不删除记录、不改变关联故障状态") },
            confirmButton = {
                Button(
                    onClick = { viewModel.void(); confirmVoid = false },
                    enabled = !state.saving,
                ) { Text("确认作废") }
            },
            dismissButton = { TextButton(onClick = { confirmVoid = false }, enabled = !state.saving) { Text("取消") } },
        )
    }
}

@Composable
private fun WorkLogDetailScreen(
    modifier: Modifier,
    logId: String,
    onFaultDetail: (String) -> Unit,
    onEdit: (String) -> Unit,
    viewModel: WorkLogDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(logId) { viewModel.load(logId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmVoid by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.loading && state.log == null) { Text("正在加载…"); return@Column }
        state.error?.let { ErrorNotice(it) }
        state.log?.let { log ->
            Text(if (log.kind == "MANUAL") "普通工作记录" else "故障派生工作记录", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("工作日期：${log.workDate}")
            Text("内容：${log.content}")
            log.workResult?.takeIf { it.isNotBlank() }?.let { Text("工作结果：$it") }
            log.area?.takeIf { it.isNotBlank() }?.let { Text("区域：$it") }
            log.deviceNameSnapshot?.takeIf { it.isNotBlank() }?.let { Text("设备：$it") }
            log.arrangementSource?.takeIf { it.isNotBlank() }?.let { Text("安排来源：$it") }
            log.attendanceKindSnapshot?.let { Text("出勤：${attendanceKindLabel(it)}") }
            log.restoreResult?.let { Text("恢复结果：${restoreLabel(RestoreResult.valueOf(it))}") }
            Text("状态：${if (log.voidedAt == null) "已记录" else "已作废"}")
            if (log.kind == "MANUAL" && log.voidedAt == null) {
                Button(onClick = { onEdit(log.id) }, modifier = Modifier.fillMaxWidth()) { Text("编辑工作记录") }
                TextButton(onClick = { confirmVoid = true }, enabled = !state.saving) {
                    Text("作废工作记录", color = MaterialTheme.colorScheme.error)
                }
            }
            if (log.kind == "FAULT_DERIVED" && state.faultId != null) {
                Button(onClick = { onFaultDetail(state.faultId!!) }, modifier = Modifier.fillMaxWidth()) { Text("查看故障详情") }
            }
        }
    }
    if (confirmVoid) {
        AlertDialog(
            onDismissRequest = { if (!state.saving) confirmVoid = false },
            title = { Text("作废工作记录") },
            text = { Text("作废后从默认列表隐藏，不会删除") },
            confirmButton = {
                Button(onClick = { viewModel.voidManual(); confirmVoid = false }, enabled = !state.saving) { Text("确认作废") }
            },
            dismissButton = { TextButton(onClick = { confirmVoid = false }, enabled = !state.saving) { Text("取消") } },
        )
    }
}

@Composable
private fun FaultListScreen(modifier: Modifier, onFaultDetail: (String) -> Unit) {
    val viewModel: HomeViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("全部故障（按接报时间倒序）", style = MaterialTheme.typography.titleMedium)
        state.snapshot?.allFaults?.forEach { fault ->
            Card(modifier = Modifier.fillMaxWidth(), onClick = { onFaultDetail(fault.id) }) {
                WorkItem(Icons.Outlined.ReportProblem, fault.deviceNameSnapshot, fault.symptom, faultStatusLabel(fault.lifecycleStatus, state.snapshot?.restoreByFault?.get(fault.id)))
            }
        }
        if (!state.loading && state.snapshot?.allFaults.isNullOrEmpty()) EmptyNotice("还没有故障记录")
    }
}

@Composable
private fun SettingsScreen(
    modifier: Modifier,
    onAttendance: () -> Unit,
    onDevices: () -> Unit,
    onNotifications: () -> Unit,
    onBackup: () -> Unit,
) {
    var aboutDialog by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingGroup("工作")
        SettingRow("出勤与排班", "修正出勤与查看本月排班", onClick = onAttendance)
        SettingGroup("提醒")
        SettingRow("通知设置", "到期提醒与排班提示", onClick = onNotifications)
        SettingGroup("数据")
        SettingRow("备份与恢复", "导出与替换恢复本机数据", onClick = onBackup)
        SettingRow("设备名称与别名", "", onClick = onDevices)
        SettingGroup("其他")
        SettingRow("关于 CODA", "", onClick = { aboutDialog = true })
    }
    if (aboutDialog) {
        AlertDialog(
            onDismissRequest = { aboutDialog = false },
            title = { Text("关于 CODA") },
            text = { Text("CODA 现场工作助手 MVP 0.1.0\n个人离线记录工具，不替代停送电、验电、LOTO、作业票与现场安全制度") },
            confirmButton = { TextButton(onClick = { aboutDialog = false }) { Text("知道了") } },
        )
    }
}

@Composable
private fun SettingGroup(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                if (value.isNotBlank()) Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HandoverCreateScreen(modifier: Modifier, onSaved: (String) -> Unit, viewModel: HandoverCreateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { viewModel.reset() }
    LaunchedEffect(state.savedId) { state.savedId?.let(onSaved) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(state.summary, viewModel::setSummary, Modifier.fillMaxWidth(), label = { Text("事项摘要（选填）") })
        OutlinedTextField(state.nextAction, viewModel::setNextAction, Modifier.fillMaxWidth(), label = { Text("下一步动作*") })
        Text("跟进期限*", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(HandoverDueKind.NONE, HandoverDueKind.END_OF_TODAY, HandoverDueKind.NEXT_SHIFT, HandoverDueKind.SPECIFIC).forEach { kind ->
                FilterChip(selected = state.dueKind == kind, onClick = { viewModel.setDueKind(kind) }, label = { Text(dueKindLabel(kind)) })
            }
        }
        if (state.dueKind == HandoverDueKind.SPECIFIC) {
            OutlinedTextField(state.dueAtText, viewModel::setDueAtText, Modifier.fillMaxWidth(), label = { Text("指定时间*（yyyy-MM-dd）") })
        }
        Text("交给班组", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null to "不指定", "A" to "甲班", "B" to "乙班").forEach { (group, label) ->
                FilterChip(selected = state.handoverGroup == group, onClick = { viewModel.setHandoverGroup(group) }, label = { Text(label) })
            }
        }
        OutlinedTextField(state.hazardNote, viewModel::setHazardNote, Modifier.fillMaxWidth(), label = { Text("可能隐患（选填）") })
        state.error?.let { ErrorNotice(it) }
        Button(onClick = { scope.launch { viewModel.create() } }, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) { Text(if (state.saving) "保存中…" else "保存交接事项") }
    }
}

@Composable
private fun WorkLogEditScreen(modifier: Modifier, logId: String, onSaved: (String) -> Unit, viewModel: ManualWorkViewModel = hiltViewModel()) {
    LaunchedEffect(logId) { viewModel.loadForEdit(logId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(state.content, viewModel::setContent, Modifier.fillMaxWidth().height(180.dp), label = { Text("工作内容*") }, minLines = 5)
        OutlinedTextField(state.workResult, viewModel::setWorkResult, Modifier.fillMaxWidth(), label = { Text("工作结果") })
        OutlinedTextField(state.deviceName, viewModel::setDeviceName, Modifier.fillMaxWidth(), label = { Text("设备名称") }, singleLine = true)
        if (state.recentDevices.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.recentDevices.take(3).forEach { device ->
                    FilterChip(selected = state.deviceName == device.name, onClick = { viewModel.setDeviceName(device.name) }, label = { Text(device.name) })
                }
            }
        }
        OutlinedTextField(state.area, viewModel::setArea, Modifier.fillMaxWidth(), label = { Text("区域") })
        OutlinedTextField(state.arrangementSource, viewModel::setArrangementSource, Modifier.fillMaxWidth(), label = { Text("安排来源") })
        OutlinedButton(onClick = viewModel::reSnapAttendance, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) { Text("出勤标记：按当前出勤修正快照") }
        state.error?.let { ErrorNotice(it) }
        Button(onClick = { scope.launch { viewModel.saveOnce()?.let(onSaved) } }, enabled = state.content.isNotBlank() && !state.saving, modifier = Modifier.fillMaxWidth()) { Text(if (state.saving) "保存中…" else "保存修改") }
    }
}

@Composable
private fun DeviceListScreen(modifier: Modifier, onDeviceDetail: (String) -> Unit, viewModel: DeviceViewModel = hiltViewModel()) {
    val devices by viewModel.devices().collectAsStateWithLifecycle(initialValue = emptyList())
    var addDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        viewModel.state.value.error?.let { ErrorNotice(it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("设备名称与别名", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { addDialog = true }) { Icon(Icons.Outlined.Add, contentDescription = "添加设备") }
        }
        val active = devices.filter { it.isActive }
        val inactive = devices.filter { !it.isActive }
        if (active.isEmpty() && inactive.isEmpty()) EmptyNotice("还没有设备，点右上角 + 添加")
        active.forEach { device ->
            Card(modifier = Modifier.fillMaxWidth(), onClick = { onDeviceDetail(device.id) }) {
                WorkItem(Icons.Outlined.Build, device.name, "启用中", "设备")
            }
        }
        if (inactive.isNotEmpty()) {
            SectionTitle("已停用", inactive.size.toString())
            inactive.forEach { device ->
                Card(modifier = Modifier.fillMaxWidth(), onClick = { onDeviceDetail(device.id) }) {
                    WorkItem(Icons.Outlined.Build, device.name, "停用不影响历史记录", "已停用")
                }
            }
        }
    }
    if (addDialog) {
        AlertDialog(
            onDismissRequest = { addDialog = false },
            title = { Text("添加设备") },
            text = { OutlinedTextField(newName, { newName = it }, label = { Text("设备名称") }, singleLine = true) },
            confirmButton = {
                Button(onClick = { scope.launch { viewModel.create(newName) { addDialog = false; newName = "" } } }, enabled = newName.isNotBlank()) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { addDialog = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun DeviceEditScreen(modifier: Modifier, deviceId: String, viewModel: DeviceViewModel = hiltViewModel()) {
    val devices by viewModel.devices().collectAsStateWithLifecycle(initialValue = emptyList())
    val aliases by viewModel.aliases(deviceId).collectAsStateWithLifecycle(initialValue = emptyList())
    val device = devices.firstOrNull { it.id == deviceId }
    var name by remember { mutableStateOf("") }
    LaunchedEffect(device?.name) { device?.let { name = it.name } }
    var newAlias by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        viewModel.state.value.error?.let { ErrorNotice(it) }
        device?.let {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("标准名称") }, singleLine = true)
            Button(onClick = { scope.launch { viewModel.rename(it.id, name) } }, enabled = name.isNotBlank() && !viewModel.state.value.busy, modifier = Modifier.fillMaxWidth()) { Text("保存名称") }
            Text("别名（参与搜索）", style = MaterialTheme.typography.labelLarge)
            aliases.forEach { alias ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(alias.alias, modifier = Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { viewModel.removeAlias(it.id, alias.alias) } }) { Text("移除", color = MaterialTheme.colorScheme.error) }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newAlias, { newAlias = it }, Modifier.weight(1f), label = { Text("新别名") }, singleLine = true)
                Button(onClick = { scope.launch { viewModel.addAlias(it.id, newAlias); newAlias = "" } }, enabled = newAlias.isNotBlank()) { Text("添加") }
            }
            if (it.isActive) {
                OutlinedButton(onClick = { scope.launch { viewModel.setActive(it.id, false) } }, modifier = Modifier.fillMaxWidth()) { Text("停用设备") }
            } else {
                Button(onClick = { scope.launch { viewModel.setActive(it.id, true) } }, modifier = Modifier.fillMaxWidth()) { Text("重新启用") }
            }
        }
        if (device == null && !viewModel.state.value.busy) EmptyNotice("设备不存在")
    }
}

@Composable
private fun SettingsScreenOld(modifier: Modifier) {
}

@Composable private fun StatusBanner(label: String, icon: ImageVector) { Card(modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(icon, contentDescription = null); Text(label, fontWeight = FontWeight.SemiBold) } } }
@Composable private fun EmptyNotice(text: String) { Text(text, Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun ErrorNotice(text: String) { Text(text, color = MaterialTheme.colorScheme.error) }

private fun lifecycleLabel(status: String): String = when (status) { "OPEN" -> "处理中"; "CLOSED" -> "已结束"; "VOIDED" -> "已作废"; else -> "待处理" }
private fun processingStatusLabel(status: String): String = when (status) { "DRAFT" -> "草稿"; "IN_PROGRESS" -> "处理中"; "PENDING_VERIFICATION" -> "待验证"; "ENDED" -> "已结束"; "CANCELED" -> "已取消"; else -> "未知状态" }
private fun statusIcon(status: String): ImageVector = when (status) {
    "DRAFT" -> Icons.Outlined.EditNote
    "IN_PROGRESS" -> Icons.Outlined.Build
    "PENDING_VERIFICATION" -> Icons.Outlined.PendingActions
    "ENDED", "CLOSED" -> Icons.Outlined.CheckCircle
    "CANCELED", "VOIDED" -> Icons.Outlined.Cancel
    else -> Icons.Outlined.Warning
}
private fun restoreLabel(result: RestoreResult): String = when (result) { RestoreResult.RESTORED -> "已恢复"; RestoreResult.TEMPORARY -> "临时恢复"; RestoreResult.PARTIAL -> "部分恢复"; RestoreResult.NOT_RESTORED -> "未恢复"; RestoreResult.UNKNOWN -> "无法确认" }
private fun attendanceKindLabel(kind: String): String = when (kind) { "TOP_DAY" -> "顶班（日）"; "TOP_NIGHT" -> "顶班（夜）"; "CUSTOM" -> "自定义出勤"; else -> "普通班 08:00-18:00" }

/** 首页出勤卡片：显示当前出勤的真实类型、班组与实际起止时间（出勤修正后可见变化）。 */
private fun attendanceSummaryLabel(attendance: com.coda.workbench.data.local.AttendanceEntity): String {
    val zone = ZoneId.systemDefault()
    val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    val start = Instant.ofEpochMilli(attendance.startAt).atZone(zone).toLocalTime().format(timeFormat)
    val end = attendance.endAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime().format(timeFormat) } ?: "?"
    val kind = when (attendance.kind) {
        "TOP_DAY" -> "顶班（日）"
        "TOP_NIGHT" -> "顶班（夜）"
        "CUSTOM" -> "自定义出勤"
        else -> "普通班"
    }
    val group = attendance.productionGroup?.let { if (it == "A") "甲班" else "乙班" }
    val groupPart = group?.let { " · $it" } ?: ""
    return "$kind$groupPart $start-$end"
}
private fun dueKindLabel(kind: HandoverDueKind): String = when (kind) {
    HandoverDueKind.NONE -> "无期限"
    HandoverDueKind.END_OF_TODAY -> "今天下班前"
    HandoverDueKind.NEXT_SHIFT -> "下一班前"
    HandoverDueKind.SPECIFIC -> "指定时间"
}
private fun handoverStatusLabel(status: String): String = when (status) {
    "PENDING_HANDOVER" -> "待交接"
    "HANDED_OVER" -> "已交接"
    "IN_PROGRESS" -> "处理中"
    "COMPLETED" -> "已完成"
    "CANCELED" -> "已取消"
    else -> status
}
private fun handoverStatusIcon(status: String): ImageVector = when (status) {
    "PENDING_HANDOVER" -> Icons.Outlined.PendingActions
    "HANDED_OVER" -> Icons.Outlined.CheckCircle
    "IN_PROGRESS" -> Icons.Outlined.Build
    "COMPLETED" -> Icons.Outlined.CheckCircle
    "CANCELED" -> Icons.Outlined.Cancel
    else -> Icons.Outlined.PendingActions
}

private fun overdueLabel(item: com.coda.workbench.data.local.HandoverItemEntity, nowMillis: Long): String {
    val dueAt = item.dueAt ?: return "待跟进"
    if (dueAt >= nowMillis) return "待跟进"
    val days = ((nowMillis - dueAt) / 86_400_000L).coerceAtLeast(1L)
    return "已逾期${days}天"
}

private fun faultStatusLabel(status: String, restore: String?): String = when (status) {
    "CLOSED" -> "已结束｜已恢复"
    "VOIDED" -> "已作废"
    "OPEN" -> restore?.let {
        runCatching { "已结束｜${restoreLabel(RestoreResult.valueOf(it))}｜待跟进" }.getOrDefault("已结束｜待跟进")
    } ?: "待处理"
    else -> "待处理"
}

private fun formatDateTime(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE)
        .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

private fun formatDateTimeInput(epochMillis: Long): String = formatDateTime(epochMillis)
