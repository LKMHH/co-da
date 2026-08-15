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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppPreferencesStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scope: CoroutineScope? = null
    private var store: AppPreferencesStore? = null

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        store = AppPreferencesStore(
            PreferenceDataStoreFactory.create(
                scope = scope!!,
                produceFile = { File(context.filesDir, "prefs-test-${UUID.randomUUID()}.preferences_pb") },
            ),
        )
    }

    @After
    fun tearDown() {
        scope?.cancel()
    }

    @Test
    fun onboardingShownDefaultsFalseAndPersists() = runBlocking {
        assertEquals(false, store!!.onboardingShownNow())
        store!!.markOnboardingShown()
        assertEquals(true, store!!.onboardingShownNow())
    }
}
