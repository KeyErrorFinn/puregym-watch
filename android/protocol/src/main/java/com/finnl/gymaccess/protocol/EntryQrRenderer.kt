package com.finnl.gymaccess.protocol

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.floor
import kotlin.math.sqrt

object EntryQrRenderer {
    private val hints = mapOf<EncodeHintType, Any>(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
        EncodeHintType.MARGIN to 4,
    )

    // Match the official app's default byte encoding, without an ECI header.
    // Entry payloads observed so far are ASCII. Refuse unsupported text rather than corrupt it.
    fun modules(payload: String): BitMatrix {
        require(payload.isNotEmpty() && payload.all { it.code <= 255 }) { "Unsupported entry code encoding" }
        return QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints)
    }

    fun maximumSquarePixels(width: Int, height: Int): Int =
        floor(minOf(width, height) / sqrt(2.0)).toInt()

    // Includes a four-module quiet zone and never resamples a module.
    fun matrix(payload: String, size: Int): BitMatrix {
        val source = modules(payload)
        val scale = size / source.width
        require(scale >= 1) { "Entry code does not fit on this screen" }
        return BitMatrix(source.width * scale).also { output ->
            for (y in 0 until source.height) for (x in 0 until source.width) {
                if (source[x, y]) output.setRegion(x * scale, y * scale, scale, scale)
            }
        }
    }
}
