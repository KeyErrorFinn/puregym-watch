package com.finnl.gymaccess.wear

import android.content.Context
import com.finnl.gymaccess.protocol.WatchSettings
import com.google.android.gms.wearable.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

class DisplaySettings private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("watch-display-settings", 0)
    val state = MutableStateFlow(runCatching { WatchSettings(prefs.getInt("size", 3), prefs.getInt("closeSeconds", 30), prefs.getBoolean("closeWarning", false)) }.getOrDefault(WatchSettings()))
    @Synchronized fun accept(json: String, revision: Long) {
        runCatching {
            val value = WatchSettings.parse(JSONObject(json))
            if (revision < prefs.getLong("revision", -1)) return
            check(prefs.edit().putInt("size", value.size).putInt("closeSeconds", value.closeSeconds).putBoolean("closeWarning", value.closeWarning).putLong("revision", revision).commit())
            state.value = value
            WatchReceipt.send(app, "settings", revision)
        }
    }
    suspend fun load() {
        runCatching {
            val items = Wearable.getDataClient(app).dataItems.await()
            try { for (item in items) if (item.uri.path == WatchSettings.PATH) {
                val map = DataMapItem.fromDataItem(item).dataMap
                map.getString("json")?.let { accept(it, map.getLong("revision")) }
            } } finally { items.release() }
        }
    }
    companion object {
        @Volatile private var instance: DisplaySettings? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: DisplaySettings(context).also { instance = it } }
    }
}
