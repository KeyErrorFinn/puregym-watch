package com.finnl.gymaccess.protocol

import org.junit.Assert.*
import org.junit.Test

class QrResponseTest {
    private fun failure(status: Int, body: String = "{}"): String {
        try { QrResponse.parse(status, body, 1000); fail("Expected failure") }
        catch (e: QrResponseFailure) { return e.status }
        return "unreachable"
    }
    @Test fun mapsAuthenticationAndRateLimits() {
        assertEquals("sign_in_required", failure(401))
        assertEquals("access_denied", failure(403))
        assertEquals("rate_limited", failure(429))
    }
    @Test fun handlesGraphqlDenialWithoutExposingServerMessage() {
        assertEquals("access_denied", failure(200, """{"data":{"createQrAccessCode":{"errors":[{"__typename":"NotGrantedError","message":"private detail"}]}}}"""))
    }
    @Test fun rejectsMalformedAndTopLevelErrors() {
        assertEquals("service_error", failure(200, "invalid"))
        assertEquals("service_error", failure(200, """{"errors":[{"message":"private detail"}]}"""))
        assertEquals("service_error", failure(200, """{"data":null}"""))
    }
    @Test fun parsesValidResponse() {
        val code = QrResponse.parse(200, """{"data":{"createQrAccessCode":{"qrCode":"test","refreshIn":"PT30S","expiresIn":"PT2M","errors":[]}}}""", 1000)
        assertEquals("test", code.payload)
        assertEquals(121000, code.expiresAt)
    }
}
