package com.coda.workbench.core.usecase

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.coda.workbench.data.backup.BackupCounts
import com.coda.workbench.data.backup.BackupData
import com.coda.workbench.data.backup.BackupDevice
import com.coda.workbench.data.backup.BackupValidationException
import com.coda.workbench.data.local.CodaDatabase
import com.coda.workbench.data.local.CodaDatabaseCallback
import com.coda.workbench.data.repository.BackupRepository
import com.coda.workbench.platform.BackupDestination
import com.coda.workbench.platform.BackupFileStore
import com.coda.workbench.platform.BackupSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
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
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupUseCaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-08-14T04:00:00Z"), zoneId)
    private var database: CodaDatabase? = null
    private var repository: BackupRepository? = null
    private var fileStore: BackupFileStore? = null
    private var useCase: BackupUseCase? = null

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, CodaDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(CodaDatabaseCallback())
            .build()
        repository = BackupRepository(database!!)
        fileStore = BackupFileStore(context)
        useCase = BackupUseCase(repository!!, fileStore!!, clock, "0.1.0")
    }

    @After
    fun tearDown() {
        database?.close()
        fileStore?.clearTemp()
    }

    @Test
    fun exportInspectReplaceRoundTripPreservesAllData() = runBlocking {
        repository!!.apply(dataA())
        val destination = ByteArrayDestination()

        val exported = useCase!!.export(destination)
        assertEquals(BackupCounts().of(dataA()), exported.counts)

        // 改动本机数据
        repository!!.apply(dataA().copy(devices = dataA().devices + BackupDevice("d2", "3号线", "3号线", true, 1L, 1L)))
        assertTrue(repository!!.snapshot().devices.size == 2)

        val preview = useCase!!.inspect(ByteArraySource(destination.written))
        assertEquals(BackupCounts().of(dataA()), preview.counts)
        assertTrue(preview.safetyFile != null) // 安全备份在确认前完成

        val result = useCase!!.replace(ByteArraySource(destination.written), preview.safetyFile!!)
        assertEquals(BackupCounts().of(dataA()), result.counts)

        assertEquals(dataA(), repository!!.snapshot())
        // 草稿与作废记录保留
        val snapshot = repository!!.snapshot()
        assertTrue(snapshot.faults.any { it.voidedAt != null })
        assertTrue(snapshot.processings.any { it.progressStatus == "DRAFT" })
        // journal 已清理
        assertFalse(fileStore!!.readJournal() != null)
    }

    @Test
    fun replaceTwiceIsIdempotent() = runBlocking {
        repository!!.apply(dataA())
        val destination = ByteArrayDestination()
        useCase!!.export(destination)
        val safetyFile = useCase!!.inspect(ByteArraySource(destination.written)).safetyFile!!
        repository!!.apply(dataA().copy(devices = dataA().devices + BackupDevice("d2", "3号线", "3号线", true, 1L, 1L)))

        useCase!!.replace(ByteArraySource(destination.written), safetyFile)
        useCase!!.replace(ByteArraySource(destination.written), safetyFile)

        assertEquals(dataA(), repository!!.snapshot())
    }

    @Test
    fun corruptedZipRejectedWithoutTouchingData() = runBlocking {
        repository!!.apply(dataA())
        val destination = ByteArrayDestination()
        useCase!!.export(destination)
        val safetyFile = useCase!!.inspect(ByteArraySource(destination.written)).safetyFile!!

        assertFailsWith<BackupValidationException> {
            useCase!!.replace(ByteArraySource(byteArrayOf(1, 2, 3, 4)), safetyFile)
        }

        assertEquals(dataA(), repository!!.snapshot())
    }

    @Test
    fun preparedJournalRollsBackOnNextStart() = runBlocking {
        repository!!.apply(dataA())

        // 模拟 PREPARED：安全备份 = 替换前数据
        val destination = ByteArrayDestination()
        useCase!!.export(destination)
        val safetyFile = fileStore!!.writeSafetyBackup(destination.written)
        fileStore!!.writeJournal(BackupUseCase.PREPARED, safetyFile)

        // 事务已提交一半的"半恢复状态"
        repository!!.apply(dataA().copy(devices = emptyList(), faults = emptyList(), processings = emptyList()))

        val state = useCase!!.recoverInterruptedRestore()

        assertTrue(state.interrupted)
        assertTrue(state.rolledBack)
        assertEquals(dataA(), repository!!.snapshot())
        assertFalse(fileStore!!.readJournal() != null)
    }

    @Test
    fun missingSafetyFileKeepsJournalAndReportsNotRolledBack() = runBlocking {
        repository!!.apply(dataA())
        fileStore!!.writeJournal(BackupUseCase.PREPARED, java.io.File("safety", "ghost.coda-backup"))
        // 合法的"半恢复状态"：设备/故障/处理一起清空（不能留孤儿引用）
        val halfState = dataA().copy(
            devices = emptyList(),
            faults = emptyList(),
            processings = emptyList(),
        )
        repository!!.apply(halfState)

        val state = useCase!!.recoverInterruptedRestore()

        assertTrue(state.interrupted)
        assertFalse(state.rolledBack)
        assertTrue(fileStore!!.readJournal() != null) // journal 保留，下次启动重试
    }

    @Test
    fun committedJournalIsSimplyCleared() = runBlocking {
        repository!!.apply(dataA())
        val destination = ByteArrayDestination()
        useCase!!.export(destination)
        val safetyFile = fileStore!!.writeSafetyBackup(destination.written)
        fileStore!!.writeJournal(BackupUseCase.COMMITTED, safetyFile)

        val state = useCase!!.recoverInterruptedRestore()

        assertFalse(state.interrupted)
        assertFalse(fileStore!!.readJournal() != null)
    }

    @Test
    fun safetyBackupsKeepAtMostThree() = runBlocking {
        repository!!.apply(dataA())
        repeat(5) {
            fileStore!!.writeSafetyBackup(byteArrayOf(1, 2, 3))
        }
        val files = java.io.File(context.filesDir, "backups/safety").listFiles() ?: emptyArray()
        assertTrue(files.size <= 3)
    }

    // ---- 数据 ----

    private fun dataA(): BackupData = BackupData(
        devices = listOf(BackupDevice("d1", "1号雷达", "1号雷达", true, 1L, 1L)),
        deviceAliases = listOf(),
        faults = listOf(
            com.coda.workbench.data.backup.BackupFault("f1", "d1", "1号雷达", 1_000L, "信号弱", "OPEN", "p1", 1L, 1L, null),
            com.coda.workbench.data.backup.BackupFault("f2", "d1", "1号雷达", 2_000L, "已作废", "VOIDED", null, 1L, 1L, 3L),
        ),
        processings = listOf(
            com.coda.workbench.data.backup.BackupProcessing("p1", "f1", "DRAFT", null, null, null, null, null, null, null, null, 1L, 1L, null, null),
        ),
        workLogs = listOf(),
        handoverItems = listOf(),
        attendance = listOf(),
        shiftPlans = listOf(),
        shiftSlots = listOf(),
    )

    private class ByteArraySource(private val bytes: ByteArray) : BackupSource {
        override fun openInputStream(): InputStream = ByteArrayInputStream(bytes)
    }

    private class ByteArrayDestination : BackupDestination {
        var written: ByteArray = byteArrayOf()

        override fun openOutputStream(): OutputStream = object : ByteArrayOutputStream() {
            override fun close() {
                written = toByteArray()
                super.close()
            }
        }
    }
}
