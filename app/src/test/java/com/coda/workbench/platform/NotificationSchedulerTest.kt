package com.coda.workbench.platform

import android.app.PendingIntent
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.coda.workbench.data.local.CodaDatabase
import com.coda.workbench.data.local.CodaDatabaseCallback
import com.coda.workbench.data.local.HandoverItemEntity
import com.coda.workbench.data.local.MonthlyShiftPlanEntity
import com.coda.workbench.data.local.ShiftSlotEntity
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zoneId = ZoneId.of("Asia/Shanghai")
    // 2026-08-14 12:00 +08:00
    private val clock = Clock.fixed(Instant.parse("2026-08-14T04:00:00Z"), zoneId)
    private var database: CodaDatabase? = null
    private var dataScope: CoroutineScope? = null
    private var store: NotificationSettingsStore? = null
    private val gateway = FakeAlarmGateway()

    @Before
    fun setUp() {
        dataScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        store = NotificationSettingsStore(
            PreferenceDataStoreFactory.create(
                scope = dataScope!!,
                produceFile = { File(context.filesDir, "scheduler-test-${UUID.randomUUID()}.preferences_pb") },
            ),
        )
        database = Room.inMemoryDatabaseBuilder(context, CodaDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(CodaDatabaseCallback())
            .build()
    }

    @After
    fun tearDown() {
        database?.close()
        dataScope?.cancel()
    }

    @Test
    fun reconcileSchedulesSingleFutureDueAlarm() = runBlocking {
        val id = insertItem(dueAt = clock.millis() + 3_600_000L)
        scheduler().reconcile(id)

        assertEquals(1, gateway.scheduled.size)
        assertEquals(clock.millis() + 3_600_000L, gateway.scheduled.first().first)
    }

    @Test
    fun reconcileDoesNotSchedulePastOrTerminalItems() = runBlocking {
        val past = insertItem(dueAt = clock.millis() - 1L)
        scheduler().reconcile(past)
        assertEquals(0, gateway.scheduled.size)

        val completed = insertItem(dueAt = clock.millis() + 3_600_000L, status = "COMPLETED")
        scheduler().reconcile(completed)
        assertEquals(0, gateway.scheduled.size)
    }

    @Test
    fun reconcileIsIdempotentOnRepeatCalls() = runBlocking {
        val id = insertItem(dueAt = clock.millis() + 3_600_000L)
        val target = scheduler()

        target.reconcile(id)
        target.reconcile(id)

        assertEquals(2, gateway.canceled.size)
        assertEquals(2, gateway.scheduled.size)
    }

    @Test
    fun disabledSwitchSuppressesSchedulingAndCancelAllClears() = runBlocking {
        val id = insertItem(dueAt = clock.millis() + 3_600_000L)
        val target = scheduler()
        target.reconcile(id)
        assertEquals(1, gateway.scheduled.size)

        store!!.setEnabled(false)
        val scheduledBeforeDisable = gateway.scheduled.size
        target.reconcile(id)
        target.reconcileAll()

        // 关闭后不再新增任何调度，只做取消
        assertEquals(scheduledBeforeDisable, gateway.scheduled.size)
        assertTrue(gateway.canceled.isNotEmpty())
    }

    @Test
    fun reconcileAllFillsNextShiftDueAndSchedules() = runBlocking {
        val nextStart = LocalDateTime.of(2026, 8, 14, 20, 0).atZone(zoneId).toInstant().toEpochMilli()
        insertSlot(nextStart)
        val id = insertItem(dueAt = null, dueKind = "NEXT_SHIFT")
        val target = scheduler()

        target.reconcileAll()

        assertEquals(nextStart, database!!.handoverItemDao().findById(id)!!.dueAt)
        val dueAlarms = gateway.scheduled.filter { it.first == nextStart }
        assertEquals(1, dueAlarms.size)
    }

    @Test
    fun reconcileAllRecomputesExistingNextShiftDueAfterShiftChange() = runBlocking {
        // 旧 deadline 已被新的班次开始时间取代
        val oldStart = LocalDateTime.of(2026, 8, 14, 20, 0).atZone(zoneId).toInstant().toEpochMilli()
        val newStart = LocalDateTime.of(2026, 8, 14, 22, 0).atZone(zoneId).toInstant().toEpochMilli()
        insertSlot(newStart)
        val id = insertItem(dueAt = oldStart, dueKind = "NEXT_SHIFT")
        val target = scheduler()

        target.reconcileAll()

        assertEquals(newStart, database!!.handoverItemDao().findById(id)!!.dueAt)
        assertTrue(gateway.scheduled.any { it.first == newStart })
    }

    @Test
    fun reconcileAllWithoutSlotsKeepsNextShiftDueNull() = runBlocking {
        val id = insertItem(dueAt = null, dueKind = "NEXT_SHIFT")

        scheduler().reconcileAll()

        assertNull(database!!.handoverItemDao().findById(id)!!.dueAt)
    }

    @Test
    fun ensureDailyArmsNextNineOClockLocal() = runBlocking {
        val target = scheduler()
        target.ensureDaily()

        val expected = LocalDateTime.of(2026, 8, 15, 9, 0).atZone(zoneId).toInstant().toEpochMilli()
        assertEquals(listOf(expected), gateway.scheduled.map { it.first })
    }

    private fun scheduler(): NotificationScheduler =
        NotificationScheduler(context, database!!, clock, store!!, zoneId, gateway)

    private suspend fun insertItem(
        dueAt: Long?,
        dueKind: String = if (dueAt == null) "NONE" else "SPECIFIC",
        status: String = "PENDING_HANDOVER",
    ): String {
        val id = UUID.randomUUID().toString()
        database!!.handoverItemDao().insert(
            HandoverItemEntity(
                id = id,
                summary = "测试事项",
                status = status,
                nextAction = "下一步",
                dueKind = dueKind,
                dueAt = dueAt,
                originType = "MANUAL",
                sourceType = null,
                sourceId = null,
                handoverGroup = null,
                potentialHazardNote = null,
                lastOverdueNoticeDate = null,
                createdAt = 1L,
                updatedAt = 1L,
                completedAt = null,
                voidedAt = null,
            ),
        )
        return id
    }

    private suspend fun insertSlot(nextStart: Long) {
        val now = clock.millis()
        val planId = UUID.randomUUID().toString()
        database!!.monthlyShiftPlanDao().insert(
            MonthlyShiftPlanEntity(planId, "2026-08", 1, 16, confirmedAt = now, createdAt = now, updatedAt = now),
        )
        database!!.shiftSlotDao().insert(
            ShiftSlotEntity(
                id = UUID.randomUUID().toString(),
                planId = planId,
                businessDate = "2026-08-14",
                group = "B",
                shiftType = "NIGHT",
                startAt = nextStart,
                endAt = LocalDateTime.of(2026, 8, 15, 8, 0).atZone(zoneId).toInstant().toEpochMilli(),
                isShiftChange = false,
                source = "SUGGESTED",
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private class FakeAlarmGateway : AlarmGateway {
        val scheduled = mutableListOf<Pair<Long, PendingIntent>>()
        val canceled = mutableListOf<PendingIntent>()

        override fun schedule(wakeAtMillis: Long, operation: PendingIntent) {
            scheduled += wakeAtMillis to operation
        }

        override fun cancel(operation: PendingIntent) {
            canceled += operation
        }
    }
}
