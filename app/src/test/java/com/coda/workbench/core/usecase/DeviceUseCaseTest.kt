package com.coda.workbench.core.usecase

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.coda.workbench.data.local.CodaDatabase
import com.coda.workbench.data.local.CodaDatabaseCallback
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
 * 设备与别名的重复校验（UI 稿 §10.1：重复名称或别名显示明确中文错误）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeviceUseCaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock: Clock = Clock.systemUTC()
    private lateinit var database: CodaDatabase
    private lateinit var useCase: DeviceUseCase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, CodaDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(CodaDatabaseCallback())
            .build()
        useCase = DeviceUseCase(database, clock)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun duplicateDeviceNameIsRejectedWithChineseMessage() = runBlocking {
        useCase.create("一号楼配电箱")
        val result = runCatching { useCase.create(" 一号楼配电箱 ") }
        assertTrue(result.isFailure)
        assertEquals("设备名称已存在", result.exceptionOrNull()!!.message)
    }

    @Test
    fun duplicateAliasIsRejectedWithChineseMessage() = runBlocking {
        val id = useCase.create("一号楼配电箱")
        useCase.addAlias(id, "雷达")
        val result = runCatching { useCase.addAlias(id, "雷达") }
        assertTrue(result.isFailure)
        assertEquals("该别名已存在", result.exceptionOrNull()!!.message)
    }

    @Test
    fun renameToAnotherDevicesNameIsRejectedButSameNameOk() = runBlocking {
        val a = useCase.create("配电箱A")
        useCase.create("配电箱B")
        val dup = runCatching { useCase.rename(a, "配电箱B") }
        assertTrue(dup.isFailure)
        assertEquals("设备名称已存在", dup.exceptionOrNull()!!.message)
        useCase.rename(a, "配电箱A") // 自己改回自己不报错
    }
}
