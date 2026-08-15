package com.coda.workbench.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context

/** 闹钟网关抽象：生产实现走非精确 AlarmManager，测试用假实现记录调度，不申请精确闹钟权限。 */
interface AlarmGateway {
    fun schedule(wakeAtMillis: Long, operation: PendingIntent)

    fun cancel(operation: PendingIntent)
}

class AndroidAlarmGateway(context: Context) : AlarmGateway {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun schedule(wakeAtMillis: Long, operation: PendingIntent) {
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeAtMillis, operation)
    }

    override fun cancel(operation: PendingIntent) {
        alarmManager.cancel(operation)
    }
}
