package com.coda.workbench.core.usecase

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.coda.workbench.data.local.AttendanceEntity
import com.coda.workbench.data.local.CodaDatabase
import com.coda.workbench.data.local.CodaDatabaseCallback
import com.coda.workbench.data.local.MonthlyShiftPlanEntity
import com.coda.workbench.data.local.ShiftSlotEntity
import com.coda.workbench.data.repository.FaultDraftRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HandoverDueConversionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zoneId = ZoneId.of("Asia/Shanghai")
    // 2026-08-14 12:00 +08:00
    private val clock = Clock.fixed(Instant.parse("2026-08-14T04:00:00Z"), zoneId)
    private var database: CodaDatabase? = null

    @After
    fun tearDown() {
        database?.close()
    }

    @Test
    fun createEndOfTodayConvertsToCurrentAttendanceEndAtSnapshot() = runBlocking {
        open()
        val endAt = LocalDateTime.of(2026, 8, 14, 20, 0).atZone(zoneId).toInstant().toEpochMilli()
        insertCurrentAttendance(endAt)
        val useCase = HandoverUseCase(database!!, clock, zoneId)

        val id = useCase.create(
            CreateHandoverInput("事项", "明天查", HandoverDueKind.END_OF_TODAY, null, null, null),
        )
        val item = database!!.handoverItemDao().findById(id)!!

        assertEquals("END_OF_TODAY", item.dueKind)
        assertEquals(endAt, item.dueAt)
    }

    @Test
    fun createEndOfTodayWithoutAttendanceFallsBackToLocalEighteen() = runBlocking {
        open()
        val useCase = HandoverUseCase(database!!, clock, zoneId)
        val expected = LocalDateTime.of(2026, 8, 14, 18, 0).atZone(zoneId).toInstant().toEpochMilli()

        val id = useCase.create(
            CreateHandoverInput("事项", "明天查", HandoverDueKind.END_OF_TODAY, null, null, null),
        )

        assertEquals(expected, database!!.handoverItemDao().findById(id)!!.dueAt)
    }

    @Test
    fun createNextShiftUsesNextConfirmedSlotStart() = runBlocking {
        open()
        val nextStart = LocalDateTime.of(2026, 8, 14, 20, 0).atZone(zoneId).toInstant().toEpochMilli()
        insertPlanWithSlot(nextStart)
        val useCase = HandoverUseCase(database!!, clock, zoneId)

        val id = useCase.create(
            CreateHandoverInput("事项", "下一班处理", HandoverDueKind.NEXT_SHIFT, null, null, null),
        )
        val item = database!!.handoverItemDao().findById(id)!!

        assertEquals("NEXT_SHIFT", item.dueKind)
        assertEquals(nextStart, item.dueAt)
    }

    @Test
    fun createNextShiftWithoutConfirmedPlanKeepsNullDueAt() = runBlocking {
        open()
        val useCase = HandoverUseCase(database!!, clock, zoneId)

        val id = useCase.create(
            CreateHandoverInput("事项", "下一班处理", HandoverDueKind.NEXT_SHIFT, null, null, null),
        )
        val item = database!!.handoverItemDao().findById(id)!!

        assertEquals("NEXT_SHIFT", item.dueKind)
        assertNull(item.dueAt)
    }

    @Test
    fun createNextShiftIgnoresSlotsOfOtherMonths() = runBlocking {
        open()
        // 九月的班次不属于八月“下一班”计算范围（技术稿 §6.2 当前月）
        val septemberStart = LocalDateTime.of(2026, 9, 1, 8, 0).atZone(zoneId).toInstant().toEpochMilli()
        insertPlanWithSlot(septemberStart, businessDate = "2026-09-01", businessMonth = "2026-09")
        val useCase = HandoverUseCase(database!!, clock, zoneId)

        val id = useCase.create(
            CreateHandoverInput("事项", "下一班处理", HandoverDueKind.NEXT_SHIFT, null, null, null),
        )

        assertNull(database!!.handoverItemDao().findById(id)!!.dueAt)
    }

    @Test
    fun updateRecomputesEndOfTodayFromCurrentAttendance() = runBlocking {
        open()
        val useCase = HandoverUseCase(database!!, clock, zoneId)
        val id = useCase.create(CreateHandoverInput("事项", "查", HandoverDueKind.NONE, null, null, null))
        val newEnd = LocalDateTime.of(2026, 8, 14, 22, 0).atZone(zoneId).toInstant().toEpochMilli()
        insertCurrentAttendance(newEnd)

        useCase.update(id, UpdateHandoverInput("事项", "查", HandoverDueKind.END_OF_TODAY, null, null, null))

        assertEquals(newEnd, database!!.handoverItemDao().findById(id)!!.dueAt)
    }

    @Test
    fun finishProcessingAutoHandoverConvertsEndOfToday() = runBlocking {
        open()
        val endAt = LocalDateTime.of(2026, 8, 14, 20, 0).atZone(zoneId).toInstant().toEpochMilli()
        insertCurrentAttendance(endAt)
        val processingId = FaultDraftRepository(database!!, clock).createMinimalDraft("一号配电箱", "信号弱")
        val faultUseCase = FaultUseCase(database!!, clock, zoneId)
        faultUseCase.startProcessing(processingId)

        val result = faultUseCase.finishProcessing(
            processingId = processingId,
            restoreResult = RestoreResult.TEMPORARY,
            verification = null,
            nextAction = "查线",
            dueAt = null,
            dueKind = HandoverDueKind.END_OF_TODAY,
        )

        val handover = database!!.handoverItemDao().findById(result.handoverId!!)!!
        assertEquals("END_OF_TODAY", handover.dueKind)
        assertEquals(endAt, handover.dueAt)
    }

    private suspend fun insertCurrentAttendance(endAt: Long) {
        database!!.attendanceDao().insert(
            AttendanceEntity(
                id = UUID.randomUUID().toString(),
                businessDate = "2026-08-14",
                kind = "TOP_DAY",
                startAt = LocalDateTime.of(2026, 8, 14, 8, 0).atZone(zoneId).toInstant().toEpochMilli(),
                endAt = endAt,
                productionGroup = "A",
                shiftId = null,
                shiftBusinessDate = "2026-08-14",
                shiftType = "DAY",
                shiftStartAt = null,
                shiftEndAt = null,
                isShiftChange = false,
                isCurrent = true,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
    }

    private suspend fun insertPlanWithSlot(
        nextStart: Long,
        businessDate: String = "2026-08-14",
        businessMonth: String = "2026-08",
    ) {
        val now = clock.millis()
        val planId = UUID.randomUUID().toString()
        database!!.monthlyShiftPlanDao().insert(
            MonthlyShiftPlanEntity(
                id = planId,
                businessMonth = businessMonth,
                groupADayStart = 1,
                groupBDayStart = 16,
                confirmedAt = now,
                createdAt = now,
                updatedAt = now,
            ),
        )
        database!!.shiftSlotDao().insert(
            ShiftSlotEntity(
                id = UUID.randomUUID().toString(),
                planId = planId,
                businessDate = businessDate,
                group = "B",
                shiftType = "NIGHT",
                startAt = nextStart,
                endAt = nextStart + 12 * 3_600_000L,
                isShiftChange = false,
                source = "SUGGESTED",
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun open() {
        database = Room.inMemoryDatabaseBuilder(context, CodaDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(CodaDatabaseCallback())
            .build()
    }
}
