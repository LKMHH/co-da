package com.coda.workbench.platform

import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.flow.Flow

data class NotificationSettingsUiState(
    val enabled: Boolean = true,
    val permissionState: NotificationPermissionState = NotificationPermissionState.GRANTED,
)

/**
 * 通知设置（技术稿 §5 NotificationSettingsUseCase 契约的等价异常式实现）：
 * 关闭总开关取消系统调度但不删除业务期限；重新打开按数据库重建未来提醒；
 * 系统权限被拒绝时返回 PermissionDenied 语义（DENIED），首页应用内提醒不受影响。
 */
class NotificationSettingsUseCase(
    private val context: Context,
    private val settings: NotificationSettingsStore,
    private val scheduler: NotificationScheduler,
) {
    fun observeEnabled(): Flow<Boolean> = settings.enabled

    suspend fun setEnabled(enabled: Boolean) {
        settings.setEnabled(enabled)
        // 调度失败不影响开关状态与业务数据；下次启动 reconcileAll 会重建
        runCatching { if (enabled) scheduler.reconcileAll() else scheduler.cancelAll() }
    }

    fun permissionState(): NotificationPermissionState = NotificationPermission.current(context)

    fun openSystemSettingsIntent(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
}
