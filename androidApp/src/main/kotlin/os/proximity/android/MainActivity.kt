package os.proximity.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import os.proximity.android.mesh.BleMeshTransport
import os.proximity.shared.guardrail.ActionType
import os.proximity.shared.guardrail.DefaultGuardrailEngine
import os.proximity.shared.guardrail.GuardrailDecision
import os.proximity.shared.guardrail.GuardrailRequest
import os.proximity.shared.guardrail.InMemoryAuditLog
import os.proximity.shared.guardrail.PeerContext
import os.proximity.shared.guardrail.RequestDirection
import os.proximity.shared.guardrail.TrustState
import os.proximity.shared.identity.AndroidDeviceIdentityProvider
import os.proximity.shared.mesh.DiscoveredPeer

class MainActivity : ComponentActivity() {

    private val auditLog = InMemoryAuditLog()
    private val guardrailEngine = DefaultGuardrailEngine(auditLog)
    private lateinit var transport: BleMeshTransport
    private lateinit var identityProvider: AndroidDeviceIdentityProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        transport = BleMeshTransport(applicationContext)
        identityProvider = AndroidDeviceIdentityProvider(applicationContext)

        setContent {
            ProximityOsApp(
                transport = transport,
                guardrailEngine = guardrailEngine,
                requiredPermissions = requiredBlePermissions()
            )
        }
    }
}

private fun requiredBlePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

@Composable
private fun ProximityOsApp(
    transport: BleMeshTransport,
    guardrailEngine: DefaultGuardrailEngine,
    requiredPermissions: Array<String>
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val context = LocalContext.current
            var permissionsGranted by remember {
                mutableStateOf(
                    requiredPermissions.all {
                        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                    }
                )
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { results ->
                permissionsGranted = results.values.all { it }
            }

            if (!permissionsGranted) {
                PermissionRequestScreen(onRequestClick = { permissionLauncher.launch(requiredPermissions) })
            } else {
                DiscoveryScreen(transport = transport, guardrailEngine = guardrailEngine)
            }
        }
    }
}

@Composable
private fun PermissionRequestScreen(onRequestClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Proximity OS", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "To discover nearby devices, Proximity OS needs Bluetooth permission. " +
                "Nothing is shared with anyone until you explicitly approve a connection.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequestClick) { Text("Grant permission") }
    }
}

@Composable
private fun DiscoveryScreen(transport: BleMeshTransport, guardrailEngine: DefaultGuardrailEngine) {
    val scope = rememberCoroutineScope()
    var isScanning by remember { mutableStateOf(false) }
    val peers by transport.discoveredPeers.collectAsState(initial = emptyList())
    var pendingConnectPeer by remember { mutableStateOf<DiscoveredPeer?>(null) }
    var pendingReason by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Proximity OS", style = MaterialTheme.typography.headlineMedium)
        Text("Phase 1 — nearby device discovery.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))

        Button(onClick = {
            if (isScanning) {
                transport.stopDiscovery()
                isScanning = false
            } else {
                scope.launch {
                    val decision = guardrailEngine.evaluate(
                        GuardrailRequest(RequestDirection.OUTBOUND, ActionType.DISCOVER_PEER)
                    )
                    if (decision is GuardrailDecision.Allow) {
                        transport.startDiscovery()
                        isScanning = true
                    } else {
                        statusMessage = decision.reason
                    }
                }
            }
        }) {
            Text(if (isScanning) "Stop scanning" else "Start scanning")
        }

        statusMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(16.dp))

        if (peers.isEmpty()) {
            Text(
                if (isScanning) {
                    "Looking for nearby devices…"
                } else {
                    "No devices yet. Start scanning to look for nearby Proximity OS devices."
                },
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            LazyColumn {
                items(peers, key = { it.transportAddress }) { peer ->
                    ListItem(
                        headlineContent = { Text(peer.displayName ?: peer.transportAddress) },
                        supportingContent = { Text("RSSI ${peer.rssi ?: "?"}") },
                        modifier = Modifier.clickable {
                            scope.launch {
                                val decision = guardrailEngine.evaluate(
                                    GuardrailRequest(
                                        direction = RequestDirection.OUTBOUND,
                                        actionType = ActionType.CONNECT_PEER,
                                        peer = PeerContext(peer.transportAddress, TrustState.UNVERIFIED)
                                    )
                                )
                                when (decision) {
                                    is GuardrailDecision.AskUser -> {
                                        pendingConnectPeer = peer
                                        pendingReason = decision.reason
                                    }

                                    is GuardrailDecision.Allow -> {
                                        statusMessage = connectAndDescribe(transport, peer)
                                    }

                                    is GuardrailDecision.Deny -> {
                                        statusMessage = "Blocked: ${decision.reason}"
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    pendingConnectPeer?.let { peer ->
        AlertDialog(
            onDismissRequest = { pendingConnectPeer = null },
            title = { Text("Connect to device?") },
            text = { Text(pendingReason) },
            confirmButton = {
                TextButton(onClick = {
                    pendingConnectPeer = null
                    scope.launch {
                        statusMessage = connectAndDescribe(transport, peer)
                    }
                }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = { pendingConnectPeer = null }) { Text("Deny") }
            }
        )
    }
}

private suspend fun connectAndDescribe(transport: BleMeshTransport, peer: DiscoveredPeer): String {
    val label = peer.displayName ?: peer.transportAddress
    return if (transport.connect(peer.transportAddress)) "Connected to $label" else "Could not connect to $label"
}
