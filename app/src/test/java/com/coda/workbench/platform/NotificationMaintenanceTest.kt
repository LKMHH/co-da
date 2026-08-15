package com.coda.workbench.platform

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.coda.workbench.data.local.CodaDatabase
import com.coda.workbench.data.local.CodaDatabaseCallback
import com.coda.workbench.data.local.HandoverItemEntity
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationMaintenanceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zoneId = ZoneId.of("Asia/Shanghai")
    // 2026-08-14 12:00 +08:00
    private val clock = Clock.fixed(Instant.parse("2026-08-14T04:00:00Z"), zoneId)
    private var database: CodaDatabase? = null
    private var dataScope: CoroutineScope? = null
    private var store: NotificationSettingsStore? = null
    private val poster = FakePoster()

    @Before
    fun setUp() {
        dataScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        store = NotificationSettingsStore(
            PreferenceDataStoreFactory.create(
                scope = dataScope!!,
                produceFile = { File(context.filesDir, "maintenance-test-${UUID.randomUUID()}.preferences_pb") },
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
    fun overdueNotifiesOncePerNaturalDay() = runBlocking {
        val id = insertItem(dueAt = clock.millis() - 86_400_000L)
        val target = maintenance()

        target.onDailyCheck()
        assertEquals(listOf(id), poster.overdue)

        target.onDailyCheck()
        assertEquals(listOf(id), poster.overdue)
        assertEquals("2026-08-14", database!!.handoverItemDao().findById(id)!!.lastOverdueNoticeDate)
    }

    @Test
    fun dueAlarmPostsOnlyForStillPendingItems() = runBlocking {
        val id = insertItem(dueAt = clock.millis() - 60_000L)
        val target = maintenance()

        target.onDueAlarm(id)
        assertEquals(listOf(id), poster.due)

        database!!.handoverItemDao().updateStatus(id, "COMPLETED", clock.millis(), clock.millis())
        target.onDueAlarm(id)
        assertEquals(listOf(id), poster.due)
    }

    @Test
    fun voidedItemNeverPostsDueAlarm() = runBlocking {
        val id = insertItem(dueAt = clock.millis() - 60_000L)
        database!!.handoverItemDao().markVoided(id, clock.millis(), clock.millis())

        maintenance().onDueAlarm(id)

        assertTrue(poster.due.isEmpty())
    }

    @Test
    fun firstOfUnconfirmedMonthPostsShiftPromptOnce() = runBlocking {
        // 2026-09-01 04:00 +08:00
        val sep1Clock = Clock.fixed(Instant.parse("2026-08-31T20:00:00Z"), zoneId)
        val target = NotificationMaintenance(database!!, sep1Clock, zoneId, store!!, poster)

        target.onDailyCheck()
        assertEquals(listOf("2026-09"), poster.shiftPromptMonths)

        target.onDailyCheck()
        assertEquals(listOf("2026-09"), poster.shiftPromptMonths) // 本月已提示，去重
    }

    @Test
    fun nonFirstDayDoesNotPostShiftPrompt() = runBlocking {
        // 2026-08-15 00:30 +08:00
        val day15Clock = Clock.fixed(Instant.parse("2026-08-14T16:30:00Z"), zoneId)
        val target = NotificationMaintenance(database!!, day15Clock, zoneId, store!!, poster)

        target.onDailyCheck()

        assertTrue(poster.shiftPromptMonths.isEmpty())
    }

    @Test
    fun shiftPromptDedupedByLastPromptMonth() = runBlocking {
        // 必须在 1 号才能真正走到 lastPromptMonth 去重分支（弱测试修复）
        store!!.setLastShiftPromptMonth("2026-09")
        val sep1Clock = Clock.fixed(Instant.parse("2026-08-31T20:00:00Z"), zoneId)
        val target = NotificationMaintenance(database!!, sep1Clock, zoneId, store!!, poster)

        target.onDailyCheck()

        assertTrue(poster.shiftPromptMonths.isEmpty())
    }

    @Test
    fun disabledSettingsSuppressAllPosts() = runBlocking {
        store!!.setEnabled(false)
        val id = insertItem(dueAt = clock.millis() - 60_000L)
        val target = maintenance()

        target.onDailyCheck()
        target.onDueAlarm(id)

        assertTrue(poster.due.isEmpty())
        assertTrue(poster.overdue.isEmpty())
        assertTrue(poster.shiftPromptMonths.isEmpty())
    }

    private fun maintenance(): NotificationMaintenance =
        NotificationMaintenance(database!!, clock, zoneId, store!!, poster)

    private suspend fun insertItem(dueAt: Long?): String {
        val id = UUID.randomUUID().toString()
        database!!.handoverItemDao().insert(
            HandoverItemEntity(
                id = id,
                summary = "测试事项",
                status = "PENDING_HANDOVER",
                nextAction = "下一步",
                dueKind = if (dueAt == null) "NONE" else "SPECIFIC",
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

    private class FakePoster : NotificationPoster {
        val due = mutableListOf<String>()
        val overdue = mutableListOf<String>()
        val shiftPromptMonths = mutableListOf<String>()

        override fun postDue(itemId: String, summary: String, nextAction: String) {
            due += itemId
        }

        override fun postOverdue(itemId: String, summary: String, nextAction: String) {
            overdue += itemId
        }

        override fun postShiftPrompt(month: String) {
            shiftPromptMonths += month
        }
    }
}
