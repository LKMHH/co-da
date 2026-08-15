package com.coda.workbench.core.model

import java.time.LocalDate

enum class SearchRecordType { FAULT, WORK_LOG, HANDOVER }

/** 技术稿 §8 SearchFilters：至少包含记录类型、日期范围、处理状态、出勤标记和 includeVoided。空集合表示不筛选。 */
data class SearchFilters(
    val recordTypes: Set<SearchRecordType> = emptySet(),
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null,
    val processingStatuses: Set<String> = emptySet(),
    val attendanceKinds: Set<String> = emptySet(),
    val includeVoided: Boolean = false,
)

data class SearchResult(
    val type: SearchRecordType,
    /** 故障 id / 工作记录 id / 交接事项 id，导航直达。 */
    val id: String,
    val title: String,
    val snippet: String,
    val statusText: String,
    /** updatedAt，结果按此倒序。 */
    val sortTime: Long,
    /** 原文未命中、经同义词或设备别名扩展命中时为 true，UI 显示"已扩展匹配"。 */
    val expandedMatch: Boolean,
)
