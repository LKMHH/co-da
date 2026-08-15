package com.coda.workbench.platform

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
class NotificationSettingsStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scope: CoroutineScope? = null
    private var store: NotificationSettingsStore? = null

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        store = NotificationSettingsStore(
            PreferenceDataStoreFactory.create(
                scope = scope!!,
                produceFile = { File(context.filesDir, "settings-test-${UUID.randomUUID()}.preferences_pb") },
            ),
        )
    }

    @After
    fun tearDown() {
        scope?.cancel()
    }

    @Test
    fun notificationsEnabledByDefault() = runBlocking {
        assertTrue(store!!.enabledNow())
        assertTrue(store!!.enabled.first())
    }

    @Test
    fun permissionPromptedDefaultsFalseAndPersists() = runBlocking {
        assertEquals(false, store!!.permissionPromptedNow())
        store!!.markPermissionPrompted()
        assertEquals(true, store!!.permissionPromptedNow())
    }

    @Test
    fun setEnabledPersistsAcrossReads() = runBlocking {
        store!!.setEnabled(false)
        assertEquals(false, store!!.enabledNow())
        assertEquals(false, store!!.enabled.first())

        store!!.setEnabled(true)
        assertEquals(true, store!!.enabledNow())
    }

    @Test
    fun lastShiftPromptMonthRoundTrips() = runBlocking {
        assertNull(store!!.lastShiftPromptMonthNow())

        store!!.setLastShiftPromptMonth("2026-09")

        assertEquals("2026-09", store!!.lastShiftPromptMonthNow())
        assertEquals("2026-09", store!!.lastShiftPromptMonth.first())
    }
}
