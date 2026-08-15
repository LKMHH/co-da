package com.coda.workbench

import com.coda.workbench.core.model.ProductionGroup
import com.coda.workbench.core.model.ShiftSlotSuggestion
import com.coda.workbench.core.model.ShiftType
import com.coda.workbench.core.rules.ShiftScheduleRules
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShiftScheduleRulesTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun ordinaryDayGeneratesDayAndCrossMidnightNightSlots() {
        val slots = augustPlan()
        val day = slots.slotStarting("2026-08-14", "08:00")
        val night = slots.slotStarting("2026-08-14", "20:00")

        assertSlot(day, ProductionGroup.A, ShiftType.DAY, "2026-08-14", "20:00", false)
        assertSlot(night, ProductionGroup.B, ShiftType.NIGHT, "2026-08-15", "08:00", false)
        assertEquals(LocalDate.of(2026, 8, 14), night.businessDate)
    }

    @Test
    fun fifteenthGeneratesAllFourShiftChangeSegments() {
        val slots = augustPlan()

        assertSlot(
            slots.slotStarting("2026-08-15", "08:00"),
            ProductionGroup.A,
            ShiftType.DAY,
            "2026-08-15",
            "16:00",
            true,
        )
        assertSlot(
            slots.slotStarting("2026-08-15", "16:00"),
            ProductionGroup.B,
            ShiftType.NIGHT,
            "2026-08-16",
            "00:00",
            true,
        )
        val midnight = slots.slotStarting("2026-08-16", "00:00")
        assertSlot(
            midnight,
            ProductionGroup.A,
            ShiftType.NIGHT,
            "2026-08-16",
            "08:00",
            true,
        )
        assertEquals(LocalDate.of(2026, 8, 15), midnight.businessDate)
        assertSlot(
            slots.slotStarting("2026-08-16", "08:00"),
            ProductionGroup.B,
            ShiftType.DAY,
            "2026-08-16",
            "20:00",
            true,
        )
    }

    @Test
    fun groupsSwapAfterTheFifteenth() {
        val slots = augustPlan()
        assertEquals(ProductionGroup.B, slots.slotStarting("2026-08-17", "08:00").group)
        assertEquals(ProductionGroup.A, slots.slotStarting("2026-08-17", "20:00").group)
    }

    @Test
    fun monthEndMidnightSegmentKeepsPreviousBusinessDate() {
        val slots = augustPlan()
        val midnight = slots.slotStarting("2026-09-01", "00:00")

        assertSlot(
            midnight,
            ProductionGroup.B,
            ShiftType.NIGHT,
            "2026-09-01",
            "08:00",
            true,
        )
        assertEquals(LocalDate.of(2026, 8, 31), midnight.businessDate)
    }

    @Test
    fun previousMonthDoesNotGuessNextMonthEightToTwentyGroup() {
        val augustSlots = augustPlan()
        assertFalse(augustSlots.any { it.startAt == instant("2026-09-01", "08:00") })

        val septemberSlots = ShiftScheduleRules.generateMonth(
            month = YearMonth.of(2026, 9),
            initialDayGroup = ProductionGroup.A,
            zoneId = zoneId,
        )
        assertEquals(
            ProductionGroup.A,
            septemberSlots.slotStarting("2026-09-01", "08:00").group,
        )
    }

    @Test
    fun currentMonthGenerationUsesInjectedClockAndZone() {
        val slots = ShiftScheduleRules.generateCurrentMonth(
            initialDayGroup = ProductionGroup.B,
            clock = Clock.fixed(Instant.parse("2026-08-31T18:00:00Z"), zoneId),
            zoneId = zoneId,
        )

        assertTrue(slots.all { YearMonth.from(it.businessDate) == YearMonth.of(2026, 9) })
        assertEquals(ProductionGroup.B, slots.slotStarting("2026-09-01", "08:00").group)
    }

    private fun augustPlan(): List<ShiftSlotSuggestion> = ShiftScheduleRules.generateMonth(
        month = YearMonth.of(2026, 8),
        initialDayGroup = ProductionGroup.A,
        zoneId = zoneId,
    )

    private fun List<ShiftSlotSuggestion>.slotStarting(
        date: String,
        time: String,
    ): ShiftSlotSuggestion = single { it.startAt == instant(date, time) }

    private fun assertSlot(
        slot: ShiftSlotSuggestion,
        group: ProductionGroup,
        type: ShiftType,
        endDate: String,
        endTime: String,
        isShiftChange: Boolean,
    ) {
        assertEquals(group, slot.group)
        assertEquals(type, slot.shiftType)
        assertEquals(instant(endDate, endTime), slot.endAt)
        assertEquals(isShiftChange, slot.isShiftChange)
    }

    private fun instant(date: String, time: String): Instant = LocalDateTime.of(
        LocalDate.parse(date),
        LocalTime.parse(time),
    ).atZone(zoneId).toInstant()
}
