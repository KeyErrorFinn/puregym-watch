package com.puregymwatch

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UpdateDeliveryTest {
    private class FakeLink(var acknowledgement: String? = "Watch is up to date (0.1.4).") : UpdateDelivery.Link {
        val calls = mutableListOf<String>()
        var uploadFailure = false
        override suspend fun discover(): String { calls += "discover"; return "watch" }
        override suspend fun probe(target: String, request: String) { calls += "probe:$target:$request" }
        override suspend fun reply(target: String, request: String): String? { calls += "reply:$target:$request"; return acknowledgement }
        override suspend fun upload(target: String, request: String) { calls += "upload:$target:$request"; if (uploadFailure) error("File submission failed") }
    }
    @Test fun legacyStatusReplyConfirmsProbeBeforeAnyApkIsSent() = runBlocking {
        val link = FakeLink(); val progress = mutableListOf<String>()
        UpdateDelivery(link, progress::add).send("probe-id", "apk-id")
        assertEquals(listOf("discover", "probe:watch:probe-id", "reply:watch:probe-id", "upload:watch:apk-id"), link.calls)
        assertTrue(progress.last().startsWith("Update queued"))
        assertFalse(progress.last().contains("up to date")) // Probe is not the APK acknowledgement.
    }
    @Test fun noReplyPreventsLargeUploadAndExplainsWhatToDo() = runBlocking {
        val link = FakeLink(null)
        val failure = runCatching { UpdateDelivery(link) {}.send("probe", "apk") }.exceptionOrNull()
        assertTrue(failure?.message?.contains("did not reply") == true)
        assertFalse(link.calls.any { it.startsWith("upload") })
    }
    @Test fun fileSubmissionFailureIsNotReportedAsQueued() = runBlocking {
        val link = FakeLink().apply { uploadFailure = true }; val progress = mutableListOf<String>()
        assertNotNull(runCatching { UpdateDelivery(link, progress::add).send("probe", "apk") }.exceptionOrNull())
        assertFalse(progress.any { it.startsWith("Update queued") })
    }
    @Test fun wrongWatchOrOldRequestCannotAcknowledgeCurrentUpdate() {
        assertTrue(UpdateDelivery.matchesReply("watch", "new", "watch", "new"))
        assertFalse(UpdateDelivery.matchesReply("watch", "new", "watch", "old"))
        assertFalse(UpdateDelivery.matchesReply("watch", "new", "other-watch", "new"))
    }
}
