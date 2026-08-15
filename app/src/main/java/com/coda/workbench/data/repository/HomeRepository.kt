package com.coda.workbench.data.repository

import com.coda.workbench.data.local.AttendanceEntity
import com.coda.workbench.data.local.CodaDatabase
import com.coda.workbench.data.local.FaultProcessingEntity
import com.coda.workbench.data.local.FaultRecordEntity
import com.coda.workbench.data.local.HandoverItemEntity
import com.coda.workbench.data.local.WorkLogEntity
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

enum class HomeWorkView { NATURAL_DAY, CURRENT_ATTENDANCE }

enum class WorkKindFilter { ALL, MANUAL, FAULT_DERIVED }

data class HomeSnapshot(
    val workLogs: List<WorkLogEntity>,
    val pendingUnfinished: List<HandoverItemEntity>,
    val pendingUpcoming: List<HandoverItemEntity>,
    val pendingOverdue: List<HandoverItemEntity>,
    val drafts: List<FaultProcessingEntity>,
    val recentFaults: List<FaultRecordEntity>,
    val allFaults: List<FaultRecordEntity>,
    val restoreByFault: Map<String, String>,
    val finishedHandovers: List<HandoverItemEntity>,
    val attendance: AttendanceEntity?,
    val workView: HomeWorkView,
    val includeVoided: Boolean,
    val kindFilter: WorkKindFilter,
    /** M5：本月排班确认时间；null 表示本月尚未确认（首页显示确认入口）。 */
    val monthShiftConfirmedAt: Long? = null,
)

class HomeRepository(
    private val database: CodaDatabase,
    private val clock: Clock,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun load(
        view: HomeWorkView = HomeWorkView.NATURAL_DAY,
        kindFilter: WorkKindFilter = WorkKindFilter.ALL,
        includeVoided: Boolean = false,
    ): HomeSnapshot {
        val now = clock.millis()
        val attendance = AttendanceRepository(database, clock = clock).ensureCurrentEntity(zoneId)
        val today = LocalDate.now(clock.withZone(zoneId))
        val workDate = today.toString()
        val monthShiftConfirmedAt =
            database.monthlyShiftPlanDao().findByMonth(java.time.YearMonth.from(today).toString())
                ?.confirmedAt
        val logs = when {
            view == HomeWorkView.CURRENT_ATTENDANCE && attendance != null -> if (includeVoided) {
                database.workLogDao().forAttendanceIncludingVoided(attendance.id)
            } else {
                database.workLogDao().forAttendance(attendance.id)
            }
            else -> if (includeVoided) {
                database.workLogDao().forWorkDateIncludingVoided(workDate)
            } else {
                database.workLogDao().forWorkDate(workDate)
            }
        }.filter { kindFilter == WorkKindFilter.ALL || it.kind == kindFilter.name }
        val pending = database.handoverItemDao().pending()
        val allFaults = database.faultRecordDao().all()
        val restoreByFault = allFaults.mapNotNull { fault ->
            fault.lastProcessingId?.let { pid ->
                database.faultProcessingDao().findById(pid)?.restoreResult?.let { fault.id to it }
            }
        }.toMap()
        return HomeSnapshot(
            workLogs = logs,
            pendingUnfinished = pending.filter { it.dueAt == null },
            pendingUpcoming = pending.filter { it.dueAt != null && it.dueAt >= now },
            pendingOverdue = pending.filter { it.dueAt != null && it.dueAt < now },
            drafts = database.faultProcessingDao().drafts(),
            recentFaults = database.faultRecordDao().recent(3),
            allFaults = allFaults,
            restoreByFault = restoreByFault,
            finishedHandovers = database.handoverItemDao().finished(),
            attendance = attendance,
            workView = view,
            includeVoided = includeVoided,
            kindFilter = kindFilter,
            monthShiftConfirmedAt = monthShiftConfirmedAt,
        )
    }

    suspend fun workLog(id: String): WorkLogEntity? = database.workLogDao().findById(id)

    suspend fun faultIdForDerivedLog(log: WorkLogEntity): String? =
        log.sourceId?.let { database.faultProcessingDao().findById(it)?.faultId }
}
