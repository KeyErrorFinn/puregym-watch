package com.puregymwatch

import com.finnl.gymaccess.protocol.Wire
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

class PhoneWearService: WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onDataChanged(events: DataEventBuffer) {
        val prefs = getSharedPreferences("watch-receipts", 0)
        for (event in events) if (event.type == DataEvent.TYPE_CHANGED) {
            val kind = when (event.dataItem.uri.path) {
                "/gymaccess/v1/receipt/code" -> "code"
                "/gymaccess/v1/receipt/settings" -> "settings"
                else -> continue
            }
            val map = DataMapItem.fromDataItem(event.dataItem).dataMap
            val revision = map.getLong("revision", -1)
            if (revision >= prefs.getLong(kind, -1)) prefs.edit().putLong(kind, revision).commit()
        }
    }
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != Wire.REQUEST && event.path != Wire.OPEN_PHONE) return
        scope.launch {
            val request = runCatching { JSONObject(event.data.toString(Charsets.UTF_8)).also { Wire.check(it) } }.getOrNull() ?: return@launch
            if (event.path == Wire.OPEN_PHONE) {
                // Android blocks unsolicited background activity launches. A notification opens the companion instead.
                // Setup opens through RemoteActivityHelper following a user tap on the watch.
                return@launch
            }
            PhoneSettings.get(this@PhoneWearService).sync()
            val response = runCatching { PhoneRepository.get(this@PhoneWearService).refresh() }
                .getOrElse { Wire.result("service_error", message = "Please retry on your phone") }
            response.put("requestId", request.optString("requestId"))
            runCatching { Wearable.getMessageClient(this@PhoneWearService).sendMessage(event.sourceNodeId, Wire.RESPONSE, response.toString().toByteArray()).await() }
        }
    }
    override fun onCapabilityChanged(info: CapabilityInfo) { scope.launch { PhoneRepository.get(this@PhoneWearService).syncPendingClear(); PhoneSettings.get(this@PhoneWearService).sync() } }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
