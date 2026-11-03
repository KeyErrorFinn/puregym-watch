package com.finnl.gymaccess.wear

import com.google.zxing.*
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.*
import org.junit.Test

class QrRendererTest {
    // Synthetic mixed-case byte-mode data, with the same QR module density as the supplied official screenshot.
    private val payload = "synthetic-entry-" + "aB012_-".repeat(8)

    private fun decode(matrix: BitMatrix): Result {
        val edge = matrix.width
        val pixels = IntArray(edge * edge) { i -> if (matrix[i % edge, i / edge]) -16777216 else -1 }
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(edge, edge, pixels))))
    }

    @Test fun matchesOfficialByteModeWithoutEci() {
        val matrix = QrRenderer.matrix(payload, 305)
        val result = decode(matrix)
        assertEquals(payload, result.text)
        assertEquals("Byte data must be the first segment, rather than an ECI header", 4, (result.rawBytes[0].toInt() and 255) ushr 4)
        assertEquals(41, QrRenderer.modules(payload).width) // 33 data modules + 4 white modules on each side.
    }

    @Test fun allModulesUseWholePhysicalPixelsAtBothWatchSizes() {
        val source = QrRenderer.modules(payload)
        for (screen in listOf(432, 480)) {
            val limit = QrRenderer.maximumPixels(payload, screen, screen)
            val rendered = QrRenderer.matrix(payload, limit)
            val scale = rendered.width / source.width
            assertEquals(source.width * scale, rendered.width)
            assertTrue(rendered.width <= limit)
            val previousScale = com.finnl.gymaccess.protocol.EntryQrRenderer.maximumSquarePixels(screen, screen) / source.width
            assertEquals(previousScale + 1, scale)
            assertTrue((source.width - 8) * scale / kotlin.math.sqrt(2.0) + 3 * scale <= screen / 2.0 - 1)
            for (y in 0 until rendered.height) for (x in 0 until rendered.width) {
                assertEquals(source[x / scale, y / scale], rendered[x, y])
            }
            assertEquals(payload, decode(rendered).text)
        }
    }

    @Test fun adjustableSizesStayDistinctSharpAndPreserveCredential() {
        val source = QrRenderer.modules(payload)
        for (screen in listOf(432, 480)) {
            val previousMaximum = QrRenderer.maximumPixels(payload, screen, screen)
            val sizes = QrRenderer.sizes(payload, screen, screen)
            assertEquals(5, sizes.size)
            assertEquals((7 downTo 3).map { source.width * it }, sizes)
            assertTrue(sizes.all { it <= previousMaximum })
            assertEquals(sizes.sortedDescending().distinct(), sizes)
            for (edge in sizes) {
                val matrix = QrRenderer.matrix(payload, edge)
                assertEquals(edge, matrix.width)
                for (y in 0 until edge) for (x in 0 until edge) {
                    val moduleX = ((x + 1) * source.width - source.width / 2 - 1) / edge
                    val moduleY = ((y + 1) * source.height - source.height / 2 - 1) / edge
                    assertEquals(source[moduleX, moduleY], matrix[x, y])
                }
                assertEquals(payload, decode(matrix).text)
            }
        }
    }

    @Test fun retainedSizeFourAndSavedSelectionKeepTheirOriginalDimensions() {
        val grid = QrRenderer.modules(payload).width
        for (screen in listOf(432, 480)) {
            val oldCount = if (screen == 432) 9 else 10
            val sizes = QrRenderer.sizes(payload, screen, screen)
            assertEquals(6 * grid, sizes[sizes.size - 4])
            for (number in 1..5) {
                assertEquals(number, QrRenderer.legacySizeNumber(payload, screen, screen, oldCount - number))
            }
            assertEquals(5, QrRenderer.legacySizeNumber(payload, screen, screen, 0))
        }
    }

    @Test fun preservesFourModuleWhiteBorder() {
        val source = QrRenderer.modules(payload)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            if (x < 4 || y < 4 || x >= source.width - 4 || y >= source.height - 4) assertFalse(source[x, y])
        }
    }

    @Test fun latinOnePayloadIsPreservedWithoutAnEncodingMarker() {
        val result = decode(QrRenderer.matrix("synthetic-caf\u00e9-entry", 305))
        assertEquals("synthetic-caf\u00e9-entry", result.text)
        assertEquals(4, (result.rawBytes[0].toInt() and 255) ushr 4)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsTextThatCannotBeRepresentedSafely() {
        QrRenderer.matrix("synthetic-\u20ac-entry", 305)
    }
}
