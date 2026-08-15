package com.coda.workbench.core.model

enum class ProcessingStatus {
    DRAFT,
    IN_PROGRESS,
    PENDING_VERIFICATION,
    ENDED,
    CANCELED,
}

enum class ProcessingAction {
    START,
    MARK_PENDING_VERIFICATION,
    RESUME,
    END,
    CANCEL,
}

enum class FaultLifecycleStatus {
    OPEN,
    CLOSED,
    VOIDED,
}

data class ProcessingRecord(
    val status: ProcessingStatus,
    val voidedAt: Long? = null,
)

sealed interface ProcessingTransition {
    data class Accepted(val status: ProcessingStatus) : ProcessingTransition
    data class Rejected(val reason: String) : ProcessingTransition
}
