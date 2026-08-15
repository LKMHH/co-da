package com.coda.workbench.core.usecase

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.coda.workbench.core.model.ProductionGroup
import com.coda.workbench.core.model.ShiftSlotPatch
import com.coda.workbench.core.model.ShiftType
import com.coda.workbench.data.local.CodaDatabase
import com.coda.workbench.data.local.CodaDatabaseCallback
import com.coda.workbench.data.local.ShiftSlotEntity
import com.coda.workbench.data.repository.FaultDraftRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.YearMonth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ShiftScheduleUseCaseTest {
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
    fun confirmMonthGeneratesSlotsAndMarksConfirmed() = runBlocking {
        open()
        val useCase = ShiftScheduleUseCase(database!!, clock, zoneId)

        useCase.confirmMonth(YearMonth.of(2026, 8), ProductionGroup.A)

        val plan = database!!.monthlyShiftPlanDao().findByMonth("2026-08")!!
        assertNotNull(plan.confirmedAt)
        assertEquals(1, plan.groupADayStart)
        val slots = database!!.shiftSlotDao().observeByMonthPrefix("2026-08-").first()
        assertTrue(slots.isNotEmpty())
        assertTrue(slots.all { it.source == "SUGGESTED" })
    }

    @Test
    fun confirmMonthIsIdempotentAndDoesNotDuplicateSlots() = runBlocking {
        open()
        val useCase = ShiftScheduleUseCase(database!!, clock, zoneId)
        useCase.confirmMonth(YearMonth.of(2026, 8), ProductionGroup.A)
        val firstCount = database!!.shiftSlotDao().observeByMonthPrefix("2026-08-").first().size

        useCase.confirmMonth(YearMonth.of(2026, 8), ProductionGroup.A)

        assertEquals(firstCount, database!!.shiftSlotDao().observeByMonthPrefix("2026-08-").first().size)
    }

    @Test
    fun updateFutureSlotMarksManualAndReConfirmDoesNotRegenerate() = runBlocking {
        open()
        val useCase = ShiftScheduleUseCase(database!!, clock, zoneId)
        useCase.confirmMonth(YearMonth.of(2026, 8), ProductionGroup.A)
        val future: ShiftSlotEntity = database!!.shiftSlotDao().observeByMonthPrefix("2026-08-").first()
            .first { it.startAt > clock.millis() }
        val patch = ShiftSlotPatch(
            group = ProductionGroup.valueOf(future.group),
            shiftType = ShiftType.valueOf(future.shiftType),
            startAt = Instant.ofEpochMilli(future.startAt),
            endAt = Instant.ofEpochMilli(future.endAt),
        )

        useCase.updateFutureSlot(future.id, patch)

        assertEquals("MANUAL", database!!.shiftSlotDao().findById(future.id)!!.source)

        useCase.confirmMonth(YearMonth.of(2026, 8), ProductionGroup.A)
        assertEquals("MANUAL", database!!.shiftSlotDao().findById(future.id)!!.source)
        assertEquals(future.businessDate, database!!.shiftSlotDao().findById(future.id)!!.businessDate)
    }

    @Test
    fun startedSlotCannotBeUpdated() = runBlocking {
        open()
        val useCase = ShiftScheduleUseCase(database!!, clock, zoneId)
        useCase.confirmMonth(YearMonth.of(2026, 8), ProductionGroup.A)
        val started: ShiftSlotEntity = database!!.shiftSlotDao().observeByMonthPrefix("2026-08-").first()
            .first { it.startAt <= clock.millis() }
        val patch = ShiftSlotPatch(
            group = ProductionGroup.valueOf(started.group),
            shiftType = ShiftType.valueOf(started.shiftType),
            startAt = Instant.ofEpochMilli(started.startAt),
            endAt = Instant.ofEpochMilli(started.endAt),
        )

        assertFailsWith<IllegalStateException> { useCase.updateFutureSlot(started.id, patch) }
        Unit
    }

    @Test
    fun futureSlotChangeDoesNotRewriteWorkLogSnapshots() = runBlocking {
        open()
        val useCase = ShiftScheduleUseCase(database!!, clock, zoneId)
        val logId = FaultDraftRepository(database!!, clock).createManual("普通工作", "2026-08-14")
        val before = database!!.workLogDao().findById(logId)!!
        useCase.confirmMonth(YearMonth.of(2026, 8), ProductionGroup.A)
        val future: ShiftSlotEntity = database!!.shiftSlotDao().observeByMonthPrefix("2026-08-").first()
            .first { it.startAt > clock.millis() }

        useCase.updateFutureSlot(
            future.id,
            ShiftSlotPatch(
                group = ProductionGroup.valueOf(future.group),
                shiftType = ShiftType.valueOf(future.shiftType),
                startAt = Instant.ofEpochMilli(future.startAt),
                endAt = Instant.ofEpochMilli(future.endAt),
            ),
        )

        assertEquals(before, database!!.workLogDao().findById(logId)!!)
    }

    @Test
    fun unconfirmedMonthReportsNoPlanAndNoSlots() = runBlocking {
        open()
        val queryUseCase = ShiftScheduleQueryUseCase(database!!, clock, zoneId)

        val state = queryUseCase.observeCurrentMonth().first()

        assertEquals("2026-08", state.month)
        assertEquals(null, state.confirmedAt)
        assertTrue(state.slots.isEmpty())
    }

    @Test
    fun monthEndSlotKeepsPreviousBusinessDateAfterGeneration() = runBlocking {
        open()
        val useCase = ShiftScheduleUseCase(database!!, clock, zoneId)
        useCase.confirmMonth(YearMonth.of(2026, 8), ProductionGroup.A)

        val slots = database!!.shiftSlotDao().observeByMonthPrefix("2026-08-").first()
        val midnight = slots.single {
            it.businessDate == "2026-08-31" &&
                Instant.ofEpochMilli(it.startAt) == Instant.parse("2026-08-31T16:00:00Z")
        }

        assertEquals("NIGHT", midnight.shiftType)
        assertTrue(midnight.isShiftChange)
    }

    private fun open() {
        database = Room.inMemoryDatabaseBuilder(context, CodaDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(CodaDatabaseCallback())
            .build()
    }
}
