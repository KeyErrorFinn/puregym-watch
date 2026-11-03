package com.puregymwatch

import kotlinx.coroutines.CancellationException

object UpdateDiscovery {
    data class Candidate(val id: String, val nearby: Boolean)
    suspend fun find(advertised: suspend () -> List<Candidate>, connected: suspend () -> List<Candidate>): Candidate {
        fun preferred(nodes: List<Candidate>) = nodes.firstOrNull { it.nearby } ?: nodes.firstOrNull()
        val capabilities = try { advertised() } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        preferred(capabilities)?.let { return it }
        return preferred(connected())
            ?: error("No watch is connected through Wear OS. Check the connection in Galaxy Wearable, open PG Access on the watch, then retry.")
    }
}
