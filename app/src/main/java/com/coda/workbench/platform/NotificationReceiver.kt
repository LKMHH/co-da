package com.coda.workbench.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * M5b 接收器：到期闹钟投递、每日维护（逾期去重 + 每月 1 号排班提示）、
 * BOOT_COMPLETED / TIME_SET / TIMEZONE_CHANGED 后从数据库重建全部未来提醒。
 */
@AndroidEntryPoint
class NotificationReceiver : BroadcastReceiver() {
    @Inject
    lateinit var maintenance: NotificationMaintenance

    @Inject
    lateinit var scheduler: NotificationScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        // 覆盖"重装/清数据后未启动即收到广播"的极端场景：渠道缺失会导致通知静默丢失
        NotificationChannels.ensure(context)
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 闹钟/系统广播路径绝不因业务异常崩溃进程；逐段隔离，保证维护闹钟仍会续期
                when (action) {
                    ACTION_DUE -> runCatching {
                        intent.getStringExtra(EXTRA_ITEM_ID)?.let { maintenance.onDueAlarm(it) }
                    }
                    ACTION_DAILY -> {
                        runCatching { maintenance.onDailyCheck() }
                        runCatching { scheduler.ensureDaily() }
                    }
                    Intent.ACTION_BOOT_COMPLETED,
                    ACTION_TIME_SET,
                    Intent.ACTION_TIMEZONE_CHANGED,
                    -> runCatching { scheduler.reconcileAll() }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_DUE = "com.coda.workbench.action.HANDOVER_DUE"
        const val ACTION_DAILY = "com.coda.workbench.action.DAILY_MAINTENANCE"
        const val EXTRA_ITEM_ID = "com.coda.workbench.extra.ITEM_ID"

        /** Intent.ACTION_TIME_SET 是 @hide 常量，公开 SDK 不可引用，使用其字面量值。 */
        const val ACTION_TIME_SET = "android.intent.action.TIME_SET"
    }
}
