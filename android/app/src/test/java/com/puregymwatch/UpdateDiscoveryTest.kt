package com.puregymwatch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UpdateDiscoveryTest {
    @Test fun missingAdvertisementFallsBackToConnectedWatch() = runBlocking {
        assertEquals("classic", UpdateDiscovery.find({ emptyList() }, { listOf(UpdateDiscovery.Candidate("classic", true)) }).id)
    }
    @Test fun failedCapabilityLookupStillChecksConnectedWatch() = runBlocking {
        assertEquals("classic", UpdateDiscovery.find({ error("Capability unavailable") }, { listOf(UpdateDiscovery.Candidate("classic", true)) }).id)
    }
    @Test fun advertisedUpdaterDoesNotNeedFallbackLookup() = runBlocking {
        assertEquals("advertised", UpdateDiscovery.find({ listOf(UpdateDiscovery.Candidate("advertised", true)) }, { error("Fallback must not be called") }).id)
    }
    @Test fun fallbackPrefersTheNearbyWatch() = runBlocking {
        assertEquals("nearby", UpdateDiscovery.find({ emptyList() }, { listOf(UpdateDiscovery.Candidate("remote", false), UpdateDiscovery.Candidate("nearby", true)) }).id)
    }
    @Test fun noConnectedWatchHasADistinctConnectionError() = runBlocking {
        val error = runCatching { UpdateDiscovery.find({ emptyList() }, { emptyList() }) }.exceptionOrNull()
        assertTrue(error?.message?.contains("No watch is connected") == true)
    }
    @Test fun cancellationIsNotMistakenForMissingCapability() = runBlocking {
        val error = runCatching { UpdateDiscovery.find({ throw CancellationException("Cancelled") }, { error("Must not fall back") }) }.exceptionOrNull()
        assertTrue(error is CancellationException)
    }
}
