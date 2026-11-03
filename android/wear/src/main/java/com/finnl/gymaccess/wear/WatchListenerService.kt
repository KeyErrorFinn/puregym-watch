package com.finnl.gymaccess.wear

import com.finnl.gymaccess.protocol.WatchSettings
import com.finnl.gymaccess.protocol.Wire
import com.finnl.gymaccess.protocol.WatchUpdate
import com.google.android.gms.wearable.*
import org.json.JSONObject

class WatchListenerService: WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == WatchVersion.REQUEST) WatchVersion.publish(this)
        if (event.path == Wire.RESPONSE) runCatching { WatchRepository.get(this).accept(JSONObject(event.data.toString(Charsets.UTF_8)), direct = true) }
    }
    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == WatchSettings.PATH) {
            val map = DataMapItem.fromDataItem(event.dataItem).dataMap
            map.getString("json")?.let { DisplaySettings.get(this).accept(it, map.getLong("revision")) }
        }
        for (event in events) if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == WatchUpdate.OFFER) {
            WatchUpdater.get(this).receive(DataMapItem.fromDataItem(event.dataItem).dataMap)
        }
        for (event in events) if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == Wire.STATE) {
            val map = DataMapItem.fromDataItem(event.dataItem).dataMap
            map.getString("json")?.let { runCatching { WatchRepository.get(this).accept(JSONObject(it), timestamp = map.getLong("updatedAt")) } }
        }
    }
}
