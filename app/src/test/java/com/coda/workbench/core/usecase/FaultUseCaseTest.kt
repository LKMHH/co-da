package com.coda.workbench.core.usecase

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.coda.workbench.data.local.CodaDatabase
import com.coda.workbench.data.repository.FaultDraftRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class FaultUseCaseTest {
    private var database: CodaDatabase? = null

    @After
    fun tearDown() {
        database?.close()
    }

    @Test
    fun finishIsIdempotentAndReturnsTheSameWorkLogAndHandover() = runBlocking {
        val useCase = newStartedUseCase()
        val processingId = database!!.faultProcessingDao().findLatest()!!.id

        val first = useCase.finishProcessing(processingId, RestoreResult.PARTIAL)
        val second = useCase.finishProcessing(processingId, RestoreResult.PARTIAL)

        assertEquals(first.workLogId, second.workLogId)
        assertEquals(first.handoverId, second.handoverId)
        assertNotNull(first.handoverId)
    }

    @Test
    fun separateProcessingRecordsProduceSeparateLogsAndPreserveFirstSnapshot() = runBlocking {
        val useCase = newStartedUseCase(deviceName = "设备甲", symptom = "第一次症状")
        val firstProcessing = database!!.faultProcessingDao().findLatest()!!
        val firstResult = useCase.finishProcessing(firstProcessing.id, RestoreResult.NOT_RESTORED)
        val second = useCase.continueProcessing(firstProcessing.id)
        database!!.faultRecordDao().updateSymptom(second.faultId, "第二次症状", 3L)
        val secondResult = useCase.finishProcessing(second.id, RestoreResult.RESTORED)

        assertTrue(firstResult.workLogId != secondResult.workLogId)
        val firstLog = database!!.workLogDao().findBySource(FaultUseCase.SOURCE_TYPE, firstProcessing.id)
        val secondLog = database!!.workLogDao().findBySource(FaultUseCase.SOURCE_TYPE, second.id)
        assertEquals("第一次症状", firstLog!!.content)
        assertEquals("第二次症状", secondLog!!.content)
    }

    @Test
    fun continueAfterRestoredFaultIsRejected() = runBlocking {
        val useCase = newStartedUseCase()
        val processing = database!!.faultProcessingDao().findLatest()!!
        useCase.finishProcessing(processing.id, RestoreResult.RESTORED)

        var rejected = false
        try {
            useCase.continueProcessing(processing.id)
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals("CLOSED", database!!.faultRecordDao().findById(processing.faultId)!!.lifecycleStatus)
    }

    @Test
    fun faultDerivedWorkLogKeepsWorkResultNull() = runBlocking {
        val useCase = newStartedUseCase()
        val processing = database!!.faultProcessingDao().findLatest()!!
        val result = useCase.finishProcessing(processing.id, RestoreResult.PARTIAL)

        val log = database!!.workLogDao().findBySource(FaultUseCase.SOURCE_TYPE, processing.id)
        assertEquals(result.workLogId, log!!.id)
        assertNull(log.workResult)
        assertEquals(RestoreResult.PARTIAL.name, log.restoreResult)
    }

    @Test
    fun handoverDueKindUsesFrozenValues() = runBlocking {
        val useCase = newStartedUseCase()
        val processing = database!!.faultProcessingDao().findLatest()!!
        useCase.finishProcessing(
            processing.id,
            RestoreResult.PARTIAL,
            nextAction = "复查",
            dueAt = 42L,
            dueKind = HandoverDueKind.SPECIFIC,
        )
        assertEquals("SPECIFIC", database!!.handoverItemDao().findBySource(
            FaultUseCase.AUTO_ORIGIN,
            FaultUseCase.SOURCE_TYPE,
            processing.id,
        )!!.dueKind)

        val endOfTodayUseCase = newStartedUseCase()
        val endOfTodayProcessing = database!!.faultProcessingDao().findLatest()!!
        endOfTodayUseCase.finishProcessing(endOfTodayProcessing.id, RestoreResult.PARTIAL, dueKind = HandoverDueKind.END_OF_TODAY)
        assertEquals("END_OF_TODAY", database!!.handoverItemDao().findBySource(
            FaultUseCase.AUTO_ORIGIN,
            FaultUseCase.SOURCE_TYPE,
            endOfTodayProcessing.id,
        )!!.dueKind)

        val nextShiftUseCase = newStartedUseCase()
        val nextShiftProcessing = database!!.faultProcessingDao().findLatest()!!
        nextShiftUseCase.finishProcessing(nextShiftProcessing.id, RestoreResult.PARTIAL, dueKind = HandoverDueKind.NEXT_SHIFT)
        assertEquals("NEXT_SHIFT", database!!.handoverItemDao().findBySource(
            FaultUseCase.AUTO_ORIGIN,
            FaultUseCase.SOURCE_TYPE,
            nextShiftProcessing.id,
        )!!.dueKind)
    }

    @Test
    fun nonRestoredResultsCreatePendingHandoverAndRestoredClosesFault() = runBlocking {
        for (result in listOf(
            RestoreResult.TEMPORARY,
            RestoreResult.PARTIAL,
            RestoreResult.NOT_RESTORED,
            RestoreResult.UNKNOWN,
        )) {
            val useCase = newStartedUseCase()
            val processing = database!!.faultProcessingDao().findLatest()!!
            val finish = useCase.finishProcessing(processing.id, result)
            assertNotNull(finish.handoverId)
            assertEquals("PENDING_HANDOVER", database!!.handoverItemDao().findBySource(
                FaultUseCase.AUTO_ORIGIN,
                FaultUseCase.SOURCE_TYPE,
                processing.id,
            )!!.status)
            database!!.close()
            database = null
        }

        val restoredUseCase = newStartedUseCase()
        val restoredProcessing = database!!.faultProcessingDao().findLatest()!!
        val restored = restoredUseCase.finishProcessing(restoredProcessing.id, RestoreResult.RESTORED)
        assertNull(restored.handoverId)
        assertEquals("CLOSED", database!!.faultRecordDao().findById(restoredProcessing.faultId)!!.lifecycleStatus)
    }

    @Test
    fun failureInsideTransactionRollsBackEveryWrite() = runBlocking {
        val useCase = newStartedUseCase(transactionHook = { error("forced failure") })
        val processing = database!!.faultProcessingDao().findLatest()!!
        var failed = false
        try {
            useCase.finishProcessing(processing.id, RestoreResult.PARTIAL)
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)
        assertNull(database!!.workLogDao().findBySource(FaultUseCase.SOURCE_TYPE, processing.id))
        assertNull(database!!.handoverItemDao().findBySource(
            FaultUseCase.AUTO_ORIGIN,
            FaultUseCase.SOURCE_TYPE,
            processing.id,
        ))
        assertEquals("IN_PROGRESS", database!!.faultProcessingDao().findById(processing.id)!!.progressStatus)
    }

    @Test
    fun voidFaultIsRejectedWhileUnfinishedProcessingExists() = runBlocking {
        val useCase = newStartedUseCase()
        val processing = database!!.faultProcessingDao().findLatest()!!
        assertFailsWith<IllegalStateException> { useCase.voidFault(processing.faultId) }
        assertEquals("OPEN", database!!.faultRecordDao().findById(processing.faultId)!!.lifecycleStatus)
    }

    @Test
    fun voidFaultMarksFaultVoidedAfterTerminalProcessing() = runBlocking {
        val useCase = newStartedUseCase()
        val processing = database!!.faultProcessingDao().findLatest()!!
        useCase.finishProcessing(processing.id, RestoreResult.RESTORED)
        useCase.voidFault(processing.faultId)
        val fault = database!!.faultRecordDao().findById(processing.faultId)!!
        assertEquals("VOIDED", fault.lifecycleStatus)
        assertNotNull(fault.voidedAt)
        assertTrue(database!!.faultRecordDao().all().none { it.id == fault.id })
    }

    @Test
    fun voidProcessingOnlyAllowedForTerminalStates() = runBlocking {
        val useCase = newStartedUseCase()
        val processing = database!!.faultProcessingDao().findLatest()!!
        assertFailsWith<IllegalStateException> { useCase.voidProcessing(processing.id) }
        useCase.finishProcessing(processing.id, RestoreResult.PARTIAL)
        useCase.voidProcessing(processing.id)
        assertNotNull(database!!.faultProcessingDao().findById(processing.id)!!.voidedAt)
    }

    private suspend fun newStartedUseCase(
        deviceName: String = "设备甲",
        symptom: String = "信号弱",
        transactionHook: (() -> Unit)? = null,
    ): FaultUseCase {
        database?.close()
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CodaDatabase::class.java,
        ).allowMainThreadQueries().build()
        val clock = Clock.fixed(Instant.ofEpochMilli(2_000L), ZoneId.of("Asia/Shanghai"))
        val processingId = FaultDraftRepository(database!!, clock)
            .createMinimalDraft(deviceName, symptom, 1_000L)
        val useCase = FaultUseCase(
            database = database!!,
            clock = clock,
            zoneId = ZoneId.of("Asia/Shanghai"),
            transactionHook = transactionHook,
        )
        useCase.startProcessing(processingId)
        return useCase
    }
}
