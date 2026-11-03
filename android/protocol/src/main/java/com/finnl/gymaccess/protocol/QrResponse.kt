package com.finnl.gymaccess.protocol

import org.json.JSONObject

class QrResponseFailure(val status: String, override val message: String): Exception(message)
object QrResponse {
    fun parse(httpStatus: Int, body: String, receivedAt: Long): EntryCode {
        when (httpStatus) {
            401 -> throw QrResponseFailure("sign_in_required", "Your session expired. Sign in on your phone.")
            403 -> throw QrResponseFailure("access_denied", "PureGym did not grant access to an entry code.")
            429 -> throw QrResponseFailure("rate_limited", "PureGym is busy. Try again in a minute.")
        }
        if (httpStatus !in 200..299) throw QrResponseFailure("service_error", "PureGym could not provide an entry code. Try again.")
        try {
            val json = JSONObject(body)
            if (json.optJSONArray("errors")?.length()?.let { it > 0 } == true) throw QrResponseFailure("service_error", "PureGym could not complete the request.")
            val data = json.optJSONObject("data")?.optJSONObject("createQrAccessCode")
                ?: throw QrResponseFailure("service_error", "PureGym returned an unexpected response.")
            val errors = data.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                val denied = (0 until errors.length()).any { errors.getJSONObject(it).optString("__typename") == "NotGrantedError" }
                throw QrResponseFailure(if (denied) "access_denied" else "service_error", if (denied) "PureGym did not grant access to an entry code." else "PureGym could not create an entry code.")
            }
            return EntryCode.fromServer(data, receivedAt)
        } catch (e: QrResponseFailure) { throw e }
        catch (_: Exception) { throw QrResponseFailure("service_error", "PureGym returned an unexpected entry code.") }
    }
}
