package com.coda.workbench.core.usecase

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.coda.workbench.data.local.CodaDatabase
import com.coda.workbench.data.local.HandoverItemEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HandoverUseCaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var database: CodaDatabase? = null
    private val clock = Clock.fixed(Instant.parse("2026-08-14T04:00:00Z"), ZoneId.of("Asia/Shanghai"))

    @After
    fun tearDown() {
        database?.close()
    }

    @Test
    fun happyPathFromPendingToCompleted() = runBlocking {
        open()
        val id = insertItem("PENDING_HANDOVER")
        val useCase = HandoverUseCase(database!!, clock)

        useCase.markHandedOver(id)
        assertEquals("HANDED_OVER", item(id).status)
        useCase.markInProgress(id)
        assertEquals("IN_PROGRESS", item(id).status)
        useCase.complete(id)
        val done = item(id)
        assertEquals("COMPLETED", done.status)
        assertNotNull(done.completedAt)
    }

    @Test
    fun invalidTransitionsAreRejected() = runBlocking {
        open()
        val id = insertItem("PENDING_HANDOVER")
        val useCase = HandoverUseCase(database!!, clock)

        assertFailsWith<IllegalStateException> { useCase.markInProgress(id) }
        assertFailsWith<IllegalStateException> { useCase.complete(id) }
        assertEquals("PENDING_HANDOVER", item(id).status)
    }

    @Test
    fun cancelFromEveryActiveStatusBecomesVisibleCanceled() = runBlocking {
        open()
        val useCase = HandoverUseCase(database!!, clock)
        for (status in listOf("PENDING_HANDOVER", "HANDED_OVER", "IN_PROGRESS")) {
            val id = insertItem(status)
            useCase.cancel(id)
            assertEquals("CANCELED", item(id).status)
        }
    }

    @Test
    fun cancelOnTerminalStatusesIsRejected() = runBlocking {
        open()
        val useCase = HandoverUseCase(database!!, clock)
        for (status in listOf("COMPLETED", "CANCELED")) {
            val id = insertItem(status)
            assertFailsWith<IllegalStateException> { useCase.cancel(id) }
        }
    }

    @Test
    fun voidKeepsBusinessStatusButHidesFromPendingList() = runBlocking {
        open()
        val id = insertItem("IN_PROGRESS")
        val useCase = HandoverUseCase(database!!, clock)

        useCase.void(id)
        val item = item(id)
        assertEquals("IN_PROGRESS", item.status)
        assertNotNull(item.voidedAt)
        assertTrue(database!!.handoverItemDao().pending().none { it.id == id })
    }

    @Test
    fun createNormalizesBlankSummaryToFollowUpTitle() = runBlocking {
        open()
        val useCase = HandoverUseCase(database!!, clock)
        val id = useCase.create(
            CreateHandoverInput(
                summary = "   ",
                nextAction = "明天再查",
                dueKind = HandoverDueKind.NONE,
                dueAt = null,
                handoverGroup = "A",
                potentialHazardNote = "注意",
            ),
        )
        val item = item(id)
        assertEquals("待跟进事项", item.summary)
        assertEquals("PENDING_HANDOVER", item.status)
        assertEquals("A", item.handoverGroup)
        assertEquals("注意", item.potentialHazardNote)
    }

    @Test
    fun createRequiresNextAction() = runBlocking {
        open()
        val useCase = HandoverUseCase(database!!, clock)
        assertFailsWith<IllegalArgumentException> {
            useCase.create(CreateHandoverInput(null, " ", HandoverDueKind.NONE, null, null, null))
        }
        Unit
    }

    @Test
    fun updateChangesFieldsOnActiveStatuses() = runBlocking {
        open()
        val useCase = HandoverUseCase(database!!, clock)
        val id = insertItem("PENDING_HANDOVER")
        useCase.update(
            id,
            UpdateHandoverInput("新摘要", "新动作", HandoverDueKind.SPECIFIC, 42L, "B", null),
        )
        val updated = item(id)
        assertEquals("新摘要", updated.summary)
        assertEquals("新动作", updated.nextAction)
        assertEquals("SPECIFIC", updated.dueKind)
        assertEquals(42L, updated.dueAt)
        assertEquals("B", updated.handoverGroup)

        val completedId = insertItem("COMPLETED")
        assertFailsWith<IllegalStateException> {
            useCase.update(completedId, UpdateHandoverInput("x", "y", HandoverDueKind.NONE, null, null, null))
        }
        Unit
    }

    private fun open() {
        database = Room.inMemoryDatabaseBuilder(context, CodaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    private suspend fun insertItem(status: String): String {
        val id = UUID.randomUUID().toString()
        database!!.handoverItemDao().insert(
            HandoverItemEntity(
                id = id,
                summary = "测试事项",
                status = status,
                nextAction = "下一步",
                dueKind = "NONE",
                dueAt = null,
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

    private suspend fun item(id: String): HandoverItemEntity =
        database!!.handoverItemDao().findById(id)!!
}
