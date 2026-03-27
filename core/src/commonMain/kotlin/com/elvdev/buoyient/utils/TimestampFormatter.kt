package com.elvdev.buoyient.utils

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

public object TimestampFormatter {
    /**
     * Formats an epoch seconds timestamp into a UTC string with the format 'yyyy-MM-dd HH:mm:ss'.
     */
    public fun fromEpochSeconds(epochSeconds: Long): String {
        val instant = Instant.Companion.fromEpochSeconds(epochSeconds)
        val dt = instant.toLocalDateTime(TimeZone.Companion.UTC)
        return "${dt.year.pad(4)}-${dt.monthNumber.pad(2)}-${dt.dayOfMonth.pad(2)} " +
            "${dt.hour.pad(2)}:${dt.minute.pad(2)}:${dt.second.pad(2)}"
    }

    private fun Int.pad(length: Int): String = toString().padStart(length, '0')
}
