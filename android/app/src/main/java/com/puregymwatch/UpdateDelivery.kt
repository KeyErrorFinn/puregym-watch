package com.puregymwatch

/** Two-phase exchange understood by the original watch 0.1.4 receiver. */
class UpdateDelivery(private val link: Link, private val progress: (String) -> Unit) {
    interface Link {
        suspend fun discover(): String
        suspend fun probe(target: String, request: String)
        suspend fun reply(target: String, request: String): String?
        suspend fun upload(target: String, request: String)
    }
    suspend fun send(probeRequest: String, updateRequest: String) {
        progress("Connecting to watch...")
        val target = link.discover()
        progress("Checking that PG Access is responding on the watch. Keep the watch app open.")
        link.probe(target, probeRequest)
        val acknowledgement = link.reply(target, probeRequest)
        check(!acknowledgement.isNullOrBlank()) {
            "Your watch was found but PG Access did not reply. Open PG Access on the watch, leave it on screen, then retry. No APK has been sent yet."
        }
        progress("Watch responded. Sending the update file...")
        link.upload(target, updateRequest)
        progress("Update queued. Keep PG Access open on the watch while it receives the file.")
    }
    companion object {
        fun matchesReply(expectedTarget: String, expectedRequest: String, source: String?, request: String?) =
            source == expectedTarget && request == expectedRequest
    }
}
