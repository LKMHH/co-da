package com.coda.workbench.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.coda.workbench.data.repository.FaultDraftRepository
import com.coda.workbench.data.repository.AttendanceRepository
import com.coda.workbench.data.repository.DeviceRepository
import com.coda.workbench.core.usecase.FaultEntryUseCase
import com.coda.workbench.core.model.AttendanceKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoomM2JvmIntegrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "coda-m2-${UUID.randomUUID()}.db"
    private var database: CodaDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun draftSurvivesCloseAndReopenAndRemainsEditable() = runBlocking {
        database = Room.databaseBuilder(context, CodaDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        var repository = FaultDraftRepository(database!!, java.time.Clock.systemUTC())
        val processingId = repository.createMinimalDraft("一号楼配电箱", "信号弱", 2L)
        database!!.close()
        database = Room.databaseBuilder(context, CodaDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()

        val reopened = database!!.faultProcessingDao().findById(processingId)
        assertNotNull(reopened)
        assertEquals("DRAFT", reopened!!.progressStatus)

        repository = FaultDraftRepository(database!!, java.time.Clock.systemUTC())
        repository.updateDraftSymptom(reopened.faultId, "断路器信号强度偏弱")
        assertEquals(
            "断路器信号强度偏弱",
            database!!.faultRecordDao().findById(reopened.faultId)!!.symptom,
        )
    }

    @Test
    fun faultEntryUseCaseCanSaveTwoDifferentFaultsSequentially() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, CodaDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(CodaDatabaseCallback())
            .build()
        val clock = Clock.fixed(Instant.parse("2026-08-14T04:00:00Z"), ZoneId.of("Asia/Shanghai"))
        val drafts = FaultDraftRepository(database!!, clock)
        val devices = DeviceRepository(database!!.deviceDao(), clock)
        val useCase = FaultEntryUseCase(drafts, devices, clock)

        val first = useCase.save("一号配电箱", 1_000L, "信号弱")
        val second = useCase.save("二号配电箱", 2_000L, "温度偏高")

        assertNotEquals(first, second)
    }

    @Test
    fun invalidWorkLogSourceIsRejectedByDatabaseConstraint() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, CodaDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(CodaDatabaseCallback())
            .build()
        val invalid = WorkLogEntity(
            id = UUID.randomUUID().toString(),
            kind = "MANUAL",
            content = "错误来源",
            workDate = "2026-08-14",
            attendanceId = null,
            attendanceKindSnapshot = null,
            attendanceStartAt = null,
            attendanceEndAt = null,
            productionGroupSnapshot = null,
            shiftIdSnapshot = null,
            shiftBusinessDateSnapshot = null,
            shiftTypeSnapshot = null,
            shiftStartAtSnapshot = null,
            shiftEndAtSnapshot = null,
            isShiftChangeSnapshot = null,
            workResult = null,
            deviceId = null,
            area = null,
            deviceNameSnapshot = null,
            processingStartedAt = null,
            processingEndedAt = null,
            processedAt = null,
            restoreResult = null,
            arrangementSource = null,
            sourceType = "FAULT_PROCESSING",
            sourceId = "processing-id",
            status = "ACTIVE",
            createdAt = 1L,
            updatedAt = 1L,
            voidedAt = null,
        )
        var rejected = false
        try {
            database!!.workLogDao().insert(invalid)
        } catch (_: Exception) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun firstUseCreatesDefaultNormalAttendanceIdempotently() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, CodaDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(CodaDatabaseCallback())
            .build()
        val zone = ZoneId.of("Asia/Shanghai")
        val clock = Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), zone)
        val repository = AttendanceRepository(database!!, clock = clock)

        val first = repository.ensureCurrent(zone) { "attendance-1" }
        val second = repository.ensureCurrent(zone) { "attendance-2" }

        assertEquals("attendance-1", first.id)
        assertEquals(first.id, second.id)
        assertEquals(AttendanceKind.NORMAL, first.kind)
        assertEquals(1, database!!.attendanceDao().countCurrent())
    }

    @Test
    fun previousDayTopNightAttendanceIsReusedAfterMidnight() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, CodaDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(CodaDatabaseCallback())
            .build()
        val zone = ZoneId.of("Asia/Shanghai")
        val start = LocalDateTime.of(2026, 8, 14, 20, 0).atZone(zone).toInstant()
        val end = LocalDateTime.of(2026, 8, 15, 8, 0).atZone(zone).toInstant()
        database!!.attendanceDao().insert(
            AttendanceEntity(
                id = "night-1",
                businessDate = "2026-08-14",
                kind = "TOP_NIGHT",
                startAt = start.toEpochMilli(),
                endAt = end.toEpochMilli(),
                productionGroup = "A",
                shiftId = "shift-1",
                shiftBusinessDate = "2026-08-14",
                shiftType = "NIGHT",
                shiftStartAt = start.toEpochMilli(),
                shiftEndAt = end.toEpochMilli(),
                isShiftChange = false,
                isCurrent = true,
                createdAt = start.toEpochMilli(),
                updatedAt = start.toEpochMilli(),
            ),
        )
        val clock = Clock.fixed(Instant.parse("2026-08-15T00:30:00Z"), zone)
        val repository = AttendanceRepository(database!!, clock = clock)

        val resolved = repository.ensureCurrent(zone) { "must-not-create" }

        assertEquals("night-1", resolved.id)
        assertEquals(AttendanceKind.TOP_NIGHT, resolved.kind)
        assertEquals(1, database!!.attendanceDao().countCurrent())
    }

    @Test
    fun schemaContainsAllM2TablesAndRequiredIndexes() {
        database = Room.inMemoryDatabaseBuilder(context, CodaDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(CodaDatabaseCallback())
            .build()
        val db = database!!.openHelper.readableDatabase
        val tables = db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'room_%'",
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertTrue(
            tables.containsAll(
                setOf(
                    "device",
                    "device_alias",
                    "fault_record",
                    "fault_processing",
                    "work_log",
                    "handover_item",
                    "attendance",
                    "monthly_shift_plan",
                    "shift_slot",
                    "backup_import_log",
                ),
            ),
        )
        val indexes = db.query(
            "SELECT name, sql FROM sqlite_master WHERE type='index'",
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1) ?: "")
            }
        }
        assertTrue(indexes.containsKey("ux_work_log_source"))
        assertTrue(indexes.containsKey("ux_handover_auto_source"))
        assertTrue(indexes["ux_attendance_current"]!!.contains("WHERE isCurrent = 1"))
        val workLogSql = db.query(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name='work_log'",
        ).use {
            it.moveToFirst()
            it.getString(0)
        }
        assertTrue(workLogSql.contains("CHECK"))
    }
}
