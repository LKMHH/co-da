package com.coda.workbench

import com.coda.workbench.core.model.FaultLifecycleStatus
import com.coda.workbench.core.model.ProcessingAction
import com.coda.workbench.core.model.ProcessingRecord
import com.coda.workbench.core.model.ProcessingStatus
import com.coda.workbench.core.model.ProcessingTransition
import com.coda.workbench.core.rules.ProcessingRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProcessingRulesTest {
    @Test
    fun legalTransitionsMatchTheFrozenStateMachine() {
        assertAccepted(ProcessingStatus.DRAFT, ProcessingAction.START, ProcessingStatus.IN_PROGRESS)
        assertAccepted(
            ProcessingStatus.IN_PROGRESS,
            ProcessingAction.MARK_PENDING_VERIFICATION,
            ProcessingStatus.PENDING_VERIFICATION,
        )
        assertAccepted(
            ProcessingStatus.PENDING_VERIFICATION,
            ProcessingAction.RESUME,
            ProcessingStatus.IN_PROGRESS,
        )
        assertAccepted(ProcessingStatus.IN_PROGRESS, ProcessingAction.END, ProcessingStatus.ENDED)
        assertAccepted(ProcessingStatus.PENDING_VERIFICATION, ProcessingAction.END, ProcessingStatus.ENDED)
        assertAccepted(ProcessingStatus.DRAFT, ProcessingAction.CANCEL, ProcessingStatus.CANCELED)
        assertAccepted(ProcessingStatus.IN_PROGRESS, ProcessingAction.CANCEL, ProcessingStatus.CANCELED)
        assertAccepted(
            ProcessingStatus.PENDING_VERIFICATION,
            ProcessingAction.CANCEL,
            ProcessingStatus.CANCELED,
        )
    }

    @Test
    fun invalidTransitionsAreRejected() {
        assertIs<ProcessingTransition.Rejected>(
            ProcessingRules.transition(ProcessingStatus.DRAFT, ProcessingAction.END),
        )
        assertIs<ProcessingTransition.Rejected>(
            ProcessingRules.transition(ProcessingStatus.IN_PROGRESS, ProcessingAction.START),
        )
        assertIs<ProcessingTransition.Rejected>(
            ProcessingRules.transition(ProcessingStatus.ENDED, ProcessingAction.RESUME),
        )
        assertIs<ProcessingTransition.Rejected>(
            ProcessingRules.transition(ProcessingStatus.CANCELED, ProcessingAction.START),
        )
    }

    @Test
    fun onlyUnvoidedTerminalProcessingsCanBeVoided() {
        assertFalse(ProcessingRules.canVoidProcessing(ProcessingRecord(ProcessingStatus.DRAFT)))
        assertFalse(ProcessingRules.canVoidProcessing(ProcessingRecord(ProcessingStatus.IN_PROGRESS)))
        assertFalse(
            ProcessingRules.canVoidProcessing(
                ProcessingRecord(ProcessingStatus.PENDING_VERIFICATION),
            ),
        )
        assertTrue(ProcessingRules.canVoidProcessing(ProcessingRecord(ProcessingStatus.ENDED)))
        assertTrue(ProcessingRules.canVoidProcessing(ProcessingRecord(ProcessingStatus.CANCELED)))
        assertFalse(
            ProcessingRules.canVoidProcessing(
                ProcessingRecord(ProcessingStatus.ENDED, voidedAt = 1L),
            ),
        )
    }

    @Test
    fun unfinishedProcessingBlocksFaultVoiding() {
        assertFalse(
            ProcessingRules.canVoidFault(
                FaultLifecycleStatus.OPEN,
                listOf(ProcessingRecord(ProcessingStatus.DRAFT)),
            ),
        )
        assertFalse(
            ProcessingRules.canVoidFault(
                FaultLifecycleStatus.CLOSED,
                listOf(ProcessingRecord(ProcessingStatus.PENDING_VERIFICATION)),
            ),
        )
        assertTrue(
            ProcessingRules.canVoidFault(
                FaultLifecycleStatus.OPEN,
                listOf(
                    ProcessingRecord(ProcessingStatus.ENDED),
                    ProcessingRecord(ProcessingStatus.CANCELED),
                ),
            ),
        )
        assertFalse(ProcessingRules.canVoidFault(FaultLifecycleStatus.VOIDED, emptyList()))
    }

    private fun assertAccepted(
        current: ProcessingStatus,
        action: ProcessingAction,
        expected: ProcessingStatus,
    ) {
        val result = assertIs<ProcessingTransition.Accepted>(
            ProcessingRules.transition(current, action),
        )
        assertEquals(expected, result.status)
    }
}
