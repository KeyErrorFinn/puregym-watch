package com.finnl.gymaccess.protocol

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class RetryWindowTest {
    @Test fun respectsServerDelay() { assertEquals(300000, RetryWindow.milliseconds("300", 0)) }
    @Test fun supportsHttpDates() {
        val now = Instant.parse("2030-01-01T00:00:00Z").toEpochMilli()
        assertEquals(120000, RetryWindow.milliseconds("Tue, 1 Jan 2030 00:02:00 GMT", now))
    }
    @Test fun handlesInvalidHeaderAndPreventsImmediateRetry() {
        assertEquals(60000, RetryWindow.milliseconds("invalid", 0))
        assertEquals(5000, RetryWindow.milliseconds("0", 0))
    }
}
