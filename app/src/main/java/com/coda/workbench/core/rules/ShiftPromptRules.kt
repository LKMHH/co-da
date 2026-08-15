package com.coda.workbench.core.rules

import java.time.LocalDate
import java.time.YearMonth

/** 每月 1 号排班确认提醒：仅当天、本月未确认、且本月尚未提示过时发送（lastShiftPromptMonth 去重）。 */
object ShiftPromptRules {
    fun shouldNotify(
        today: LocalDate,
        confirmedAt: Long?,
        lastPromptMonth: String?,
    ): Boolean =
        today.dayOfMonth == 1 &&
            confirmedAt == null &&
            lastPromptMonth != YearMonth.from(today).toString()
}
