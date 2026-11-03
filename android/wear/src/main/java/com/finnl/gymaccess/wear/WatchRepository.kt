package com.finnl.gymaccess.wear

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.finnl.gymaccess.protocol.*
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.util.UUID

data class WatchState(val code: EntryCode? = null, val message: String = "Connecting to phone...", val loading: Boolean = false, val signedOut: Boolean = false)
class WatchRepository private constructor(private val context: Context) {
    private val storage = SecureStore(context, "gym-watch-code-v1")
    private var requestId: String? = null
    private var requestedAt = 0L
    private var updateAt = 0L
    private var nextRequestAt = 0L
    val state = MutableStateFlow(WatchState())
    @Synchronized fun accept(value: JSONObject, direct: Boolean = false, timestamp: Long = System.currentTimeMillis()) {
        runCatching {
            Wire.check(value)
            if (direct && value.optString("requestId") != requestId) return
            val revision = value.optLong("revision", timestamp)
            if (revision < updateAt) return
            updateAt = revision
            val status = value.getString("status")
            when (status) {
                "ready", "cached" -> {
                    val code = EntryCode.fromWire(value.getJSONObject("code"))
                    require(code.valid())
                    storage.write(code.json().toString())
                    nextRequestAt = if (status == "cached") System.currentTimeMillis() + 60000 else maxOf(code.refreshAt, System.currentTimeMillis() + 5000)
                    state.value = WatchState(code, if (status == "cached") "Saved code" else "Scan to enter")
                }
                "signed_out", "sign_in_required", "access_denied" -> {
                    storage.clear(); requestId = null
                    state.value = WatchState(message = value.optString("message", "Sign in on your phone"), signedOut = true)
                }
                else -> {
                    val code = state.value.code?.takeIf { it.valid() }
                    state.value = WatchState(code, if (code != null) "Saved code" else value.optString("message", "Please retry"))
                }
            }
            WatchReceipt.send(context, "code", revision)
        }.onFailure { state.value = WatchState(message = "Unable to read entry code. Update both apps and retry.") }
    }
    suspend fun load() {
        WatchVersion.publish(context)
        try {
            val items = Wearable.getDataClient(context).dataItems.await()
            try { for (item in items) if (item.uri.path == Wire.STATE) {
                val map = DataMapItem.fromDataItem(item).dataMap
                map.getString("json")?.let { accept(JSONObject(it), timestamp = map.getLong("updatedAt")) }
            } } finally { items.release() }
        } catch (_: Exception) { /* Continue with locally cached data. */ }
        if (state.value.code == null && !state.value.signedOut) {
            val cached = storage.read()?.let { runCatching { EntryCode.fromWire(JSONObject(it)) }.getOrNull() }?.takeIf { it.valid() }
            state.value = WatchState(cached, if (cached != null) "Saved code" else "Connect your phone")
        }
    }
    suspend fun request(openPhone: Boolean = false) {
        if (state.value.loading && System.currentTimeMillis() - requestedAt < 25000) return
        requestedAt = System.currentTimeMillis()
        nextRequestAt = requestedAt + 60000
        requestId = UUID.randomUUID().toString()
        if (!openPhone) state.value = state.value.copy(loading = true)
        try {
            val nodes = Wearable.getCapabilityClient(context).getCapability(Wire.PHONE, CapabilityClient.FILTER_REACHABLE).await().nodes
            val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
            if (node == null) {
                state.value = state.value.copy(loading = false, message = if (state.value.code?.valid() == true) "Saved code - phone offline" else "Connect your phone and open PG Access")
                return
            }
            if (openPhone) {
                val intent = Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_BROWSABLE).setData(Uri.parse("gymaccess:/setup"))
                val launch = RemoteActivityHelper(context).startRemoteActivity(intent, node.id)
                launch.addListener({
                    runCatching { launch.get() }.onFailure {
                        state.value = state.value.copy(message = "Open PG Access on your phone", loading = false)
                    }
                }, context.mainExecutor)
                state.value = state.value.copy(message = "Continue on your phone", loading = false)
            } else {
                val json = JSONObject().put("version", Wire.VERSION).put("requestId", requestId)
                Wearable.getMessageClient(context).sendMessage(node.id, Wire.REQUEST, json.toString().toByteArray()).await()
            }
        } catch (_: Exception) { state.value = state.value.copy(loading = false, message = if (state.value.code?.valid() == true) "Saved code - phone offline" else "Phone unavailable. Please retry.") }
    }
    fun tick() {
        val current = state.value
        if (current.code != null && !current.code.valid()) {
            storage.clear(); state.value = current.copy(code = null, message = "Code expired. Connect your phone.")
        }
        if (state.value.loading && System.currentTimeMillis() - requestedAt >= 25000) state.value = state.value.copy(loading = false, message = "Phone did not respond. Please retry.")
    }
    fun shouldRefresh(now: Long = System.currentTimeMillis()) =
        !state.value.loading && !state.value.signedOut && now >= nextRequestAt && (state.value.code?.refreshAt ?: 0) <= now
    companion object {
        @Volatile private var instance: WatchRepository? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: WatchRepository(context.applicationContext).also { instance = it } }
    }
}
