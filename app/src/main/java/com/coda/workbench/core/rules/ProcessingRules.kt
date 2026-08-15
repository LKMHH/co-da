package com.coda.workbench.core.rules

import com.coda.workbench.core.model.FaultLifecycleStatus
import com.coda.workbench.core.model.ProcessingAction
import com.coda.workbench.core.model.ProcessingRecord
import com.coda.workbench.core.model.ProcessingStatus
import com.coda.workbench.core.model.ProcessingTransition

object ProcessingRules {
    fun transition(
        current: ProcessingStatus,
        action: ProcessingAction,
    ): ProcessingTransition {
        val next = when (action) {
            ProcessingAction.START -> if (current == ProcessingStatus.DRAFT) {
                ProcessingStatus.IN_PROGRESS
            } else null

            ProcessingAction.MARK_PENDING_VERIFICATION -> if (current == ProcessingStatus.IN_PROGRESS) {
                ProcessingStatus.PENDING_VERIFICATION
            } else null

            ProcessingAction.RESUME -> if (current == ProcessingStatus.PENDING_VERIFICATION) {
                ProcessingStatus.IN_PROGRESS
            } else null

            ProcessingAction.END -> if (
                current == ProcessingStatus.IN_PROGRESS ||
                current == ProcessingStatus.PENDING_VERIFICATION
            ) {
                ProcessingStatus.ENDED
            } else null

            ProcessingAction.CANCEL -> if (
                current == ProcessingStatus.DRAFT ||
                current == ProcessingStatus.IN_PROGRESS ||
                current == ProcessingStatus.PENDING_VERIFICATION
            ) {
                ProcessingStatus.CANCELED
            } else null
        }

        return next?.let { ProcessingTransition.Accepted(it) }
            ?: ProcessingTransition.Rejected(
                "不允许从 $current 执行 ${action.toDisplayName()}",
            )
    }

    fun canVoidProcessing(record: ProcessingRecord): Boolean =
        record.voidedAt == null && (
            record.status == ProcessingStatus.ENDED ||
                record.status == ProcessingStatus.CANCELED
            )

    fun canVoidFault(
        lifecycleStatus: FaultLifecycleStatus,
        processings: List<ProcessingRecord>,
    ): Boolean = lifecycleStatus != FaultLifecycleStatus.VOIDED &&
        processings.none { it.status.isUnfinished() }

    private fun ProcessingStatus.isUnfinished(): Boolean = when (this) {
        ProcessingStatus.DRAFT,
        ProcessingStatus.IN_PROGRESS,
        ProcessingStatus.PENDING_VERIFICATION -> true

        ProcessingStatus.ENDED,
        ProcessingStatus.CANCELED -> false
    }

    private fun ProcessingAction.toDisplayName(): String = when (this) {
        ProcessingAction.START -> "开始处理"
        ProcessingAction.MARK_PENDING_VERIFICATION -> "标记待验证"
        ProcessingAction.RESUME -> "继续处理"
        ProcessingAction.END -> "结束本次处理"
        ProcessingAction.CANCEL -> "取消本次处理"
    }
}
