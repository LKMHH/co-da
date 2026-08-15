package com.coda.workbench.platform

import com.coda.workbench.core.rules.ShiftPromptRules
import com.coda.workbench.data.local.CodaDatabase
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * M5b 通知维护：到期闹钟投递、逾期每日去重（同一自然日最多一次）、
 * 每月 1 号排班确认提醒（lastShiftPromptMonth 去重）。
 */
class NotificationMaintenance(
    private val database: CodaDatabase,
    private val clock: Clock,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val settings: NotificationSettingsStore,
    private val poster: NotificationPoster,
) {
    suspend fun onDueAlarm(itemId: String) {
        if (!settings.enabledNow()) return
        val item = database.handoverItemDao().findById(itemId) ?: return
        val dueAt = item.dueAt ?: return
        if (item.voidedAt != null || item.status !in OPEN_STATUSES) return
        if (dueAt <= clock.millis()) {
            poster.postDue(item.id, item.summary, item.nextAction)
        }
    }

    suspend fun onDailyCheck() {
        if (!settings.enabledNow()) return
        checkOverdue()
        checkMonthlyShiftPrompt()
    }

    private suspend fun checkOverdue() {
        val now = clock.millis()
        val today = LocalDate.now(clock.withZone(zoneId)).toString()
        database.handoverItemDao().pendingWithDue().forEach { item ->
            val dueAt = item.dueAt ?: return@forEach
            if (dueAt < now && item.lastOverdueNoticeDate != today) {
                poster.postOverdue(item.id, item.summary, item.nextAction)
                database.handoverItemDao().markOverdueNoticed(item.id, today, now)
            }
        }
    }

    private suspend fun checkMonthlyShiftPrompt() {
        val today = LocalDate.now(clock.withZone(zoneId))
        val month = YearMonth.from(today).toString()
        val plan = database.monthlyShiftPlanDao().findByMonth(month)
        if (ShiftPromptRules.shouldNotify(today, plan?.confirmedAt, settings.lastShiftPromptMonthNow())) {
            poster.postShiftPrompt(month)
            settings.setLastShiftPromptMonth(month)
        }
    }

    companion object {
        private val OPEN_STATUSES = setOf("PENDING_HANDOVER", "HANDED_OVER", "IN_PROGRESS")
    }
}
