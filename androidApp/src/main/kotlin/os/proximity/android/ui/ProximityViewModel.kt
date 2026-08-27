package os.proximity.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import os.proximity.android.data.AppSettings
import os.proximity.shared.domain.Conversation
import os.proximity.shared.domain.Peer
import os.proximity.shared.guardrail.AuditLog
import os.proximity.shared.guardrail.AuditLogEntry
import os.proximity.shared.guardrail.DefaultGuardrailEngine
import os.proximity.shared.guardrail.PolicyCatalog
import os.proximity.shared.identity.DeviceIdentityProvider
import os.proximity.shared.mesh.MeshEvent
import os.proximity.shared.mesh.MeshManager

/**
 * Holds screen state and owns the lifetime of mesh work.
 *
 * Deliberately thin: policy lives in the Guardrail Engine and protocol
 * lives in `shared`, so this class only translates between those and
 * Compose state.
 */
class ProximityViewModel(
    private val settings: AppSettings,
    private val engine: DefaultGuardrailEngine,
    private val auditLog: AuditLog,
    private val identityProvider: DeviceIdentityProvider,
    val mesh: MeshManager
) : ViewModel() {

    val peers: StateFlow<List<Peer>> = mesh.peers
    val conversations: StateFlow<Map<String, Conversation>> = mesh.conversations
    val isScanning: StateFlow<Boolean> = mesh.isScanning
    val auditEntries: StateFlow<List<AuditLogEntry>> = auditLog.entries
    val displayName: StateFlow<String> = settings.displayName
    val hasOnboarded: StateFlow<Boolean> = settings.hasOnboarded
    val enabledPolicyIds: StateFlow<Set<String>> = settings.enabledPolicyIds

    /** A Guardrail "ask me" decision currently blocking the mesh. */
    var pendingDecision by mutableStateOf<MeshEvent.DecisionRequired?>(null)
        private set

    /** Transient message shown to the user (a block, or a status change). */
    var banner by mutableStateOf<String?>(null)
        private set

    /** This device's own verification code, for showing to another person. */
    var myFingerprint by mutableStateOf<String?>(null)
        private set

    init {
        mesh.start()
        applyEnabledPolicies()

        viewModelScope.launch {
            myFingerprint = runCatching { identityProvider.getOrCreateIdentity().fingerprint }
                .getOrNull()
        }

        viewModelScope.launch {
            mesh.events.collect { event ->
                when (event) {
                    is MeshEvent.DecisionRequired -> pendingDecision = event
                    is MeshEvent.Blocked -> banner = event.reason
                    is MeshEvent.Notice -> banner = event.text
                }
            }
        }

        viewModelScope.launch {
            settings.enabledPolicyIds.collect { applyEnabledPolicies(it) }
        }
    }

    // ------------------------------------------------------------- discovery

    fun toggleScanning() {
        viewModelScope.launch {
            if (isScanning.value) mesh.stopDiscovery() else mesh.startDiscovery()
        }
    }

    fun connectTo(peer: Peer) {
        viewModelScope.launch { mesh.connectTo(peer.transportAddress) }
    }

    fun disconnect(peer: Peer) = mesh.disconnect(peer.transportAddress)

    // ------------------------------------------------------------- decisions

    fun resolvePendingDecision(allow: Boolean) {
        val decision = pendingDecision ?: return
        pendingDecision = null
        viewModelScope.launch { mesh.resolveDecision(decision.decisionId, allow) }
    }

    fun dismissBanner() {
        banner = null
    }

    // ----------------------------------------------------------------- chat

    fun sendMessage(peerDeviceId: String, body: String) {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { mesh.sendChat(peerDeviceId, trimmed) }
    }

    // ---------------------------------------------------------------- trust

    fun markVerified(deviceId: String) {
        viewModelScope.launch { mesh.markVerified(deviceId) }
    }

    fun revokeVerification(deviceId: String) {
        viewModelScope.launch { mesh.revokeVerification(deviceId) }
    }

    // -------------------------------------------------------------- settings

    fun setDisplayName(name: String) = settings.setDisplayName(name)

    fun completeOnboarding() = settings.setOnboarded(true)

    fun setPolicyEnabled(id: String, enabled: Boolean) = settings.setPolicyEnabled(id, enabled)

    private fun applyEnabledPolicies(ids: Set<String> = settings.enabledPolicyIds.value) {
        viewModelScope.launch {
            // Rebuild from scratch rather than diffing: the rule set is small,
            // and a stale rule left behind would be a silent policy hole.
            PolicyCatalog.options.forEach { engine.removeRule(it.id) }
            PolicyCatalog.options
                .filter { it.id in ids }
                .forEach { engine.addRule(it.buildRule()) }
        }
    }

    class Factory(
        private val settings: AppSettings,
        private val engine: DefaultGuardrailEngine,
        private val auditLog: AuditLog,
        private val identityProvider: DeviceIdentityProvider,
        private val mesh: MeshManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ProximityViewModel(settings, engine, auditLog, identityProvider, mesh) as T
    }
}
