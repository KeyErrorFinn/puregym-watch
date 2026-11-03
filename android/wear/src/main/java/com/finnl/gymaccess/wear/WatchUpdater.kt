package com.finnl.gymaccess.wear

import android.content.Context
import com.finnl.gymaccess.protocol.WatchUpdate
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.io.File

data class UpdateState(val ready: Boolean = false, val message: String = "", val name: String = "")
class WatchUpdater private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val prefs = context.getSharedPreferences("watch-update", 0)
    private val directory = File(context.filesDir, "updates").apply { mkdirs() }
    val apk = File(directory, "watch.apk")
    val state = MutableStateFlow(UpdateState())
    fun receive(data: DataMap) { scope.launch { mutex.withLock { receiveLocked(data) } } }
    private suspend fun report(request: String, message: String) {
        state.value = state.value.copy(message = message)
        runCatching {
            val item = PutDataMapRequest.create(WatchUpdate.STATUS).apply {
                dataMap.putString("request", request); dataMap.putString("message", message)
                dataMap.putLong("at", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(item).await()
        }
    }
    private suspend fun receiveLocked(data: DataMap) {
        val request = data.getString("request") ?: return
        val temp = File(directory, "incoming.apk")
        try {
            if (data.getString("target") != Wearable.getNodeClient(context).localNode.await().id) return
            val installed = context.packageManager.getPackageInfo(context.packageName, 0)
            if (data.getLong("version") <= installed.longVersionCode) {
                if (apk.exists() && runCatching { WatchUpdate.inspect(context, apk) }.isFailure) {
                    apk.delete(); state.value = UpdateState()
                }
                report(request, "Watch is up to date (${installed.versionName}).")
                return
            }
            val staged = if (apk.exists()) runCatching { WatchUpdate.inspect(context, apk) }.getOrNull() else null
            if (staged != null && staged.version > data.getLong("version")) {
                report(request, "A newer update is already ready on the watch.")
                return
            }
            if (prefs.getString("request", null) == request && staged != null) {
                val info = WatchUpdate.inspect(context, apk)
                state.value = UpdateState(true, "Update ready. Open PG Access on your watch and tap Install.", info.name)
                report(request, state.value.message)
                return
            }
            require(data.getLong("bytes") in 1..WatchUpdate.MAX_BYTES) { "Invalid update size" }
            state.value = UpdateState(message = "Receiving watch update...")
            report(request, "Transferring watch update. Keep both devices nearby.")
            val asset = data.getAsset("apk") ?: error("Missing update file")
            withTimeout(180000) {
                val response = Wearable.getDataClient(context).getFdForAsset(asset).await()
                try {
                    val input = response.inputStream ?: error("Update transfer unavailable")
                    input.use { source -> temp.outputStream().use { destination ->
                        val buffer = ByteArray(65536); var total = 0L
                        while (true) { val count = source.read(buffer); if (count < 0) break
                            total += count; require(total <= WatchUpdate.MAX_BYTES) { "Update is too large" }
                            destination.write(buffer, 0, count)
                        }
                    } }
                } finally { response.release() }
            }
            val info = WatchUpdate.validateTransfer(context, temp, data.getLong("bytes"), data.getString("sha256") ?: "", data.getLong("version"))
            java.nio.file.Files.move(temp.toPath(), apk.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            prefs.edit().putString("request", request).apply()
            state.value = UpdateState(true, "Update ready. Tap Install on your watch.", info.name)
            report(request, "Update ready. Open PG Access on your watch and tap Install.")
        } catch (_: Exception) {
            temp.delete()
            state.value = UpdateState(message = "Update failed. Tap Update watch on your phone to retry.")
            report(request, state.value.message)
        }
    }
    suspend fun load() {
        mutex.withLock {
            if (apk.exists()) {
                runCatching { WatchUpdate.inspect(context, apk) }
                    .onSuccess { state.value = UpdateState(true, "Update ready. Tap Install on your watch.", it.name) }
                    .onFailure { apk.delete(); state.value = UpdateState() }
            }
        }
        runCatching {
            val items = Wearable.getDataClient(context).dataItems.await()
            try { for (item in items) if (item.uri.path == WatchUpdate.OFFER) receive(DataMapItem.fromDataItem(item).dataMap) }
            finally { items.release() }
        }
    }
    companion object {
        @Volatile private var instance: WatchUpdater? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: WatchUpdater(context.applicationContext).also { instance = it }
        }
    }
}
