package com.coda.workbench.platform

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.coda.workbench.MainActivity
import com.coda.workbench.R

/** 通知投递抽象：生产实现走 NotificationManagerCompat；权限被拒时静默降级，不影响业务写入。 */
interface NotificationPoster {
    fun postDue(itemId: String, summary: String, nextAction: String)

    fun postOverdue(itemId: String, summary: String, nextAction: String)

    fun postShiftPrompt(month: String)
}

class AndroidNotificationPoster(private val context: Context) : NotificationPoster {
    private val manager = NotificationManagerCompat.from(context)

    override fun postDue(itemId: String, summary: String, nextAction: String) =
        notify(
            id = PendingIntentKeys.notificationIdFor(itemId),
            channel = NotificationChannels.CHANNEL_DUE,
            title = "事项到期提醒",
            text = summary.ifBlank { nextAction }.ifBlank { "待跟进事项已到期" },
        )

    override fun postOverdue(itemId: String, summary: String, nextAction: String) =
        notify(
            id = PendingIntentKeys.notificationIdFor(itemId),
            channel = NotificationChannels.CHANNEL_DUE,
            title = "逾期提醒",
            text = summary.ifBlank { nextAction }.ifBlank { "待跟进事项已逾期" },
        )

    override fun postShiftPrompt(month: String) =
        notify(
            id = SHIFT_PROMPT_NOTIFICATION_ID,
            channel = NotificationChannels.CHANNEL_SHIFT,
            title = "本月排班尚未确认",
            text = "请确认本月 08:00-20:00 由甲班还是乙班上白班",
        )

    private fun notify(id: Int, channel: String, title: String, text: String) {
        val contentIntent = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent)
            .build()
        runCatching { manager.notify(id, notification) }
    }

    companion object {
        const val SHIFT_PROMPT_NOTIFICATION_ID = 8_640_001
    }
}
