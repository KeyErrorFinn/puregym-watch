package com.puregymwatch

import android.content.Context
import com.finnl.gymaccess.protocol.WatchSettings
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

class PhoneSettings private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("watch-display-settings", 0)
    private val mutex = Mutex()
    fun current() = WatchSettings(prefs.getInt("size", 3), prefs.getInt("closeSeconds", 30), prefs.getBoolean("closeWarning", false))
    suspend fun save(value: WatchSettings) = mutex.withLock {
        val revision = maxOf(System.currentTimeMillis(), prefs.getLong("revision", 0) + 1)
        check(prefs.edit().putInt("size", value.size).putInt("closeSeconds", value.closeSeconds).putBoolean("closeWarning", value.closeWarning).putLong("revision", revision).commit())
        publish(value, revision)
    }
    fun revision() = prefs.getLong("revision", 0)
    suspend fun sync() = mutex.withLock { publish(current(), prefs.getLong("revision", 0)) }
    private suspend fun publish(value: WatchSettings, revision: Long) {
        // Local settings are retained if offline; retry when the watch asks or the phone UI reopens.
        runCatching {
            val request = PutDataMapRequest.create(WatchSettings.PATH).apply {
                dataMap.putString("json", value.json().toString()); dataMap.putLong("revision", revision)
            }.asPutDataRequest().setUrgent()
            withTimeout(5000) { Wearable.getDataClient(app).putDataItem(request).await() }
        }
    }
    companion object {
        @Volatile private var instance: PhoneSettings? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: PhoneSettings(context).also { instance = it } }
    }
}
