package com.finnl.gymaccess.protocol

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object RetryWindow {
    fun milliseconds(header: String?, now: Long): Long {
        if (header == null) return 60000
        val seconds = header.trim().toLongOrNull()
        if (seconds != null) return if (seconds in 0..604800) maxOf(5000, seconds * 1000) else 604800000
        return try {
            maxOf(5000, ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now)
        } catch (_: Exception) { 60000 }
    }
}
