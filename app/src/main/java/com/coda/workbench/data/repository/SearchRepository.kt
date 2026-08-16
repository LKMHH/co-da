package com.coda.workbench.data.repository

import com.coda.workbench.core.model.SearchFilters
import com.coda.workbench.core.model.SearchRecordType
import com.coda.workbench.core.model.SearchResult
import com.coda.workbench.core.rules.TextRules
import com.coda.workbench.data.local.CodaDatabase
import com.coda.workbench.data.local.FaultProcessingEntity
import com.coda.workbench.data.local.FaultRecordEntity
import com.coda.workbench.data.local.HandoverItemEntity
import com.coda.workbench.data.local.WorkLogEntity
import java.time.LocalDate
import java.time.ZoneId

/**
 * M6 搜索（技术稿 §8 / 产品稿 §9）：
 * - 原文规范化包含匹配（NFKC + LIKE ESCAPE 转义通配符），再追加内置同义词；
 * - 设备名称/别名命中时，通过 deviceId 关联故障与工作记录；
 * - 结果按 updatedAt 倒序；默认隐藏作废，includeVoided 时显示并保留"已作废"标注。
 */
class SearchRepository(
    private val database: CodaDatabase,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun search(query: String, filters: SearchFilters): List<SearchResult> {
        val normalized = TextRules.normalize(query)
        if (normalized.isEmpty()) return emptyList()

        val likeTerms = TextRules.expandedTerms(normalized).map { TextRules.containsLikePattern(it) }
        val aliasDeviceIds = collectAliasIds(likeTerms)
        val dateFromMillis = filters.dateFrom?.atStartOfDay(zoneId)?.toInstant()?.toEpochMilli()
        val dateToMillis = filters.dateTo?.plusDays(1)?.atStartOfDay(zoneId)?.toInstant()?.toEpochMilli()
        val includeVoided = filters.includeVoided
        val wantFault = filters.recordTypes.isEmpty() || SearchRecordType.FAULT in filters.recordTypes
        val wantWork = filters.recordTypes.isEmpty() || SearchRecordType.WORK_LOG in filters.recordTypes
        val wantHandover = filters.recordTypes.isEmpty() || SearchRecordType.HANDOVER in filters.recordTypes

        val results = mutableListOf<SearchResult>()
        if (wantFault) {
            results += searchFaults(likeTerms, aliasDeviceIds, includeVoided, dateFromMillis, dateToMillis, filters, normalized)
        }
        if (wantWork) {
            results += searchWorkLogs(likeTerms, aliasDeviceIds, includeVoided, dateFromMillis, dateToMillis, filters, normalized)
        }
        if (wantHandover) {
            results += searchHandovers(likeTerms, includeVoided, dateFromMillis, dateToMillis, normalized)
        }
        return results.sortedByDescending { it.sortTime }
    }

    /** 空查询时的「最近更新」（UI 稿 §9）：三类记录按 updatedAt 合并取最近 N 条。 */
    suspend fun recent(limit: Int = 10): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        database.workLogDao().recent(limit).filter { it.voidedAt == null }.forEach { log ->
            results += SearchResult(
                type = SearchRecordType.WORK_LOG,
                id = log.id,
                title = log.deviceNameSnapshot ?: "普通工作",
                snippet = log.content,
                statusText = if (log.voidedAt == null) "已记录" else "已作废",
                extraTag = if (log.kind == "FAULT_DERIVED") "故障派生" else null,
                sortTime = log.updatedAt,
                expandedMatch = false,
            )
        }
        database.faultRecordDao().recent(limit).forEach { fault ->
            val latest = fault.lastProcessingId?.let { database.faultProcessingDao().findById(it) }
            results += SearchResult(
                type = SearchRecordType.FAULT,
                id = fault.id,
                title = fault.deviceNameSnapshot,
                snippet = fault.symptom,
                statusText = faultStatusText(fault, latest),
                sortTime = fault.updatedAt,
                expandedMatch = false,
            )
        }
        database.handoverItemDao().recent(limit).forEach { item ->
            results += SearchResult(
                type = SearchRecordType.HANDOVER,
                id = item.id,
                title = item.summary,
                snippet = item.nextAction,
                statusText = if (item.voidedAt == null) handoverStatusLabel(item.status) else "已作废",
                sortTime = item.updatedAt,
                expandedMatch = false,
            )
        }
        return results.sortedByDescending { it.sortTime }.take(limit)
    }

    private suspend fun collectAliasIds(likeTerms: List<String>): List<String> {
        val ids = mutableListOf<String>()
        for (term in likeTerms) {
            ids += database.deviceDao().idsMatching(term)
        }
        return ids.distinct().let { if (it.isEmpty()) listOf("") else it }
    }

    private suspend fun searchFaults(
        likeTerms: List<String>,
        aliasDeviceIds: List<String>,
        includeVoided: Boolean,
        dateFromMillis: Long?,
        dateToMillis: Long?,
        filters: SearchFilters,
        normalized: String,
    ): List<SearchResult> {
        val faults = mutableListOf<FaultRecordEntity>()
        for (term in likeTerms) {
            faults += database.faultRecordDao().searchByTerm(term, aliasDeviceIds, includeVoided)
        }
        val results = mutableListOf<SearchResult>()
        for (fault in faults.distinctBy { it.id }) {
            if (dateFromMillis != null && fault.reportedAt < dateFromMillis) continue
            if (dateToMillis != null && fault.reportedAt >= dateToMillis) continue
            val latest = fault.lastProcessingId?.let { database.faultProcessingDao().findById(it) }
            if (filters.processingStatuses.isNotEmpty() &&
                (latest == null || latest.progressStatus !in filters.processingStatuses)
            ) {
                continue
            }
            results += SearchResult(
                type = SearchRecordType.FAULT,
                id = fault.id,
                title = fault.deviceNameSnapshot,
                snippet = fault.symptom,
                statusText = faultStatusText(fault, latest),
                sortTime = fault.updatedAt,
                expandedMatch = !faultContainsOriginal(fault, latest, normalized),
            )
        }
        return results
    }

    private suspend fun searchWorkLogs(
        likeTerms: List<String>,
        aliasDeviceIds: List<String>,
        includeVoided: Boolean,
        dateFromMillis: Long?,
        dateToMillis: Long?,
        filters: SearchFilters,
        normalized: String,
    ): List<SearchResult> {
        val logs = mutableListOf<WorkLogEntity>()
        for (term in likeTerms) {
            logs += database.workLogDao().searchByTerm(term, aliasDeviceIds, includeVoided)
        }
        val results = mutableListOf<SearchResult>()
        for (log in logs.distinctBy { it.id }) {
            if (dateFromMillis != null || dateToMillis != null) {
                val date = runCatching { LocalDate.parse(log.workDate) }.getOrNull() ?: continue
                val millis = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
                if (dateFromMillis != null && millis < dateFromMillis) continue
                if (dateToMillis != null && millis >= dateToMillis) continue
            }
            if (filters.attendanceKinds.isNotEmpty() &&
                (log.attendanceKindSnapshot == null || log.attendanceKindSnapshot !in filters.attendanceKinds)
            ) {
                continue
            }
            results += SearchResult(
                type = SearchRecordType.WORK_LOG,
                id = log.id,
                title = log.deviceNameSnapshot ?: "普通工作",
                snippet = log.content,
                statusText = if (log.voidedAt == null) "已记录" else "已作废",
                extraTag = if (log.kind == "FAULT_DERIVED") "故障派生" else null,
                sortTime = log.updatedAt,
                expandedMatch = !workLogContainsOriginal(log, normalized),
            )
        }
        return results
    }

    private suspend fun searchHandovers(
        likeTerms: List<String>,
        includeVoided: Boolean,
        dateFromMillis: Long?,
        dateToMillis: Long?,
        normalized: String,
    ): List<SearchResult> {
        val items = mutableListOf<HandoverItemEntity>()
        for (term in likeTerms) {
            items += database.handoverItemDao().searchByTerm(term, includeVoided)
        }
        val results = mutableListOf<SearchResult>()
        for (item in items.distinctBy { it.id }) {
            if (dateFromMillis != null && item.createdAt < dateFromMillis) continue
            if (dateToMillis != null && item.createdAt >= dateToMillis) continue
            results += SearchResult(
                type = SearchRecordType.HANDOVER,
                id = item.id,
                title = item.summary,
                snippet = item.nextAction,
                statusText = if (item.voidedAt == null) handoverStatusLabel(item.status) else "已作废",
                sortTime = item.updatedAt,
                expandedMatch = !handoverContainsOriginal(item, normalized),
            )
        }
        return results
    }

    // ---- 原文命中判定：原文出现在可搜索文本字段时不算扩展命中；否则为同义词/别名扩展 ----

    private fun faultContainsOriginal(fault: FaultRecordEntity, latest: FaultProcessingEntity?, query: String): Boolean =
        listOfNotNull(
            fault.deviceNameSnapshot,
            fault.symptom,
            latest?.checkResult,
            latest?.initialJudgement,
            latest?.rootCause,
            latest?.measures,
            latest?.verification,
        ).any { it.contains(query) }

    private fun workLogContainsOriginal(log: WorkLogEntity, query: String): Boolean =
        listOfNotNull(log.content, log.workResult, log.deviceNameSnapshot).any { it.contains(query) }

    private fun handoverContainsOriginal(item: HandoverItemEntity, query: String): Boolean =
        listOfNotNull(item.summary, item.nextAction, item.potentialHazardNote).any { it.contains(query) }

    private fun faultStatusText(fault: FaultRecordEntity, latest: FaultProcessingEntity?): String {
        if (fault.voidedAt != null) return "已作废"
        return when (latest?.progressStatus) {
            "DRAFT" -> "草稿"
            "IN_PROGRESS" -> "处理中"
            "PENDING_VERIFICATION" -> "待验证"
            "ENDED" -> "已结束｜${restoreLabel(latest.restoreResult)}"
            "CANCELED" -> "已取消"
            else -> if (fault.lifecycleStatus == "CLOSED") "已结束" else "待处理"
        }
    }

    private fun restoreLabel(restoreResult: String?): String = when (restoreResult) {
        "RESTORED" -> "已恢复"
        "TEMPORARY" -> "临时恢复"
        "PARTIAL" -> "部分恢复"
        "NOT_RESTORED" -> "未恢复"
        "UNKNOWN" -> "无法确认"
        else -> "待跟进"
    }

    private fun handoverStatusLabel(status: String): String = when (status) {
        "PENDING_HANDOVER" -> "待交接"
        "HANDED_OVER" -> "已交接"
        "IN_PROGRESS" -> "处理中"
        "COMPLETED" -> "已完成"
        "CANCELED" -> "已取消"
        else -> status
    }
}
