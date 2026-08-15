package com.coda.workbench.core.usecase

import com.coda.workbench.data.local.WorkLogEntity
import com.coda.workbench.data.repository.HomeRepository
import com.coda.workbench.data.repository.HomeSnapshot
import com.coda.workbench.data.repository.HomeWorkView
import com.coda.workbench.data.repository.WorkKindFilter

class HomeUseCase(private val repository: HomeRepository) {
    suspend fun load(
        view: HomeWorkView = HomeWorkView.NATURAL_DAY,
        kindFilter: WorkKindFilter = WorkKindFilter.ALL,
        includeVoided: Boolean = false,
    ): HomeSnapshot = repository.load(view, kindFilter, includeVoided)

    suspend fun loadWorkLog(id: String): WorkLogEntity? = repository.workLog(id)

    suspend fun faultIdForDerivedLog(log: WorkLogEntity): String? = repository.faultIdForDerivedLog(log)
}
