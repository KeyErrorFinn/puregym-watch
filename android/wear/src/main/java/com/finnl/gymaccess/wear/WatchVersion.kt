package com.finnl.gymaccess.wear

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

object WatchVersion {
    const val PATH = "/gymaccess/v1/watch-version"
    const val REQUEST = "/gymaccess/v1/watch-version-request"
    fun publish(context: Context) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val request = PutDataMapRequest.create(PATH).apply {
            dataMap.putString("name", info.versionName ?: info.longVersionCode.toString())
            dataMap.putLong("version", info.longVersionCode)
            dataMap.putLong("reportedAt", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request)
    }
}

