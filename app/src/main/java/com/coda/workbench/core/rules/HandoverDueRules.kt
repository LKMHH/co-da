package com.coda.workbench.core.rules

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * 交接事项期限换算（技术稿 §9.1）：
 * - END_OF_TODAY 按创建/修改时的当前出勤 endAt 换算；无当前出勤或时间不明确时回退设备本地当天 18:00；
 *   换算结果写入事项快照，后续修正出勤不改变已保存 dueAt。
 * - NEXT_SHIFT 读取已确认/手动修正的下一条 shift_slot 的开始时间；未确认（无未来班次）时返回 null，
 *   事项仍在首页显示，由 reconcileAll 在排班确认后补算。
 * - NONE 不生成定时时间；SPECIFIC 直接透传用户选择。
 */
object HandoverDueRules {
    fun resolveDueAt(
        dueKindName: String,
        explicitDueAt: Long?,
        attendanceEndAt: Long?,
        upcomingStarts: List<Long>,
        now: Instant,
        zoneId: ZoneId,
    ): Long? = when (dueKindName) {
        "NONE" -> null
        "END_OF_TODAY" -> attendanceEndAt ?: endOfTodayFallback(now, zoneId)
        "NEXT_SHIFT" -> upcomingStarts.minOrNull()
        "SPECIFIC" -> explicitDueAt
        else -> explicitDueAt
    }

    fun endOfTodayFallback(now: Instant, zoneId: ZoneId): Long =
        now.atZone(zoneId).toLocalDate()
            .atTime(LocalTime.of(18, 0))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
}
