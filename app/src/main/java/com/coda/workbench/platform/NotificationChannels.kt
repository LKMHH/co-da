package com.coda.workbench.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** 通知渠道：至少区分“到期事项”与“排班提示”，允许系统级关闭（技术稿 §9.2）。 */
object NotificationChannels {
    const val CHANNEL_DUE = "due_items"
    const val CHANNEL_SHIFT = "shift_reminders"

    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DUE, "到期事项", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "交接事项到期与逾期提醒"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SHIFT, "排班提示", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "每月排班确认提醒"
            },
        )
    }
}
