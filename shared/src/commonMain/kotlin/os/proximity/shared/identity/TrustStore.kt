package os.proximity.shared.identity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import os.proximity.shared.guardrail.TrustState

/**
 * Remembers which peers the user has personally vouched for.
 *
 * Key pinning is implicit rather than a separate mechanism: a device ID is
 * derived from its public key, so a peer presenting a different key
 * necessarily presents a different device ID and simply reads as a stranger.
 * There is no "same identity, new key" case to mishandle.
 */
interface TrustStore {

    val verifiedDeviceIds: StateFlow<Set<String>>

    fun trustStateOf(deviceId: String): TrustState

    /** Records that the user confirmed this peer out of band (fingerprint/QR). */
    suspend fun markVerified(deviceId: String)

    suspend fun revokeVerification(deviceId: String)
}

class InMemoryTrustStore : TrustStore {

    private val verified = MutableStateFlow<Set<String>>(emptySet())
    override val verifiedDeviceIds: StateFlow<Set<String>> = verified

    override fun trustStateOf(deviceId: String): TrustState =
        if (verified.value.contains(deviceId)) TrustState.VERIFIED else TrustState.UNVERIFIED

    override suspend fun markVerified(deviceId: String) {
        verified.value = verified.value + deviceId
    }

    override suspend fun revokeVerification(deviceId: String) {
        verified.value = verified.value - deviceId
    }
}
