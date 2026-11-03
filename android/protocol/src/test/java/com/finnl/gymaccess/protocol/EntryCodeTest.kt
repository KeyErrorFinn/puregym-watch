package com.finnl.gymaccess.protocol

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EntryCodeTest {
    @Test fun expiryIsExclusive() {
        val code = EntryCode("entry", 1500, 2000)
        assertTrue(code.valid(1999)); assertFalse(code.valid(2000))
    }
    @Test fun parsesServerTimestampsAndBoundsRefresh() {
        val result = EntryCode.fromServer(JSONObject("""{"qrCode":"test","refreshAt":"2030-01-01T00:02:00Z","expiresAt":"2030-01-01T00:01:00Z"}"""), 1000)
        assertEquals(result.expiresAt, result.refreshAt)
    }
    @Test fun supportsIsoDuration() {
        val code = EntryCode.fromServer(JSONObject("""{"qrCode":"test","refreshIn":"PT30S","expiresIn":"PT2M"}"""), 1000)
        assertEquals(31000, code.refreshAt); assertEquals(121000, code.expiresAt)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsMissingTiming() {
        EntryCode.fromServer(JSONObject("""{"qrCode":"test"}"""), 1000)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsExpiredResponse() {
        EntryCode.fromServer(JSONObject("""{"qrCode":"test","refreshAt":"2000-01-01T00:00:00Z","expiresAt":"2000-01-01T00:01:00Z"}"""), System.currentTimeMillis())
    }
    @Test fun protocolRoundTrip() {
        val code = EntryCode("payload", 1500, 2000)
        assertEquals(code, EntryCode.fromWire(code.json()))
        Wire.check(Wire.result("ready", code))
    }
}
