package com.coda.workbench.core.usecase

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.coda.workbench.data.local.CodaDatabase
import com.coda.workbench.data.local.CodaDatabaseCallback
import com.coda.workbench.data.local.DeviceEntity
import com.coda.workbench.data.repository.FaultDraftRepository
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T8：普通工作记录的「设备」字段——
 * 名称命中已有设备时记录 deviceId + 规范快照；
 * 自由文本只存快照、不自动创建设备；清空时清除两个字段。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ManualWorkDeviceTest {

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

    private suspend fun insertDevice(name: String): DeviceEntity {
        val now = clock.millis()
        val device = DeviceEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            normalizedName = name,
            isActive = true,
            createdAt = now,
            updatedAt = now,
        )
        database.deviceDao().insert(device)
        return device
    }

    @Test
    fun `device name matching existing device stores id and canonical snapshot`() = runBlocking {
        val device = insertDevice("一号楼配电箱")
        val id = useCase.save("巡检一号楼")
        useCase.update(id, "巡检一号楼", null, null, null, "一号楼配电箱", null)

        val log = database.workLogDao().findById(id)!!
        assertEquals(device.id, log.deviceId)
        assertEquals("一号楼配电箱", log.deviceNameSnapshot)
    }

    @Test
    fun `free text device name stores snapshot only without creating device`() = runBlocking {
        val id = useCase.save("巡检临时点")
        useCase.update(id, "巡检临时点", null, null, null, "临时点位A", null)

        val log = database.workLogDao().findById(id)!!
        assertNull(log.deviceId)
        assertEquals("临时点位A", log.deviceNameSnapshot)
        assertNull(database.deviceDao().findByNormalizedName("临时点位A"))
    }

    @Test
    fun `blank device name clears previous values`() = runBlocking {
        insertDevice("一号楼配电箱")
        val id = useCase.save("巡检一号楼")
        useCase.update(id, "巡检一号楼", null, null, null, "一号楼配电箱", null)
        useCase.update(id, "巡检一号楼", null, null, null, "  ", null)

        val log = database.workLogDao().findById(id)!!
        assertNull(log.deviceId)
        assertNull(log.deviceNameSnapshot)
    }
}
