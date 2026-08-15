package com.coda.workbench.core.usecase

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.coda.workbench.data.local.CodaDatabase
import com.coda.workbench.data.local.CodaDatabaseCallback
import com.coda.workbench.data.repository.FaultDraftRepository
import java.time.Clock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T9：编辑工作记录可修改「工作日期」——
 * 合法 yyyy-MM-dd 生效；非法/空白被拒且不改数据；null 表示不修改（新建流程沿用自动日期）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ManualWorkWorkDateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock: Clock = Clock.systemUTC()
    private lateinit var database: CodaDatabase
    private lateinit var useCase: ManualWorkUseCase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, CodaDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(CodaDatabaseCallback())
            .build()
        useCase = ManualWorkUseCase(FaultDraftRepository(database, clock), database, clock)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `valid date updates workDate and keeps content`() = runBlocking {
        val id = useCase.save("巡检一号楼")
        useCase.update(id, "巡检一号楼", null, null, null, null, "2026-08-01")

        val log = database.workLogDao().findById(id)!!
        assertEquals("2026-08-01", log.workDate)
        assertEquals("巡检一号楼", log.content)
    }

    @Test
    fun `invalid date format is rejected and nothing changes`() = runBlocking {
        val id = useCase.save("巡检一号楼")
        val before = database.workLogDao().findById(id)!!.workDate

        val result = runCatching { useCase.update(id, "巡检一号楼", null, null, null, null, "2026/08/01") }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("yyyy-MM-dd"))
        assertEquals(before, database.workLogDao().findById(id)!!.workDate)
    }

    @Test
    fun `blank date is rejected`() = runBlocking {
        val id = useCase.save("巡检一号楼")
        val result = runCatching { useCase.update(id, "巡检一号楼", null, null, null, null, "  ") }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("工作日期不能为空"))
    }

    @Test
    fun `null date keeps existing workDate`() = runBlocking {
        val id = useCase.save("巡检一号楼")
        val before = database.workLogDao().findById(id)!!.workDate

        useCase.update(id, "巡检一号楼", null, null, null, null, null)

        assertEquals(before, database.workLogDao().findById(id)!!.workDate)
    }
}
