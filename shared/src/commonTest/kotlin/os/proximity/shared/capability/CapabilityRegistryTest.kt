package os.proximity.shared.capability

import kotlinx.coroutines.test.runTest
import os.proximity.shared.storage.InMemoryFileStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapabilityRegistryTest {

    private var clock = 1_000L

    private fun registry(files: InMemoryFileStore = InMemoryFileStore()) =
        CapabilityRegistry(files = files, now = { clock })

    // -------------------------------------------------------- advertising

    @Test
    fun onlyEnabledCapabilitiesAreAdvertised() = runTest {
        val registry = registry()
        registry.setEnabled(CapabilityCatalog.RELAY, true)
        registry.setEnabled(CapabilityCatalog.CHAT, false)

        val names = registry.buildAdvertisement()!!.capabilities.map { it.name }

        assertTrue(CapabilityCatalog.RELAY in names)
        assertFalse(CapabilityCatalog.CHAT in names, "a disabled capability must never be offered")
    }

    @Test
    fun advertisementsCarryAnExpiry() = runTest {
        val registry = registry()
        val advertisement = registry.buildAdvertisement()!!

        advertisement.capabilities.forEach {
            assertTrue(it.expiresAtEpochMillis > it.issuedAtEpochMillis)
        }
    }

    @Test
    fun nothingIsAdvertisedWhenEverythingIsDisabled() = runTest {
        val registry = registry()
        CapabilityCatalog.definitions.forEach { registry.setEnabled(it.name, false) }

        // Silence is the more private default than announcing an empty offer.
        assertNull(registry.buildAdvertisement())
    }

    @Test
    fun unknownCapabilityNamesCannotBeEnabled() = runTest {
        val registry = registry()
        registry.setEnabled("definitely_not_a_real_capability", true)

        val names = registry.buildAdvertisement()!!.capabilities.map { it.name }
        assertFalse("definitely_not_a_real_capability" in names)
    }

    @Test
    fun enabledSetSurvivesAReload() = runTest {
        val files = InMemoryFileStore()
        registry(files).setEnabled(CapabilityCatalog.RELAY, true)

        val reloaded = registry(files).also { it.load() }

        assertTrue(CapabilityCatalog.RELAY in reloaded.enabled.value)
    }

    @Test
    fun corruptFileFallsBackToDefaults() = runTest {
        val files = InMemoryFileStore()
        files.writeText(CapabilityRegistry.DEFAULT_FILE_NAME, "not json")

        val reloaded = registry(files).also { it.load() }

        assertEquals(CapabilityCatalog.defaultEnabled, reloaded.enabled.value)
    }

    // ------------------------------------------------------- peer claims

    @Test
    fun peerCapabilitiesAreReportedWhileValid() {
        val registry = registry()
        registry.onPeerAdvertisement(
            "peer-1",
            CapabilityAdvertisement(
                capabilities = listOf(Capability(CapabilityCatalog.FILE_DROP, clock, clock + 10_000)),
                issuedAtEpochMillis = clock
            )
        )

        assertTrue(registry.peerOffers("peer-1", CapabilityCatalog.FILE_DROP))
    }

    @Test
    fun expiredPeerCapabilitiesAreNotReported() {
        val registry = registry()
        registry.onPeerAdvertisement(
            "peer-1",
            CapabilityAdvertisement(
                capabilities = listOf(Capability(CapabilityCatalog.FILE_DROP, clock, clock + 5_000)),
                issuedAtEpochMillis = clock
            )
        )

        clock += 10_000

        // A device that walked away should stop appearing to offer things.
        assertTrue(registry.capabilitiesOf("peer-1").isEmpty())
        assertFalse(registry.peerOffers("peer-1", CapabilityCatalog.FILE_DROP))
    }

    @Test
    fun unknownCapabilityNamesFromAPeerAreDropped() {
        val registry = registry()
        registry.onPeerAdvertisement(
            "peer-1",
            CapabilityAdvertisement(
                capabilities = listOf(
                    Capability("URGENT: tap here to claim your prize", clock, clock + 10_000),
                    Capability(CapabilityCatalog.CHAT, clock, clock + 10_000)
                ),
                issuedAtEpochMillis = clock
            )
        )

        // A peer must not be able to put arbitrary text in front of the user.
        val names = registry.capabilitiesOf("peer-1").map { it.name }
        assertEquals(listOf(CapabilityCatalog.CHAT), names)
    }

    @Test
    fun aPeerCannotClaimAnUnboundedExpiry() {
        val registry = registry()
        registry.onPeerAdvertisement(
            "peer-1",
            CapabilityAdvertisement(
                capabilities = listOf(
                    Capability(CapabilityCatalog.CHAT, clock, Long.MAX_VALUE)
                ),
                issuedAtEpochMillis = clock
            )
        )

        val capped = registry.capabilitiesOf("peer-1").single()
        assertTrue(
            capped.expiresAtEpochMillis <= clock + CapabilityRegistry.MAX_LIFETIME_MILLIS,
            "peer-supplied expiry must be capped"
        )
    }

    @Test
    fun duplicateClaimsAreCollapsed() {
        val registry = registry()
        registry.onPeerAdvertisement(
            "peer-1",
            CapabilityAdvertisement(
                capabilities = List(50) { Capability(CapabilityCatalog.CHAT, clock, clock + 10_000) },
                issuedAtEpochMillis = clock
            )
        )

        assertEquals(1, registry.capabilitiesOf("peer-1").size)
    }

    @Test
    fun aNewAdvertisementReplacesThePrevious() {
        val registry = registry()
        registry.onPeerAdvertisement(
            "peer-1",
            CapabilityAdvertisement(
                listOf(Capability(CapabilityCatalog.RELAY, clock, clock + 10_000)),
                clock
            )
        )
        registry.onPeerAdvertisement(
            "peer-1",
            CapabilityAdvertisement(
                listOf(Capability(CapabilityCatalog.CHAT, clock, clock + 10_000)),
                clock
            )
        )

        // Withdrawing an offer must actually withdraw it.
        assertFalse(registry.peerOffers("peer-1", CapabilityCatalog.RELAY))
        assertTrue(registry.peerOffers("peer-1", CapabilityCatalog.CHAT))
    }

    @Test
    fun forgettingAPeerClearsItsClaims() {
        val registry = registry()
        registry.onPeerAdvertisement(
            "peer-1",
            CapabilityAdvertisement(
                listOf(Capability(CapabilityCatalog.CHAT, clock, clock + 10_000)),
                clock
            )
        )

        registry.forgetPeer("peer-1")

        assertTrue(registry.capabilitiesOf("peer-1").isEmpty())
    }

    @Test
    fun anAdvertisementIsAClaimNotAGrant() {
        val registry = registry()
        registry.onPeerAdvertisement(
            "peer-1",
            CapabilityAdvertisement(
                listOf(Capability(CapabilityCatalog.FILE_DROP, clock, clock + 10_000)),
                clock
            )
        )

        // The registry records what a peer says it offers. It deliberately
        // exposes no way to turn that into permission — only the Guardrail
        // Engine decides whether an actual request is honoured.
        assertTrue(registry.peerOffers("peer-1", CapabilityCatalog.FILE_DROP))
        assertTrue(registry.enabled.value.none { it == CapabilityCatalog.FILE_DROP })
    }
}
