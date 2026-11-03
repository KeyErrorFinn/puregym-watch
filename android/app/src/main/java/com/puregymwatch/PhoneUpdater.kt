package com.puregymwatch

import android.content.Context
import android.os.ParcelFileDescriptor
import com.finnl.gymaccess.protocol.WatchUpdate
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.UUID

class PhoneUpdater(private val context: Context) {
    // Status must remain readable while discovery or file submission is pending.
    private val sendMutex = Mutex()
    private val bundleMutex = Mutex()
    private val prefs = context.getSharedPreferences("watch-updater", 0)
    private var lastVersionRequest = 0L
    private var bundle: Pair<File, WatchUpdate.Apk>? = null
    private suspend fun bundled(): Pair<File, WatchUpdate.Apk> = bundleMutex.withLock {
        bundle?.let { return@withLock it }
        val file = File(context.cacheDir, "bundled-watch.apk")
        context.assets.open("watch-update.apk").use { input -> file.outputStream().use { input.copyTo(it) } }
        (file to WatchUpdate.inspect(context, file, newer = false)).also { bundle = it }
    }
    private fun progress(message: String) { prefs.edit().putString("message", message).apply() }
    private suspend fun readReply(target: String, request: String): String? {
        val items = withTimeoutOrNull(2000) { Wearable.getDataClient(context).dataItems.await() } ?: return null
        var reply: String? = null
        var latest = Long.MIN_VALUE
        try { for (item in items) if (item.uri.path == WatchUpdate.STATUS) {
            val data = DataMapItem.fromDataItem(item).dataMap
            if (UpdateDelivery.matchesReply(target, request, item.uri.host, data.getString("request")) && data.getLong("at") > latest) {
                latest = data.getLong("at"); reply = data.getString("message")
            }
        } } finally { items.release() }
        return reply
    }
    suspend fun installedVersion(): String? {
        // Keep reports tied to the node that sent them; never infer installation from an APK offer.
        return runCatching {
            val nodes = withTimeout(3000) {
                Wearable.getCapabilityClient(context).getCapability("gym_access_watch_v1", CapabilityClient.FILTER_REACHABLE).await().nodes
            }
            val target = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
            val now = System.currentTimeMillis()
            if (target != null && now - lastVersionRequest >= 30000) {
                lastVersionRequest = now
                Wearable.getMessageClient(context).sendMessage(target.id, "/gymaccess/v1/watch-version-request", byteArrayOf())
            }
            val items = withTimeout(3000) { Wearable.getDataClient(context).dataItems.await() }
            try {
                val reports = items.filter { it.uri.path == "/gymaccess/v1/watch-version" && (target == null || it.uri.host == target.id) }
                val newest = reports.maxByOrNull { DataMapItem.fromDataItem(it).dataMap.getLong("reportedAt") }
                newest?.let { DataMapItem.fromDataItem(it).dataMap.getString("name") }
            } finally { items.release() }
        }.getOrNull()
    }
    suspend fun status(): Pair<String, String> {
        val (_, info) = bundled()
        val request = prefs.getString("request", "")!!
        val target = prefs.getString("target", "")!!
        var message = prefs.getString("message", "Open PG Access on your watch, then tap Update watch.")!!
        if (request.isNotEmpty() && target.isNotEmpty()) {
            val reply = runCatching { readReply(target, request) }.getOrNull()
            if (reply != null) {
                message = reply
            } else {
                val sentAt = prefs.getLong("sentAt", 0)
                if (sentAt > 0 && System.currentTimeMillis() - sentAt >= 45000) {
                    message = "The update was queued, but the watch has not acknowledged it yet. Keep PG Access open on the watch. If nothing appears after a few minutes, retry."
                }
            }
        }
        return info.name to message
    }
    suspend fun send() = sendMutex.withLock {
        prefs.edit().remove("request").remove("target").remove("sentAt").apply()
        try {
            val (file, info) = bundled()
            val link = object : UpdateDelivery.Link {
                override suspend fun discover(): String {
                    val target = UpdateDiscovery.find(
                        advertised = {
                            withTimeoutOrNull(3000) {
                                Wearable.getCapabilityClient(context).getCapability(WatchUpdate.CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                                    .await().nodes.map { UpdateDiscovery.Candidate(it.id, it.isNearby) }
                            } ?: emptyList()
                        },
                        connected = {
                            progress("Updater advertisement unavailable. Checking the connected watch directly...")
                            withTimeout(10000) {
                                Wearable.getNodeClient(context).connectedNodes.await()
                                    .map { UpdateDiscovery.Candidate(it.id, it.isNearby) }
                            }
                        },
                    )
                    return target.id
                }
                override suspend fun probe(target: String, request: String) {
                    // 0.1.4 checks version before requiring an APK. Version zero elicits
                    // its normal status reply without transferring a file or changing the QR.
                    val data = PutDataMapRequest.create(WatchUpdate.OFFER).apply {
                        dataMap.putString("request", request); dataMap.putString("target", target)
                        dataMap.putLong("version", 0)
                    }.asPutDataRequest().setUrgent()
                    withTimeout(10000) { Wearable.getDataClient(context).putDataItem(data).await() }
                }
                override suspend fun reply(target: String, request: String): String? = withTimeoutOrNull(20000) {
                    while (currentCoroutineContext().isActive) {
                        readReply(target, request)?.let { return@withTimeoutOrNull it }
                        delay(500)
                    }
                    null
                }
                override suspend fun upload(target: String, request: String) {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        val data = PutDataMapRequest.create(WatchUpdate.OFFER).apply {
                            dataMap.putString("request", request); dataMap.putString("target", target)
                            dataMap.putLong("version", info.version); dataMap.putString("name", info.name)
                            dataMap.putString("sha256", WatchUpdate.sha256(file)); dataMap.putLong("bytes", file.length())
                            dataMap.putAsset("apk", Asset.createFromFd(descriptor))
                        }.asPutDataRequest().setUrgent()
                        withTimeout(60000) { Wearable.getDataClient(context).putDataItem(data).await() }
                    }
                    prefs.edit().putString("request", request).putString("target", target)
                        .putLong("sentAt", System.currentTimeMillis()).apply()
                }
            }
            UpdateDelivery(link, ::progress).send(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        } catch (e: Exception) {
            val message = if (e is TimeoutCancellationException) "Watch update timed out. Keep both apps open and retry."
                else if (e is CancellationException) "Watch update was interrupted. Please retry."
                else e.message ?: "Unable to send watch update. Please retry."
            progress(message)
            throw IllegalStateException(message, e)
        }
    }
}
