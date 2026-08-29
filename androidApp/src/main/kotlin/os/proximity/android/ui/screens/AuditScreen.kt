package os.proximity.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import os.proximity.android.ui.components.DecisionBadge
import os.proximity.android.ui.components.EmptyState
import os.proximity.android.ui.components.SectionHeader
import os.proximity.shared.guardrail.ActionType
import os.proximity.shared.guardrail.AuditLogEntry
import os.proximity.shared.guardrail.RequestDirection

/**
 * The audit log, rendered for a person rather than an engineer.
 *
 * This screen is what makes the app's promises checkable: a user who was
 * told "nothing is sent without your approval" can come here and see
 * exactly what was asked for and what was decided.
 */
@Composable
fun AuditScreen(entries: List<AuditLogEntry>, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) {
        EmptyState(
            title = "Nothing has happened yet",
            body = "Every decision this app makes — allowing something, blocking it, " +
                "or asking you — gets written here so you can check it later.",
            modifier = modifier
        )
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("Activity", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "This log stays on your phone. It is never uploaded anywhere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item { SectionHeader("${entries.size} recent decisions") }
        items(entries) { entry -> AuditRow(entry) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun AuditRow(entry: AuditLogEntry) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    describeAction(entry.request.actionType, entry.request.direction),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                DecisionBadge(entry.decision)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                entry.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    append(formatTimestamp(entry.timestampEpochMillis))
                    entry.request.peer?.let { append(" · ${it.deviceId.take(8)}") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Enum names would be technically accurate and practically useless here.
 * Every action gets a sentence a non-technical person can act on.
 */
private fun describeAction(action: ActionType, direction: RequestDirection): String {
    val inbound = direction == RequestDirection.INBOUND
    return when (action) {
        ActionType.DISCOVER_PEER -> "Look for nearby devices"
        ActionType.CONNECT_PEER ->
            if (inbound) "Someone nearby asked to connect" else "Connect to a nearby device"
        ActionType.SEND_MESSAGE -> "Send a message"
        ActionType.RECEIVE_MESSAGE -> "Receive a message"
        ActionType.SEND_FILE -> "Send a file"
        ActionType.RECEIVE_FILE -> "Someone tried to send you a file"
        ActionType.READ_CONTACTS -> "Read your contacts"
        ActionType.READ_CALENDAR -> "Read your calendar"
        ActionType.SHARE_LOCATION -> "Share your location"
        ActionType.ADVERTISE_CAPABILITY -> "Tell nearby devices what you offer"
        ActionType.REQUEST_CAPABILITY -> "Ask a device for something it offers"
        ActionType.RELAY_MESSAGE -> "Carry a message for someone else"
        ActionType.SYNC_LIST ->
            if (inbound) "Receive a shared list update" else "Share a list update"
        ActionType.LEAVE_MESH -> "Send data outside the local mesh"
        ActionType.EXECUTE_CODE -> "Run code sent by another device"
    }
}

private fun formatTimestamp(epochMillis: Long): String =
    SimpleDateFormat("d MMM, HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))
