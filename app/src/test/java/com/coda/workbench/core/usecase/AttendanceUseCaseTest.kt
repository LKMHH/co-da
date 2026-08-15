package com.coda.workbench.core.usecase

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.coda.workbench.core.model.AttendanceInput
import com.coda.workbench.core.model.AttendanceKind
import com.coda.workbench.core.model.AttendancePatch
import com.coda.workbench.data.local.AttendanceEntity
import com.coda.workbench.data.local.CodaDatabase
import com.coda.workbench.data.local.CodaDatabaseCallback
import com.coda.workbench.data.repository.FaultDraftRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AttendanceUseCaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-08-14T04:00:00Z"), zoneId)
    private var database: CodaDatabase? = null

    @After
    fun tearDown() {
        database?.close()
    }

    @Test
    fun firstSavedAttendanceBecomesCurrentAutomatically() = runBlocking {
        open()
        val useCase = AttendanceUseCase(database!!, clock, zoneId)
        val saved = useCase.save(normalInput(LocalDate.of(2026, 8, 14)))

        assertNotNull(database!!.attendanceDao().findCurrent())
        assertEquals(saved.id, database!!.attendanceDao().findCurrent()!!.id)
        assertEquals("NORMAL", saved.kind)
        assertEquals(1, database!!.attendanceDao().countCurrent())
    }

    @Test
    fun savingAnotherAttendanceDoesNotImplicitlySwitchCurrent() = runBlocking {
        open()
        val useCase = AttendanceUseCase(database!!, clock, zoneId)
        val first = useCase.save(normalInput(LocalDate.of(2026, 8, 14)))
        val second = useCase.save(normalInput(LocalDate.of(2026, 8, 15)))

        assertEquals(first.id, database!!.attendanceDao().findCurrent()!!.id)
        assertEquals(listOf(second.id), database!!.attendanceDao().observeForDate("2026-08-15").first().map { it.id })
        assertEquals(second.id, database!!.attendanceDao().findById(second.id)!!.id)
    }

    @Test
    fun setCurrentSwapsExplicitlyAndKeepsSingleCurrent() = runBlocking {
        open()
        val useCase = AttendanceUseCase(database!!, clock, zoneId)
        val first = useCase.save(normalInput(LocalDate.of(2026, 8, 14)))
        val second = useCase.save(normalInput(LocalDate.of(2026, 8, 15)))

        useCase.setCurrent(second.id)

        assertEquals(second.id, database!!.attendanceDao().findCurrent()!!.id)
        assertEquals(1, database!!.attendanceDao().countCurrent())
        assertEquals(false, database!!.attendanceDao().findById(first.id)!!.isCurrent)
    }

    @Test
    fun ensureDefaultForDateCreatesNormalForGivenDateWhenNoneExists() = runBlocking {
        open()
        val useCase = AttendanceUseCase(database!!, clock, zoneId)

        val created = useCase.ensureDefaultForDate(LocalDate.of(2026, 8, 20))

        assertEquals("2026-08-20", created.businessDate)
        assertEquals("NORMAL", created.kind)
        assertEquals(created.id, database!!.attendanceDao().findCurrent()!!.id)
    }

    @Test
    fun ensureDefaultForDateReusesExistingCurrentRegardlessOfDate() = runBlocking {
        open()
        val useCase = AttendanceUseCase(database!!, clock, zoneId)
        // 前一日开始的顶夜班（跨午夜未结束）
        insertAttendance(
            AttendanceEntity(
                id = "night-1",
                businessDate = "2026-08-13",
                kind = "TOP_NIGHT",
                startAt = LocalDateTime.of(2026, 8, 13, 20, 0).atZone(zoneId).toInstant().toEpochMilli(),
                endAt = LocalDateTime.of(2026, 8, 14, 8, 0).atZone(zoneId).toInstant().toEpochMilli(),
                productionGroup = "A",
                shiftId = null,
                shiftBusinessDate = "2026-08-13",
                shiftType = "NIGHT",
                shiftStartAt = null,
                shiftEndAt = null,
                isShiftChange = false,
                isCurrent = true,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        val reused = useCase.ensureDefaultForDate(LocalDate.of(2026, 8, 14))

        assertEquals("night-1", reused.id)
        assertEquals(1, database!!.attendanceDao().countCurrent())
    }

    @Test
    fun updateDoesNotRewriteExistingWorkLogSnapshots() = runBlocking {
        open()
        val useCase = AttendanceUseCase(database!!, clock, zoneId)
        val attendance = useCase.save(normalInput(LocalDate.of(2026, 8, 14)))
        val logId = FaultDraftRepository(database!!, clock).createManual("普通工作", "2026-08-14")
        val before = database!!.workLogDao().findById(logId)!!

        useCase.update(
            attendance.id,
            AttendancePatch(
                kind = AttendanceKind.CUSTOM,
                startAt = LocalDateTime.of(2026, 8, 14, 9, 0).atZone(zoneId).toInstant(),
                endAt = LocalDateTime.of(2026, 8, 14, 21, 0).atZone(zoneId).toInstant(),
                productionGroup = null,
            ),
        )

        val after = database!!.workLogDao().findById(logId)!!
        assertEquals(before.attendanceKindSnapshot, after.attendanceKindSnapshot)
        assertEquals(before.attendanceStartAt, after.attendanceStartAt)
        assertEquals(before.attendanceEndAt, after.attendanceEndAt)
        assertEquals("NORMAL", after.attendanceKindSnapshot)
    }

    @Test
    fun saveRejectsStartNotBeforeEnd() = runBlocking {
        open()
        val useCase = AttendanceUseCase(database!!, clock, zoneId)
        val start = LocalDateTime.of(2026, 8, 14, 9, 0).atZone(zoneId).toInstant()
        var rejected = false
        try {
            useCase.save(AttendanceInput(LocalDate.of(2026, 8, 14), AttendanceKind.NORMAL, start, start, null))
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    private fun normalInput(date: LocalDate) = AttendanceInput(
        businessDate = date,
        kind = AttendanceKind.NORMAL,
        startAt = date.atTime(8, 0).atZone(zoneId).toInstant(),
        endAt = date.atTime(18, 0).atZone(zoneId).toInstant(),
        productionGroup = null,
    )

    private suspend fun insertAttendance(entity: AttendanceEntity) {
        database!!.attendanceDao().insert(entity)
    }

    private fun open() {
        database = Room.inMemoryDatabaseBuilder(context, CodaDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(CodaDatabaseCallback())
            .build()
    }
}
