package com.finnl.gymaccess.protocol

import org.json.JSONObject

data class WatchSettings(val size: Int = 3, val closeSeconds: Int = 30, val closeWarning: Boolean = false) {
    init { require(size in 1..3); require(closeSeconds in 15..120) }
    fun json() = JSONObject().put("size", size).put("closeSeconds", closeSeconds).put("closeWarning", closeWarning)
    fun remainingMillis(elapsedMillis: Long) = (closeSeconds * 1000L - elapsedMillis).coerceAtLeast(0)
    companion object {
        const val PATH = "/gymaccess/v1/settings"
        fun parse(json: JSONObject) = WatchSettings(json.getInt("size"), json.getInt("closeSeconds"), json.optBoolean("closeWarning", false))
    }
}
