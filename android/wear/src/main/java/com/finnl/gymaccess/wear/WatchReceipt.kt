package com.finnl.gymaccess.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.PutDataMapRequest

object WatchReceipt {
    fun send(context: Context, kind: String, revision: Long) {
        val request = PutDataMapRequest.create("/gymaccess/v1/receipt/$kind").apply {
            dataMap.putLong("revision", revision)
            dataMap.putLong("receivedAt", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request)
    }
}
