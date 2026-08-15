package com.coda.workbench.core.usecase

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.coda.workbench.core.model.SearchFilters
import com.coda.workbench.core.model.SearchRecordType
import com.coda.workbench.data.local.CodaDatabase
import com.coda.workbench.data.local.CodaDatabaseCallback
import com.coda.workbench.data.local.DeviceAliasEntity
import com.coda.workbench.data.local.DeviceEntity
import com.coda.workbench.data.local.FaultProcessingEntity
import com.coda.workbench.data.local.FaultRecordEntity
import com.coda.workbench.data.local.HandoverItemEntity
import com.coda.workbench.data.local.WorkLogEntity
import com.coda.workbench.data.repository.SearchRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SearchUseCaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private var database: CodaDatabase? = null
    private var repository: SearchRepository? = null

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, CodaDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(CodaDatabaseCallback())
            .build()
        repository = SearchRepository(database!!, zoneId)
        seedData()
    }

    @After
    fun tearDown() {
        database?.close()
    }

    @Test
    fun signalWeakHitsSynonymFaultAndDirectWork() = runBlocking {
        val results = repository!!.search("信号弱", SearchFilters())

        val ids = results.map { it.id }.toSet()
        assertTrue(ids.contains("fault-1")) // 同义词命中
        assertTrue(ids.contains("work-1")) // 原文命中
        assertFalse(ids.contains("fault-2"))
        assertFalse(ids.contains("work-3"))

        val fault = results.single { it.id == "fault-1" }
        assertTrue(fault.expandedMatch) // 现象里没有"信号弱"原词，靠同义词扩展
        val work = results.single { it.id == "work-1" }
        assertFalse(work.expandedMatch)
    }

    @Test
    fun aliasHitsLinkedFaultAndWork() = runBlocking {
        val results = repository!!.search("雷达1", SearchFilters())

        val ids = results.map { it.id }.toSet()
        assertTrue(ids.contains("fault-1"))
        assertTrue(ids.contains("work-1")) // deviceId 关联命中
        assertFalse(ids.contains("fault-2"))
        assertTrue(results.all { it.expandedMatch })
    }

    @Test
    fun voidedHiddenByDefaultAndShownWithFilter() = runBlocking {
        assertFalse(repository!!.search("100%", SearchFilters()).any { it.id == "work-2" })

        val withVoided = repository!!.search("100%", SearchFilters(includeVoided = true))
        val voidedWork = withVoided.single { it.id == "work-2" }
        assertEquals("已作废", voidedWork.statusText)
    }

    @Test
    fun likeWildcardsAreTreatedLiterally() = runBlocking {
        // "%" 只命中真正包含百分号的内容，不会当成通配符命中全部
        val percent = repository!!.search("%", SearchFilters(includeVoided = true))
        assertEquals(listOf("work-2"), percent.map { it.id })

        // "_" 转义后无命中
        assertTrue(repository!!.search("_", SearchFilters()).isEmpty())
    }

    @Test
    fun recordTypeFilterLimitsResults() = runBlocking {
        val handoverOnly = repository!!.search("信号", SearchFilters(recordTypes = setOf(SearchRecordType.HANDOVER)))
        assertTrue(handoverOnly.all { it.type == SearchRecordType.HANDOVER })
        assertTrue(handoverOnly.any { it.id == "handover-2" })
        assertFalse(handoverOnly.any { it.id == "fault-1" })
    }

    @Test
    fun dateRangeFilterExcludesOlderFault() = runBlocking {
        val results = repository!!.search(
            "信号",
            SearchFilters(dateFrom = LocalDate.of(2026, 8, 14)),
        )
        // fault-1 接报时间 2026-08-10，被排除；work-1 是 2026-08-14 保留
        assertFalse(results.any { it.id == "fault-1" })
        assertTrue(results.any { it.id == "work-1" })
    }

    @Test
    fun processingStatusFilterAppliesToFaults() = runBlocking {
        val draftOnly = repository!!.search("信号", SearchFilters(processingStatuses = setOf("DRAFT")))
        assertTrue(draftOnly.any { it.id == "fault-1" })

        val endedOnly = repository!!.search("信号", SearchFilters(processingStatuses = setOf("ENDED")))
        assertFalse(endedOnly.any { it.id == "fault-1" })
    }

    @Test
    fun attendanceKindFilterAppliesToWorkLogs() = runBlocking {
        val nightOnly = repository!!.search("信号", SearchFilters(attendanceKinds = setOf("TOP_NIGHT")))
        assertFalse(nightOnly.any { it.id == "work-1" })

        val normalOnly = repository!!.search("信号", SearchFilters(attendanceKinds = setOf("NORMAL")))
        assertTrue(normalOnly.any { it.id == "work-1" })
    }

    @Test
    fun searchUseCaseExposesFlow() = runBlocking {
        val results = SearchUseCase(repository!!).search("信号弱", SearchFilters()).first()
        assertTrue(results.any { it.id == "fault-1" })
    }

    @Test
    fun blankQueryReturnsEmpty() = runBlocking {
        assertTrue(repository!!.search("   ", SearchFilters()).isEmpty())
    }

    // ---- 数据准备 ----

    private suspend fun seedData() {
        val db = database!!
        db.deviceDao().insert(DeviceEntity("device-1", "1号雷达", "1号雷达", true, 1L, 1L))
        db.deviceDao().insert(DeviceEntity("device-2", "3号线", "3号线", true, 1L, 1L))
        db.deviceAliasDao().insert(DeviceAliasEntity("alias-1", "device-1", "雷达1", "雷达1", 1L, 1L))

        val faultReportedAt = LocalDateTime.of(2026, 8, 10, 9, 0).atZone(zoneId).toInstant().toEpochMilli()
        db.faultRecordDao().insert(
            FaultRecordEntity(
                id = "fault-1", deviceId = "device-1", deviceNameSnapshot = "1号雷达",
                reportedAt = faultReportedAt, symptom = "信号强度偏弱", lifecycleStatus = "OPEN",
                lastProcessingId = "proc-1", createdAt = 1L, updatedAt = 1L, voidedAt = null,
            ),
        )
        db.faultProcessingDao().insert(
            FaultProcessingEntity(
                id = "proc-1", faultId = "fault-1", progressStatus = "DRAFT", restoreResult = null,
                startedAt = null, endedAt = null, checkResult = "检查正常", initialJudgement = null,
                rootCause = null, measures = null, verification = null, createdAt = 1L, updatedAt = 1L,
                completedAt = null, voidedAt = null,
            ),
        )
        db.faultRecordDao().insert(
            FaultRecordEntity(
                id = "fault-2", deviceId = "device-2", deviceNameSnapshot = "3号线",
                reportedAt = faultReportedAt, symptom = "温度偏高", lifecycleStatus = "OPEN",
                lastProcessingId = null, createdAt = 1L, updatedAt = 1L, voidedAt = null,
            ),
        )

        db.workLogDao().insert(
            WorkLogEntity(
                id = "work-1", kind = "MANUAL", content = "检查信号弱", workDate = "2026-08-14",
                attendanceId = null, attendanceKindSnapshot = "NORMAL", attendanceStartAt = null,
                attendanceEndAt = null, productionGroupSnapshot = null, shiftIdSnapshot = null,
                shiftBusinessDateSnapshot = null, shiftTypeSnapshot = null, shiftStartAtSnapshot = null,
                shiftEndAtSnapshot = null, isShiftChangeSnapshot = null, workResult = "完成",
                deviceId = "device-1", area = null, deviceNameSnapshot = null,
                processingStartedAt = null, processingEndedAt = null, processedAt = null,
                restoreResult = null, arrangementSource = "MANUAL", sourceType = null, sourceId = null,
                status = "ACTIVE", createdAt = 1L, updatedAt = 1L, voidedAt = null,
            ),
        )
        db.workLogDao().insert(
            WorkLogEntity(
                id = "work-2", kind = "MANUAL", content = "完成度100%", workDate = "2026-08-14",
                attendanceId = null, attendanceKindSnapshot = "NORMAL", attendanceStartAt = null,
                attendanceEndAt = null, productionGroupSnapshot = null, shiftIdSnapshot = null,
                shiftBusinessDateSnapshot = null, shiftTypeSnapshot = null, shiftStartAtSnapshot = null,
                shiftEndAtSnapshot = null, isShiftChangeSnapshot = null, workResult = null,
                deviceId = null, area = null, deviceNameSnapshot = null,
                processingStartedAt = null, processingEndedAt = null, processedAt = null,
                restoreResult = null, arrangementSource = "MANUAL", sourceType = null, sourceId = null,
                status = "ACTIVE", createdAt = 1L, updatedAt = 1L, voidedAt = 2L,
            ),
        )
        db.workLogDao().insert(
            WorkLogEntity(
                id = "work-3", kind = "MANUAL", content = "巡检三号线", workDate = "2026-08-14",
                attendanceId = null, attendanceKindSnapshot = "NORMAL", attendanceStartAt = null,
                attendanceEndAt = null, productionGroupSnapshot = null, shiftIdSnapshot = null,
                shiftBusinessDateSnapshot = null, shiftTypeSnapshot = null, shiftStartAtSnapshot = null,
                shiftEndAtSnapshot = null, isShiftChangeSnapshot = null, workResult = null,
                deviceId = null, area = null, deviceNameSnapshot = null,
                processingStartedAt = null, processingEndedAt = null, processedAt = null,
                restoreResult = null, arrangementSource = "MANUAL", sourceType = null, sourceId = null,
                status = "ACTIVE", createdAt = 1L, updatedAt = 1L, voidedAt = null,
            ),
        )

        db.handoverItemDao().insert(
            HandoverItemEntity(
                id = "handover-1", summary = "有效回波偏弱待查", status = "PENDING_HANDOVER",
                nextAction = "明天复测", dueKind = "NONE", dueAt = null, originType = "MANUAL",
                sourceType = null, sourceId = null, handoverGroup = null, potentialHazardNote = null,
                lastOverdueNoticeDate = null, createdAt = 1L, updatedAt = 1L, completedAt = null, voidedAt = null,
            ),
        )
        db.handoverItemDao().insert(
            HandoverItemEntity(
                id = "handover-2", summary = "信号线领取备件", status = "PENDING_HANDOVER",
                nextAction = "领备件", dueKind = "NONE", dueAt = null, originType = "MANUAL",
                sourceType = null, sourceId = null, handoverGroup = null, potentialHazardNote = null,
                lastOverdueNoticeDate = null, createdAt = 1L, updatedAt = 1L, completedAt = null, voidedAt = null,
            ),
        )
    }
}
