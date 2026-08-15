package com.coda.workbench.core.usecase

import com.coda.workbench.core.rules.HandoverDueRules
import com.coda.workbench.data.local.CodaDatabase
import com.coda.workbench.data.local.HandoverItemEntity
import java.time.Clock
import java.time.ZoneId
import java.util.UUID

data class CreateHandoverInput(
    val summary: String?,
    val nextAction: String,
    val dueKind: HandoverDueKind,
    val dueAt: Long?,
    val handoverGroup: String?,
    val potentialHazardNote: String?,
)

data class UpdateHandoverInput(
    val summary: String?,
    val nextAction: String,
    val dueKind: HandoverDueKind,
    val dueAt: Long?,
    val handoverGroup: String?,
    val potentialHazardNote: String?,
)

class HandoverUseCase(
    private val database: CodaDatabase,
    private val clock: Clock,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val notificationScheduler: HandoverNotificationScheduler = NoOpHandoverNotificationScheduler,
) {
    suspend fun load(id: String): HandoverItemEntity? = database.handoverItemDao().findById(id)

    suspend fun create(input: CreateHandoverInput): String {
        require(input.nextAction.isNotBlank()) { "下一步动作必填" }
        require(input.dueKind != HandoverDueKind.SPECIFIC || input.dueAt != null) { "指定时间必填" }
        val now = clock.millis()
        val summary = input.summary?.trim().takeUnless { it.isNullOrEmpty() } ?: "待跟进事项"
        val item = HandoverItemEntity(
            id = UUID.randomUUID().toString(),
            summary = summary,
            status = "PENDING_HANDOVER",
            nextAction = input.nextAction.trim(),
            dueKind = input.dueKind.name,
            dueAt = resolveDueAt(input.dueKind, input.dueAt),
            originType = "MANUAL",
            sourceType = null,
            sourceId = null,
            handoverGroup = input.handoverGroup,
            potentialHazardNote = input.potentialHazardNote?.trim(),
            lastOverdueNoticeDate = null,
            createdAt = now,
            updatedAt = now,
            completedAt = null,
            voidedAt = null,
        )
        database.handoverItemDao().insert(item)
        runCatching { notificationScheduler.reconcile(item.id) }
        return item.id
    }

    suspend fun update(id: String, input: UpdateHandoverInput) {
        val item = requireItem(id)
        check(item.status in setOf("PENDING_HANDOVER", "HANDED_OVER", "IN_PROGRESS")) { "当前状态不可编辑" }
        require(input.nextAction.isNotBlank()) { "下一步动作必填" }
        require(input.dueKind != HandoverDueKind.SPECIFIC || input.dueAt != null) { "指定时间必填" }
        val summary = input.summary?.trim().takeUnless { it.isNullOrEmpty() } ?: item.summary
        database.handoverItemDao().updateFields(
            id = id,
            summary = summary,
            nextAction = input.nextAction.trim(),
            dueKind = input.dueKind.name,
            dueAt = resolveDueAt(input.dueKind, input.dueAt),
            handoverGroup = input.handoverGroup,
            potentialHazardNote = input.potentialHazardNote?.trim(),
            updatedAt = clock.millis(),
        )
        runCatching { notificationScheduler.reconcile(id) }
    }

    /** 事项来源为故障处理且故障仍 OPEN 时，返回可继续处理的故障 id。 */
    suspend fun faultLink(item: HandoverItemEntity): String? {
        if (item.sourceType != "FAULT_PROCESSING") return null
        val processing = item.sourceId?.let { database.faultProcessingDao().findById(it) } ?: return null
        val fault = database.faultRecordDao().findById(processing.faultId) ?: return null
        return fault.id.takeIf { fault.lifecycleStatus == "OPEN" }
    }

    suspend fun markHandedOver(id: String) = transition(id, from = "PENDING_HANDOVER", to = "HANDED_OVER")

    suspend fun markInProgress(id: String) = transition(id, from = "HANDED_OVER", to = "IN_PROGRESS")

    suspend fun complete(id: String) = transition(id, from = "IN_PROGRESS", to = "COMPLETED", completed = true)

    suspend fun cancel(id: String) {
        val item = requireItem(id)
        check(item.status in setOf("PENDING_HANDOVER", "HANDED_OVER", "IN_PROGRESS")) { "当前状态不可取消" }
        update(id, "CANCELED", completed = true)
    }

    suspend fun void(id: String) {
        val now = clock.millis()
        check(database.handoverItemDao().markVoided(id, now, now) == 1) { "事项不存在或已作废" }
        runCatching { notificationScheduler.reconcile(id) }
    }

    private suspend fun transition(id: String, from: String, to: String, completed: Boolean = false) {
        val item = requireItem(id)
        check(item.status == from) { "当前状态不允许该操作" }
        update(id, to, completed)
    }

    private suspend fun update(id: String, status: String, completed: Boolean) {
        val now = clock.millis()
        database.handoverItemDao().updateStatus(id, status, if (completed) now else null, now)
        runCatching { notificationScheduler.reconcile(id) }
    }

    /**
     * M5 期限换算（技术稿 §9.1）：END_OF_TODAY 取当前出勤 endAt（缺省回退当天 18:00）并写入快照；
     * NEXT_SHIFT 取已确认排班的下一条班次开始时间，未确认时为 null 但仍保留 dueKind，等待排班确认后补算。
     */
    private suspend fun resolveDueAt(dueKind: HandoverDueKind, explicitDueAt: Long?): Long? {
        val nowMillis = clock.millis()
        val monthPrefix = java.time.YearMonth.now(clock.withZone(zoneId)).toString() + "-"
        return HandoverDueRules.resolveDueAt(
            dueKindName = dueKind.name,
            explicitDueAt = explicitDueAt,
            attendanceEndAt = database.attendanceDao().findCurrent()?.endAt,
            upcomingStarts = database.shiftSlotDao().upcomingInMonth(monthPrefix, nowMillis).map { it.startAt },
            now = clock.instant(),
            zoneId = zoneId,
        )
    }

    private suspend fun requireItem(id: String): HandoverItemEntity =
        database.handoverItemDao().findById(id) ?: error("交接事项不存在")
}
