package com.coda.workbench.platform

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.coda.workbench.core.usecase.FaultNotificationScheduler
import com.coda.workbench.core.usecase.HandoverNotificationScheduler
import com.coda.workbench.core.usecase.ShiftScheduleNotificationTrigger
import com.coda.workbench.data.local.CodaDatabase
import java.time.Clock
import java.time.LocalTime
import java.time.ZoneId

/**
 * M5b 通知调度器（技术稿 §9.1）：
 * - 非精确 AlarmManager（不申请 SCHEDULE_EXACT_ALARM），到期只提醒一次；
 * - 稳定的 PendingIntent request code（UUID 派生），创建/更新/完成/作废都 cancel/reschedule，幂等；
 * - 数据库是事实来源：App 启动、设备重启、排班变化后 reconcileAll 重建未来提醒；
 * - NEXT_SHIFT 未确认时无 dueAt，reconcileAll 在排班确认后补算并调度；
 * - 每天 09:00 的一次性维护闹钟在接收器里自续期（进程被杀不丢提醒）。
 */
class NotificationScheduler(
    private val context: Context,
    private val database: CodaDatabase,
    private val clock: Clock,
    private val settings: NotificationSettingsStore,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val gateway: AlarmGateway = AndroidAlarmGateway(context),
) : FaultNotificationScheduler, HandoverNotificationScheduler, ShiftScheduleNotificationTrigger {

    override suspend fun scheduleForFault(faultId: String, handoverId: String) =
        reconcile(handoverId)

    /** 单事项重调度：到期未到的活动事项排闹钟，其余一律取消。 */
    override suspend fun reconcile(handoverId: String) {
        val item = database.handoverItemDao().findById(handoverId) ?: return
        gateway.cancel(duePendingIntent(item.id))
        if (!settings.enabledNow()) return
        val now = clock.millis()
        val schedulable = item.voidedAt == null &&
            item.status in OPEN_STATUSES &&
            item.dueAt != null &&
            item.dueAt!! > now
        if (schedulable) gateway.schedule(item.dueAt!!, duePendingIntent(item.id))
    }

    /** 以数据库为事实来源重建全部未来提醒 + 补算 NEXT_SHIFT + 确保每日维护闹钟。 */
    override suspend fun reconcileAll() {
        if (!settings.enabledNow()) {
            cancelAll()
            return
        }
        recomputeNextShiftDue()
        val now = clock.millis()
        database.handoverItemDao().pendingWithDue().forEach { item ->
            gateway.cancel(duePendingIntent(item.id))
            val dueAt = item.dueAt
            if (dueAt != null && dueAt > now) {
                gateway.schedule(dueAt, duePendingIntent(item.id))
            }
        }
        ensureDaily()
    }

    /** 关闭总开关：取消系统通知调度但不删除业务期限；重新打开时由 reconcileAll 重建。 */
    suspend fun cancelAll() {
        database.handoverItemDao().pendingWithDue().forEach { gateway.cancel(duePendingIntent(it.id)) }
        database.handoverItemDao().pendingNextShift().forEach { gateway.cancel(duePendingIntent(it.id)) }
        gateway.cancel(dailyPendingIntent())
    }

    /** 每日维护闹钟（自续期，接收器处理后再排下一次）。 */
    suspend fun ensureDaily() {
        gateway.cancel(dailyPendingIntent())
        gateway.schedule(nextDailyTimeMillis(), dailyPendingIntent())
    }

    /**
     * NEXT_SHIFT 事项在排班变化、App 启动、设备重启后重新计算 dueAt（技术稿 §9.1）：
     * 统一重算到已确认/手动修正的下一条班次开始时间（含此前已算出旧值的事项）。
     */
    private suspend fun recomputeNextShiftDue() {
        val now = clock.millis()
        val monthPrefix = java.time.YearMonth.now(clock.withZone(zoneId)).toString() + "-"
        val nextStart = database.shiftSlotDao().upcomingInMonth(monthPrefix, now).firstOrNull()?.startAt ?: return
        database.handoverItemDao().pendingNextShift().forEach { item ->
            if (item.dueAt != nextStart) {
                database.handoverItemDao().updateDueAt(item.id, nextStart, now)
            }
        }
    }

    private fun nextDailyTimeMillis(): Long {
        val zoned = clock.instant().atZone(zoneId)
        var at = zoned.toLocalDate().atTime(LocalTime.of(9, 0)).atZone(zoneId).toInstant().toEpochMilli()
        if (at <= clock.millis()) {
            at = zoned.toLocalDate().plusDays(1).atTime(LocalTime.of(9, 0)).atZone(zoneId).toInstant().toEpochMilli()
        }
        return at
    }

    private fun duePendingIntent(itemId: String): PendingIntent {
        val intent = Intent(context, NotificationReceiver::class.java)
            .setAction(NotificationReceiver.ACTION_DUE)
            .putExtra(NotificationReceiver.EXTRA_ITEM_ID, itemId)
        return PendingIntent.getBroadcast(
            context,
            PendingIntentKeys.requestCodeFor(itemId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun dailyPendingIntent(): PendingIntent {
        val intent = Intent(context, NotificationReceiver::class.java)
            .setAction(NotificationReceiver.ACTION_DAILY)
        return PendingIntent.getBroadcast(
            context,
            DAILY_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private val OPEN_STATUSES = setOf("PENDING_HANDOVER", "HANDED_OVER", "IN_PROGRESS")
        private const val DAILY_REQUEST_CODE = 7_000_001
    }
}
