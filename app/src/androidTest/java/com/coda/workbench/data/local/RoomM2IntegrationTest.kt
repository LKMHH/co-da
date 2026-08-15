package com.coda.workbench.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class RoomM2IntegrationTest {
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
        val device = DeviceEntity(
            id = UUID.randomUUID().toString(),
            name = "一号楼配电箱",
            normalizedName = "一号楼配电箱",
            isActive = true,
            createdAt = 1L,
            updatedAt = 1L,
        )
        val fault = FaultRecordEntity(
            id = UUID.randomUUID().toString(),
            deviceId = device.id,
            deviceNameSnapshot = device.name,
            reportedAt = 2L,
            symptom = "信号弱",
            lifecycleStatus = "OPEN",
            lastProcessingId = null,
            createdAt = 2L,
            updatedAt = 2L,
            voidedAt = null,
        )
        val processing = FaultProcessingEntity(
            id = UUID.randomUUID().toString(),
            faultId = fault.id,
            progressStatus = "DRAFT",
            restoreResult = null,
            startedAt = null,
            endedAt = null,
            checkResult = null,
            initialJudgement = null,
            rootCause = null,
            measures = null,
            verification = null,
            createdAt = 2L,
            updatedAt = 2L,
            completedAt = null,
            voidedAt = null,
        )
        database!!.faultDraftRepository().createDraft(device, fault, processing)
        database!!.close()
        database = Room.databaseBuilder(context, CodaDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()

        val reopened = database!!.faultProcessingDao().findById(processing.id)
        assertNotNull(reopened)
        assertEquals("DRAFT", reopened!!.progressStatus)

        database!!.faultRecordDao().updateSymptom(fault.id, "断路器信号强度偏弱", 3L)
        assertEquals("断路器信号强度偏弱", database!!.faultRecordDao().findById(fault.id)!!.symptom)
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
        assertEquals(true, rejected)
    }

}
