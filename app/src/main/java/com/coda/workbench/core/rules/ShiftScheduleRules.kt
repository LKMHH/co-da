package com.coda.workbench.core.rules

import com.coda.workbench.core.model.ProductionGroup
import com.coda.workbench.core.model.ShiftSlotSuggestion
import com.coda.workbench.core.model.ShiftType
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

object ShiftScheduleRules {
    fun generateCurrentMonth(
        initialDayGroup: ProductionGroup,
        clock: Clock,
        zoneId: ZoneId,
    ): List<ShiftSlotSuggestion> = generateMonth(
        month = YearMonth.now(clock.withZone(zoneId)),
        initialDayGroup = initialDayGroup,
        zoneId = zoneId,
    )

    fun generateMonth(
        month: YearMonth,
        initialDayGroup: ProductionGroup,
        zoneId: ZoneId,
    ): List<ShiftSlotSuggestion> = buildList {
        val firstDay = month.atDay(1)
        val middleChangeDate = month.atDay(15)
        val lastDay = month.atEndOfMonth()
        val secondHalfDayGroup = initialDayGroup.other()

        for (day in 1..14) {
            addRegularDay(firstDay.withDayOfMonth(day), initialDayGroup, zoneId)
        }

        addShiftChange(
            date = middleChangeDate,
            currentDayGroup = initialDayGroup,
            zoneId = zoneId,
            includeNextDayDaySlot = true,
        )

        addNightSlot(
            date = middleChangeDate.plusDays(1),
            nightGroup = initialDayGroup,
            zoneId = zoneId,
        )

        for (day in 17 until lastDay.dayOfMonth) {
            addRegularDay(firstDay.withDayOfMonth(day), secondHalfDayGroup, zoneId)
        }

        addShiftChange(
            date = lastDay,
            currentDayGroup = secondHalfDayGroup,
            zoneId = zoneId,
            includeNextDayDaySlot = false,
        )
    }.sortedBy { it.startAt }

    private fun MutableList<ShiftSlotSuggestion>.addRegularDay(
        date: LocalDate,
        dayGroup: ProductionGroup,
        zoneId: ZoneId,
    ) {
        add(
            slot(
                businessDate = date,
                group = dayGroup,
                shiftType = ShiftType.DAY,
                startDate = date,
                startTime = LocalTime.of(8, 0),
                endDate = date,
                endTime = LocalTime.of(20, 0),
                zoneId = zoneId,
                isShiftChange = false,
            ),
        )
        addNightSlot(date, dayGroup.other(), zoneId)
    }

    private fun MutableList<ShiftSlotSuggestion>.addNightSlot(
        date: LocalDate,
        nightGroup: ProductionGroup,
        zoneId: ZoneId,
    ) {
        add(
            slot(
                businessDate = date,
                group = nightGroup,
                shiftType = ShiftType.NIGHT,
                startDate = date,
                startTime = LocalTime.of(20, 0),
                endDate = date.plusDays(1),
                endTime = LocalTime.of(8, 0),
                zoneId = zoneId,
                isShiftChange = false,
            ),
        )
    }

    private fun MutableList<ShiftSlotSuggestion>.addShiftChange(
        date: LocalDate,
        currentDayGroup: ProductionGroup,
        zoneId: ZoneId,
        includeNextDayDaySlot: Boolean,
    ) {
        val nextDate = date.plusDays(1)
        val currentNightGroup = currentDayGroup.other()

        add(
            slot(
                businessDate = date,
                group = currentDayGroup,
                shiftType = ShiftType.DAY,
                startDate = date,
                startTime = LocalTime.of(8, 0),
                endDate = date,
                endTime = LocalTime.of(16, 0),
                zoneId = zoneId,
                isShiftChange = true,
            ),
        )
        add(
            slot(
                businessDate = date,
                group = currentNightGroup,
                shiftType = ShiftType.NIGHT,
                startDate = date,
                startTime = LocalTime.of(16, 0),
                endDate = nextDate,
                endTime = LocalTime.MIDNIGHT,
                zoneId = zoneId,
                isShiftChange = true,
            ),
        )
        add(
            slot(
                businessDate = date,
                group = currentDayGroup,
                shiftType = ShiftType.NIGHT,
                startDate = nextDate,
                startTime = LocalTime.MIDNIGHT,
                endDate = nextDate,
                endTime = LocalTime.of(8, 0),
                zoneId = zoneId,
                isShiftChange = true,
            ),
        )
        if (includeNextDayDaySlot) {
            add(
                slot(
                    businessDate = nextDate,
                    group = currentNightGroup,
                    shiftType = ShiftType.DAY,
                    startDate = nextDate,
                    startTime = LocalTime.of(8, 0),
                    endDate = nextDate,
                    endTime = LocalTime.of(20, 0),
                    zoneId = zoneId,
                    isShiftChange = true,
                ),
            )
        }
    }

    private fun slot(
        businessDate: LocalDate,
        group: ProductionGroup,
        shiftType: ShiftType,
        startDate: LocalDate,
        startTime: LocalTime,
        endDate: LocalDate,
        endTime: LocalTime,
        zoneId: ZoneId,
        isShiftChange: Boolean,
    ): ShiftSlotSuggestion = ShiftSlotSuggestion(
        businessDate = businessDate,
        group = group,
        shiftType = shiftType,
        startAt = LocalDateTime.of(startDate, startTime).atZone(zoneId).toInstant(),
        endAt = LocalDateTime.of(endDate, endTime).atZone(zoneId).toInstant(),
        isShiftChange = isShiftChange,
    )

    private fun ProductionGroup.other(): ProductionGroup = when (this) {
        ProductionGroup.A -> ProductionGroup.B
        ProductionGroup.B -> ProductionGroup.A
    }
}
