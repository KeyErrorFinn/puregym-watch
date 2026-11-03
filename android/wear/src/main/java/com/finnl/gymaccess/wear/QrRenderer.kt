package com.finnl.gymaccess.wear

import com.finnl.gymaccess.protocol.EntryQrRenderer

object QrRenderer {
    fun modules(payload: String) = EntryQrRenderer.modules(payload)
    // Only the outer white corners may meet the round bezel. Keep the complete
    // data square and at least three module widths of radial white clearance.
    fun maximumPixels(payload: String, width: Int, height: Int): Int {
        val grid = modules(payload).width
        val data = grid - 8
        val scale = kotlin.math.floor((minOf(width, height) - 2) / (data * kotlin.math.sqrt(2.0) + 6)).toInt()
        require(scale >= 1) { "Entry code does not fit on this screen" }
        return scale * grid
    }
    private fun originalSizes(payload: String, width: Int, height: Int): List<Int> {
        val grid = modules(payload).width
        val previousMaximum = maximumPixels(payload, width, height)
        val previousScale = previousMaximum / grid
        val normal = (minOf(3, previousScale)..previousScale).map { it * grid }
        // Explicit oversize modes: full one-pixel-per-module jumps, even when
        // the bitmap or QR pattern extends past the physical screen edge.
        val extra = (1..3).map { previousMaximum + grid * it }
        return (normal + extra).distinct().sortedDescending()
    }
    // Labels originally counted upwards from the smallest QR. Retain those exact sizes.
    fun sizes(payload: String, width: Int, height: Int) = originalSizes(payload, width, height).takeLast(5)
    fun legacySizeNumber(payload: String, width: Int, height: Int, index: Int) =
        (originalSizes(payload, width, height).size - index).coerceIn(1, sizes(payload, width, height).size)
    fun matrix(payload: String, size: Int) = EntryQrRenderer.matrix(payload, size)
}
