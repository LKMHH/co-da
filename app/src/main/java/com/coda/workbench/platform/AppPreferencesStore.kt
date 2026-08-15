package com.coda.workbench.platform

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

/**
 * 应用级本机 UI 偏好（非业务数据，备份恢复不覆盖）。
 */
class AppPreferencesStore(private val dataStore: DataStore<Preferences>) {
    /** 首次使用引导是否已展示过。 */
    suspend fun onboardingShownNow(): Boolean = dataStore.data.first()[KEY_ONBOARDING_SHOWN] ?: false

    suspend fun markOnboardingShown() {
        dataStore.edit { it[KEY_ONBOARDING_SHOWN] = true }
    }

    companion object {
        private val KEY_ONBOARDING_SHOWN = booleanPreferencesKey("onboardingShown")
    }
}
