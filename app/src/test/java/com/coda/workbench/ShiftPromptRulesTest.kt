package com.coda.workbench

import com.coda.workbench.core.rules.ShiftPromptRules
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShiftPromptRulesTest {
    @Test
    fun firstOfUnconfirmedMonthNotPromptedYetTriggers() {
        assertTrue(
            ShiftPromptRules.shouldNotify(
                today = LocalDate.of(2026, 9, 1),
                confirmedAt = null,
                lastPromptMonth = null,
            ),
        )
    }

    @Test
    fun alreadyPromptedThisMonthDoesNotRepeat() {
        assertFalse(
            ShiftPromptRules.shouldNotify(
                today = LocalDate.of(2026, 9, 1),
                confirmedAt = null,
                lastPromptMonth = "2026-09",
            ),
        )
    }

    @Test
    fun confirmedMonthNeverPrompts() {
        assertFalse(
            ShiftPromptRules.shouldNotify(
                today = LocalDate.of(2026, 9, 1),
                confirmedAt = 123L,
                lastPromptMonth = null,
            ),
        )
    }

    @Test
    fun nonFirstDayNeverPrompts() {
        assertFalse(
            ShiftPromptRules.shouldNotify(
                today = LocalDate.of(2026, 9, 15),
                confirmedAt = null,
                lastPromptMonth = null,
            ),
        )
    }
}
