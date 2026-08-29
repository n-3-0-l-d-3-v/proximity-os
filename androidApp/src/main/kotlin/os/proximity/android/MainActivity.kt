package os.proximity.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import os.proximity.android.data.AppSettings
import os.proximity.android.mesh.BleMeshTransport
import os.proximity.android.ui.ProximityApp
import os.proximity.android.ui.ProximityViewModel
import os.proximity.shared.crypto.AndroidCryptoPrimitives
import os.proximity.shared.guardrail.DefaultGuardrailEngine
import os.proximity.shared.guardrail.FileAuditLog
import os.proximity.shared.identity.FileTrustStore
import os.proximity.shared.identity.JcaSignatureVerifier
import os.proximity.shared.identity.KeystoreDeviceIdentityProvider
import os.proximity.shared.lists.SharedListRepository
import os.proximity.shared.storage.AndroidFileStore
import os.proximity.shared.mesh.MeshManager

/**
 * Composition root.
 *
 * Wiring lives here rather than in a DI framework: the graph is small, and
 * having it written out in one readable place makes it obvious that every
 * path from the transport to the UI goes through [MeshManager] and the
 * Guardrail Engine.
 */
class MainActivity : ComponentActivity() {

    private val meshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settings = AppSettings(applicationContext)
        val fileStore = AndroidFileStore(applicationContext)
        val auditLog = FileAuditLog(fileStore)
        val trustStore = FileTrustStore(fileStore)
        val guardrailEngine = DefaultGuardrailEngine(auditLog)
        val identityProvider = KeystoreDeviceIdentityProvider()
        val listRepository = SharedListRepository(
            files = fileStore,
            deviceId = { identityProvider.getOrCreateIdentity().deviceId },
            now = { System.currentTimeMillis() }
        )

        // Restore before the mesh can act: a peer must not be treated as a
        // stranger just because the load had not finished yet.
        meshScope.launch {
            trustStore.load()
            auditLog.load()
            listRepository.load()
        }

        val meshManager = MeshManager(
            transport = BleMeshTransport(applicationContext),
            primitives = AndroidCryptoPrimitives(),
            identityProvider = identityProvider,
            verifier = JcaSignatureVerifier(),
            guardrail = guardrailEngine,
            trustStore = trustStore,
            scope = meshScope,
            displayName = { settings.displayName.value },
            listSync = listRepository
        )

        val viewModel = ViewModelProvider(
            this,
            ProximityViewModel.Factory(
                settings = settings,
                engine = guardrailEngine,
                auditLog = auditLog,
                identityProvider = identityProvider,
                listRepository = listRepository,
                mesh = meshManager
            )
        )[ProximityViewModel::class.java]

        setContent {
            ProximityApp(
                viewModel = viewModel,
                requiredPermissions = requiredBlePermissions()
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        meshScope.cancel()
    }
}

/**
 * API 31 split Bluetooth into purpose-specific permissions. Below that,
 * scanning was gated behind location — which is exactly the conflation this
 * project dislikes, but it is what those OS versions require.
 */
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
