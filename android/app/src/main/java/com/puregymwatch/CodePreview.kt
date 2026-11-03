package com.puregymwatch

import android.graphics.Bitmap
import android.util.Base64
import com.finnl.gymaccess.protocol.EntryCode
import com.finnl.gymaccess.protocol.EntryQrRenderer
import java.io.ByteArrayOutputStream

/** Rendering only: never retrieves or refreshes a credential. */
object CodePreview {
    private var lastPayload: String? = null
    private var lastLimit = 0
    private var lastImage: Pair<String, Int>? = null

    @Synchronized fun image(code: EntryCode, maximumPixels: Int): Pair<String, Int> {
        val limit = maximumPixels.coerceIn(120, 1600)
        lastImage?.takeIf { lastPayload == code.payload && lastLimit == limit }?.let { return it }
        val matrix = EntryQrRenderer.matrix(code.payload, limit)
        val edge = matrix.width
        val bitmap = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        try {
            val pixels = IntArray(edge * edge) { i -> if (matrix[i % edge, i / edge]) -16777216 else -1 }
            bitmap.setPixels(pixels, 0, edge, 0, 0, edge, edge)
            val bytes = ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
            val result = "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP) to edge
            lastPayload = code.payload; lastLimit = limit; lastImage = result
            return result
        } finally { bitmap.recycle() }
    }

    @Synchronized fun clear() { lastPayload = null; lastImage = null; lastLimit = 0 }
}
