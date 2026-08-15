package com.coda.workbench.platform

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 通知偏好（技术稿 §3.2 app_settings）：只保存通知开关等本机偏好，
 * 备份恢复（M7）不覆盖；业务期限仍保存在 Room 中。
 */
class NotificationSettingsStore(private val dataStore: DataStore<Preferences>) {
    val enabled: Flow<Boolean> = dataStore.data.map { it[KEY_ENABLED] ?: true }

    suspend fun enabledNow(): Boolean = dataStore.data.first()[KEY_ENABLED] ?: true

    suspend fun setEnabled(value: Boolean) {
        dataStore.edit { it[KEY_ENABLED] = value }
    }

    val lastShiftPromptMonth: Flow<String?> = dataStore.data.map { it[KEY_LAST_PROMPT] }

    suspend fun lastShiftPromptMonthNow(): String? = dataStore.data.first()[KEY_LAST_PROMPT]

    suspend fun setLastShiftPromptMonth(month: String) {
        dataStore.edit { it[KEY_LAST_PROMPT] = month }
    }

    /** 通知权限是否已引导过（首启只弹一次系统授权，被拒后走设置页重新申请）。 */
    suspend fun permissionPromptedNow(): Boolean = dataStore.data.first()[KEY_PERMISSION_PROMPTED] ?: false

    suspend fun markPermissionPrompted() {
        dataStore.edit { it[KEY_PERMISSION_PROMPTED] = true }
    }

    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("notificationEnabled")
        private val KEY_LAST_PROMPT = stringPreferencesKey("lastShiftPromptMonth")
        private val KEY_PERMISSION_PROMPTED = booleanPreferencesKey("notificationPermissionPrompted")
    }
}
