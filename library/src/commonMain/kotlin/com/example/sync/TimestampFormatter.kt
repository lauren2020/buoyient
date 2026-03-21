package com.example.sync

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

object TimestampFormatter {
    /**
     * Formats an epoch seconds timestamp into a UTC string with the format 'yyyy-MM-dd HH:mm:ss'.
     */
    fun fromEpochSeconds(epochSeconds: Long): String {
        val instant = Instant.fromEpochSeconds(epochSeconds)
        val dt = instant.toLocalDateTime(TimeZone.UTC)
        return "%04d-%02d-%02d %02d:%02d:%02d".format(
            dt.year, dt.monthNumber, dt.dayOfMonth,
            dt.hour, dt.minute, dt.second
        )
    }
}
