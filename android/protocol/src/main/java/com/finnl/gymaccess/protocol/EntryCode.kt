package com.finnl.gymaccess.protocol

import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

data class EntryCode(val payload: String, val refreshAt: Long, val expiresAt: Long) {
    fun valid(now: Long = System.currentTimeMillis()) = payload.isNotBlank() && expiresAt > now
    fun json(): JSONObject = JSONObject().put("payload", payload).put("refreshAt", refreshAt).put("expiresAt", expiresAt)
    companion object {
        fun fromWire(json: JSONObject): EntryCode = EntryCode(json.getString("payload"), json.getLong("refreshAt"), json.getLong("expiresAt"))
        fun fromServer(json: JSONObject, receivedAt: Long): EntryCode {
            fun deadline(absolute: String, relative: String): Long {
                val value = json.optString(absolute)
                if (value.isNotBlank() && value != "null") {
                    return try { Instant.parse(value).toEpochMilli() } catch (_: Exception) { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
                }
                val duration = json.optString(relative)
                val milliseconds = try { Duration.parse(duration).toMillis() } catch (_: Exception) {
                    // The legacy API can serialize a duration as .NET TimeSpan.
                    val match = Regex("(?:(\\d+)\\.)?(\\d{1,2}):(\\d{2}):(\\d{2}(?:\\.\\d+)?)").matchEntire(duration)
                        ?: throw IllegalArgumentException("Missing or invalid QR timing")
                    val (days, hours, minutes, seconds) = match.destructured
                    (((days.toLongOrNull() ?: 0) * 86400 + hours.toLong() * 3600 + minutes.toLong() * 60) * 1000 + seconds.toDouble() * 1000).toLong()
                }
                require(milliseconds > 0 && milliseconds < 31L * 86400000) { "Invalid QR lifetime" }
                return Math.addExact(receivedAt, milliseconds)
            }
            val expires = deadline("expiresAt", "expiresIn")
            val refresh = deadline("refreshAt", "refreshIn")
            val code = EntryCode(json.getString("qrCode"), minOf(refresh, expires), expires)
            require(code.valid(receivedAt)) { "QR code is empty or expired" }
            return code
        }
    }
}
object Wire {
    const val REQUEST = "/gymaccess/v1/request"
    const val RESPONSE = "/gymaccess/v1/response"
    const val OPEN_PHONE = "/gymaccess/v1/open-phone"
    const val STATE = "/gymaccess/v1/state"
    const val PHONE = "gym_access_phone_v1"
    const val VERSION = 1
    fun result(status: String, code: EntryCode? = null, message: String? = null): JSONObject =
        JSONObject().put("version", VERSION).put("status", status).apply {
            if (code != null) put("code", code.json())
            if (message != null) put("message", message)
        }
    fun check(value: JSONObject) { require(value.getInt("version") == VERSION) { "Update both phone and watch apps" } }
}
