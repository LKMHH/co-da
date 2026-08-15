package com.coda.workbench.core.usecase

import com.coda.workbench.core.model.SearchFilters
import com.coda.workbench.core.model.SearchResult
import com.coda.workbench.data.repository.SearchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** M6 搜索（技术稿 §5 SearchUseCase 契约）：查询流每次收集时执行一次 LIKE 搜索。 */
class SearchUseCase(private val repository: SearchRepository) {
    fun search(query: String, filters: SearchFilters): Flow<List<SearchResult>> = flow {
        emit(repository.search(query, filters))
    }
}
