package com.coda.workbench.core.usecase

import com.coda.workbench.core.rules.TextRules
import com.coda.workbench.data.local.CodaDatabase
import com.coda.workbench.data.repository.AttendanceRepository
import com.coda.workbench.data.repository.FaultDraftRepository
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

class ManualWorkUseCase(
    private val repository: FaultDraftRepository,
    private val database: CodaDatabase,
    private val clock: Clock,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun save(content: String): String {
        require(content.isNotBlank()) { "工作内容不能为空" }
        return repository.createManual(
            content = content.trim(),
            workDate = LocalDate.now(clock.withZone(zoneId)).toString(),
        )
    }

    suspend fun update(
        id: String,
        content: String,
        workResult: String?,
        area: String?,
        arrangementSource: String?,
        deviceName: String?,
        workDate: String?,
    ) {
        require(content.isNotBlank()) { "工作内容不能为空" }
        val log = database.workLogDao().findById(id) ?: error("工作记录不存在")
        check(log.kind == "MANUAL") { "派生记录不能直接编辑，请回到对应处理记录修正" }
        val trimmedDevice = deviceName?.trim()?.takeIf { it.isNotEmpty() }
        val matched = trimmedDevice?.let { database.deviceDao().findByNormalizedName(TextRules.normalize(it)) }
        val parsedDate = workDate?.let {
            if (it.isBlank()) error("工作日期不能为空")
            runCatching { LocalDate.parse(it.trim()) }.getOrNull() ?: error("工作日期格式应为 yyyy-MM-dd")
        }?.toString()
        database.workLogDao().updateManualFields(
            id = id,
            content = content.trim(),
            workResult = workResult?.trim()?.takeIf { it.isNotEmpty() },
            area = area?.trim()?.takeIf { it.isNotEmpty() },
            arrangementSource = arrangementSource?.trim()?.takeIf { it.isNotEmpty() },
            deviceId = matched?.id,
            deviceNameSnapshot = matched?.name ?: trimmedDevice,
            workDate = parsedDate,
            updatedAt = clock.millis(),
        )
    }

    suspend fun reSnapAttendance(id: String) {
        val log = database.workLogDao().findById(id) ?: error("工作记录不存在")
        check(log.kind == "MANUAL") { "派生记录不能直接编辑，请回到对应处理记录修正" }
        val attendance = AttendanceRepository(database, clock = clock).ensureCurrentEntity(zoneId)
        val shiftBusinessDate = attendance.shiftBusinessDate ?: attendance.businessDate
        database.workLogDao().reSnapAttendance(
            id = id,
            attendanceId = attendance.id,
            kind = attendance.kind,
            startAt = attendance.startAt,
            endAt = attendance.endAt,
            productionGroup = attendance.productionGroup,
            shiftId = attendance.shiftId,
            shiftBusinessDate = shiftBusinessDate,
            shiftType = attendance.shiftType,
            shiftStartAt = attendance.shiftStartAt,
            shiftEndAt = attendance.shiftEndAt,
            isShiftChange = attendance.isShiftChange,
            workDate = shiftBusinessDate,
            updatedAt = clock.millis(),
        )
    }

    suspend fun void(id: String) {
        val log = database.workLogDao().findById(id) ?: error("工作记录不存在")
        check(log.kind == "MANUAL") { "派生记录请从对应处理记录作废" }
        val now = clock.millis()
        check(database.workLogDao().markVoided(id, now, now) == 1) { "记录不存在或已作废" }
    }
}
