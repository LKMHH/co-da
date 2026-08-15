package com.coda.workbench.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class NotificationPermissionState { GRANTED, DENIED }

/** POST_NOTIFICATIONS（Android 13+）权限状态；低版本视为已允许。 */
object NotificationPermission {
    fun current(context: Context): NotificationPermissionState =
        if (Build.VERSION.SDK_INT >= 33) {
            if (
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                NotificationPermissionState.GRANTED
            } else {
                NotificationPermissionState.DENIED
            }
        } else {
            NotificationPermissionState.GRANTED
        }
}
