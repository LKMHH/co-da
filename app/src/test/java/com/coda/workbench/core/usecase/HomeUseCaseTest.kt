package com.coda.workbench.core.usecase

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.coda.workbench.data.local.AttendanceEntity
import com.coda.workbench.data.local.CodaDatabase
import com.coda.workbench.data.local.DeviceEntity
import com.coda.workbench.data.local.FaultProcessingEntity
import com.coda.workbench.data.local.FaultRecordEntity
import com.coda.workbench.data.local.HandoverItemEntity
import com.coda.workbench.data.local.WorkLogEntity
import com.coda.workbench.data.repository.HomeRepository
import com.coda.workbench.data.repository.HomeWorkView
import com.coda.workbench.data.repository.WorkKindFilter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HomeUseCaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var database: CodaDatabase? = null
    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-08-14T04:00:00Z"), zone)

    @After
    fun tearDown() { database?.close() }

    @Test
    fun naturalDayAndCurrentAttendanceViewsUseDifferentKeys() = runBlocking {
        openDatabase()
        val attendance = currentAttendance("attendance-1", "2026-08-13")
        database!!.attendanceDao().insert(attendance)
        database!!.workLogDao().insert(workLog("manual-1", "2026-08-14", attendance.id, "普通工作"))
        database!!.workLogDao().insert(workLog("manual-other", "2026-08-14", "other", "同日另一出勤"))
        database!!.workLogDao().insert(workLog("manual-2", "2026-08-13", "other", "前一天"))
        val repository = HomeRepository(database!!, clock, zone)
        val useCase = HomeUseCase(repository)

        val day = useCase.load(HomeWorkView.NATURAL_DAY)
        val shift = useCase.load(HomeWorkView.CURRENT_ATTENDANCE)

        assertEquals(setOf("manual-1", "manual-other"), day.workLogs.map { it.id }.toSet())
        assertEquals(listOf("manual-1"), shift.workLogs.map { it.id })
    }

    @Test
    fun voidedWorkIsHiddenByDefaultAndVisibleWhenRequested() = runBlocking {
        openDatabase()
        database!!.workLogDao().insert(workLog("active", "2026-08-14", null, "有效"))
        database!!.workLogDao().insert(workLog("voided", "2026-08-14", null, "已作废", voidedAt = 5L))
        val repository = HomeRepository(database!!, clock, zone)
        assertFalse(repository.load().workLogs.any { it.id == "voided" })
        assertTrue(repository.load(includeVoided = true).workLogs.any { it.id == "voided" })
    }

    @Test
    fun draftsOnlyAppearWhenDraftRowsExistAndPendingItemsAreGrouped() = runBlocking {
        openDatabase()
        val now = clock.millis()
        database!!.handoverItemDao().insert(handover("open", null))
        database!!.handoverItemDao().insert(handover("soon", now + 60_000))
        database!!.handoverItemDao().insert(handover("late", now - 60_000))
        database!!.handoverItemDao().insert(handover("handed", null, "HANDED_OVER"))
        database!!.handoverItemDao().insert(handover("processing", null, "IN_PROGRESS"))
        val repository = HomeRepository(database!!, clock, zone)
        val empty = repository.load()
        assertTrue(empty.drafts.isEmpty())
        assertEquals(setOf("open", "handed", "processing"), empty.pendingUnfinished.map { it.id }.toSet())
        assertEquals(listOf("soon"), empty.pendingUpcoming.map { it.id })
        assertEquals(listOf("late"), empty.pendingOverdue.map { it.id })

        seedDraft()
        assertEquals(1, repository.load().drafts.size)
    }

    @Test
    fun faultDerivedLogKeepsAttendanceAndShiftBusinessDateSnapshot() = runBlocking {
        openDatabase()
        val start = Instant.parse("2026-08-14T12:00:00Z")
        database!!.attendanceDao().insert(
            AttendanceEntity(
                id = "night",
                businessDate = "2026-08-14",
                kind = "TOP_NIGHT",
                startAt = start.toEpochMilli(),
                endAt = Instant.parse("2026-08-15T00:00:00Z").toEpochMilli(),
                productionGroup = "A",
                shiftId = "shift-night",
                shiftBusinessDate = "2026-08-14",
                shiftType = "NIGHT",
                shiftStartAt = start.toEpochMilli(),
                shiftEndAt = Instant.parse("2026-08-15T00:00:00Z").toEpochMilli(),
                isShiftChange = false,
                isCurrent = true,
                createdAt = start.toEpochMilli(),
                updatedAt = start.toEpochMilli(),
            ),
        )
        val processingId = com.coda.workbench.data.repository.FaultDraftRepository(database!!, clock, zone)
            .createMinimalDraft("配电柜", "信号弱", 1_000L)
        val useCase = FaultUseCase(database!!, clock, zone)
        useCase.startProcessing(processingId)
        useCase.finishProcessing(processingId, RestoreResult.PARTIAL)
        val log = database!!.workLogDao().findBySource(FaultUseCase.SOURCE_TYPE, processingId)!!
        assertEquals("night", log.attendanceId)
        assertEquals("2026-08-14", log.workDate)
        assertEquals("2026-08-14", log.shiftBusinessDateSnapshot)
        assertEquals("TOP_NIGHT", log.attendanceKindSnapshot)
    }

    @Test
    fun manualWorkUsesCurrentAttendanceSnapshot() = runBlocking {
        openDatabase()
        database!!.attendanceDao().insert(currentAttendance("attendance-manual", "2026-08-14"))
        val repository = com.coda.workbench.data.repository.FaultDraftRepository(database!!, clock, zone)
        repository.createManual("巡检配电柜", "2026-08-14")
        val log = database!!.workLogDao().forWorkDate("2026-08-14").single()
        assertEquals("attendance-manual", log.attendanceId)
        assertEquals("2026-08-14", log.shiftBusinessDateSnapshot)
    }

    private fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(context, CodaDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    private fun currentAttendance(id: String, date: String) = AttendanceEntity(
        id = id, businessDate = date, kind = "NORMAL", startAt = 1L, endAt = 2L,
        productionGroup = null, shiftId = null, shiftBusinessDate = date, shiftType = "DAY",
        shiftStartAt = 1L, shiftEndAt = 2L, isShiftChange = false, isCurrent = true,
        createdAt = 1L, updatedAt = 1L,
    )

    private fun workLog(id: String, date: String, attendanceId: String?, content: String, voidedAt: Long? = null) = WorkLogEntity(
        id = id, kind = "MANUAL", content = content, workDate = date, attendanceId = attendanceId,
        attendanceKindSnapshot = null, attendanceStartAt = null, attendanceEndAt = null,
        productionGroupSnapshot = null, shiftIdSnapshot = null, shiftBusinessDateSnapshot = null,
        shiftTypeSnapshot = null, shiftStartAtSnapshot = null, shiftEndAtSnapshot = null,
        isShiftChangeSnapshot = null, workResult = null, deviceId = null, area = null,
        deviceNameSnapshot = null, processingStartedAt = null, processingEndedAt = null,
        processedAt = null, restoreResult = null, arrangementSource = "MANUAL", sourceType = null,
        sourceId = null, status = "ACTIVE", createdAt = 1L, updatedAt = 1L, voidedAt = voidedAt,
    )

    private fun handover(id: String, dueAt: Long?, status: String = "PENDING_HANDOVER") = HandoverItemEntity(
        id = id, summary = id, status = status, nextAction = "跟进", dueKind = if (dueAt == null) "NONE" else "SPECIFIC",
        dueAt = dueAt, originType = "MANUAL", sourceType = null, sourceId = null, handoverGroup = null,
        potentialHazardNote = null, lastOverdueNoticeDate = null, createdAt = 1L, updatedAt = 1L,
        completedAt = null, voidedAt = null,
    )

    private suspend fun seedDraft() {
        val device = DeviceEntity("device-1", "配电柜", "配电柜", true, 1L, 1L)
        val fault = FaultRecordEntity("fault-1", device.id, device.name, 1L, "信号弱", "OPEN", "processing-1", 1L, 1L, null)
        val processing = FaultProcessingEntity("processing-1", fault.id, "DRAFT", null, null, null, null, null, null, null, null, 1L, 1L, null, null)
        database!!.deviceDao().insert(device)
        database!!.faultRecordDao().insert(fault)
        database!!.faultProcessingDao().insert(processing)
    }
}
