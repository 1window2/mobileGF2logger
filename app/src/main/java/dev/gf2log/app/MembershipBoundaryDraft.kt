package dev.gf2log.app

import dev.gf2log.app.management.MembershipBoundaryValue
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Mutable picker state that preserves an untouched persisted boundary exactly.
 *
 * Reconstructing an Instant from the displayed date and minute can lose
 * seconds, nanoseconds, or the selected offset during a daylight-saving overlap.
 * The original value is therefore returned byte-for-byte until the user
 * explicitly changes or clears a field.
 */
internal class MembershipBoundaryDraft(
    initialValue: Instant?,
    initialDate: LocalDate?,
    initialTimeKnown: Boolean,
    private val zone: ZoneId,
) {
    private val original = (initialDate ?: initialValue?.atZone(zone)?.toLocalDate())?.let { date ->
        MembershipBoundaryValue(
            date = date,
            instant = initialValue ?: date.atStartOfDay(zone).toInstant(),
            timeKnown = initialTimeKnown && initialValue != null,
        )
    }
    private var dirty = false

    var date: LocalDate? = original?.date
        private set

    var time: LocalTime? = original
        ?.takeIf(MembershipBoundaryValue::timeKnown)
        ?.instant
        ?.atZone(zone)
        ?.toLocalTime()
        private set

    val boundary: MembershipBoundaryValue?
        get() {
            if (!dirty) return original
            return date?.let { selectedDate ->
                MembershipBoundaryValue(
                    date = selectedDate,
                    instant = selectedDate.atTime(time ?: LocalTime.MIDNIGHT)
                        .atZone(zone)
                        .toInstant(),
                    timeKnown = time != null,
                )
            }
        }

    fun selectDate(value: LocalDate) {
        if (date != value) {
            date = value
            dirty = true
        }
    }

    fun clearDate() {
        if (date != null || time != null) {
            date = null
            time = null
            dirty = true
        }
    }

    fun selectTime(value: LocalTime) {
        time = value
        dirty = true
    }

    fun clearTime() {
        if (time != null) {
            time = null
            dirty = true
        }
    }
}
