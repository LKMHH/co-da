package com.coda.workbench.platform

import android.app.PendingIntent

/** 稳定的 PendingIntent request code 与通知 id：由事项 UUID 派生，创建/更新/取消时幂等。 */
object PendingIntentKeys {
    fun requestCodeFor(id: String): Int {
        val code = id.hashCode() and Int.MAX_VALUE
        return if (code == 0) 1 else code
    }

    fun notificationIdFor(id: String): Int = requestCodeFor(id)
}
