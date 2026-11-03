package com.finnl.gymaccess.protocol
import org.junit.Assert.*
import org.junit.Test
class WatchSettingsTest {
    @Test fun warningIsOptionalAndBackwardCompatible() {
        assertFalse(WatchSettings().closeWarning)
        assertFalse(WatchSettings.parse(org.json.JSONObject("{\"size\":3,\"closeSeconds\":30}")).closeWarning)
        val enabled = WatchSettings(closeWarning = true)
        assertEquals(enabled, WatchSettings.parse(enabled.json()))
    }
    @Test fun defaultsAndRoundTrip() { val value = WatchSettings(); assertEquals(3, value.size); assertEquals(30, value.closeSeconds); assertEquals(value, WatchSettings.parse(value.json())) }
    @Test fun timerUsesTimeSinceOpening() { assertEquals(30000L, WatchSettings().remainingMillis(0)); assertEquals(19000L, WatchSettings().remainingMillis(11000)); assertEquals(0L, WatchSettings(closeSeconds = 15).remainingMillis(16000)); assertEquals(104000L, WatchSettings(closeSeconds = 120).remainingMillis(16000)) }
    @Test fun validatesAllAllowedValues() { for (size in 1..3) for (seconds in 15..120) assertEquals(size, WatchSettings(size, seconds).size) }
    @Test(expected = IllegalArgumentException::class) fun rejectsOldLargeSizes() { WatchSettings(4, 30) }
    @Test(expected = IllegalArgumentException::class) fun rejectsTooShortTimer() { WatchSettings(3, 14) }
    @Test(expected = IllegalArgumentException::class) fun rejectsTooLongTimer() { WatchSettings(3, 121) }
}
