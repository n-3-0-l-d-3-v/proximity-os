package os.proximity.android.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import os.proximity.android.ui.components.EmptyState
import os.proximity.android.ui.components.SectionHeader
import os.proximity.shared.lists.SharedList

@Composable
fun ListsScreen(
    lists: List<SharedList>,
    connectedPeerCount: Int,
    onOpen: (String) -> Unit,
    onCreate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showCreate by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        if (lists.isEmpty()) {
            EmptyState(
                title = "No lists yet",
                body = "Make a list and anyone you connect to can add to it — even with no " +
                    "internet. Changes merge automatically when you are back in range.",
                actionLabel = "Create a list",
                onAction = { showCreate = true },
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(Modifier.weight(1f)) {
                item {
                    SectionHeader(
                        if (connectedPeerCount > 0) {
                            "Syncing with $connectedPeerCount nearby " +
                                if (connectedPeerCount == 1) "device" else "devices"
                        } else {
                            "Not connected — changes sync when you reconnect"
                        }
                    )
                }
                items(lists, key = { it.id }) { list ->
                    ListRow(list = list, onClick = { onOpen(list.id) })
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
            Button(
                onClick = { showCreate = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text("New list")
            }
        }
    }

    if (showCreate) {
        TextPromptDialog(
            title = "New list",
            label = "List name",
            confirmLabel = "Create",
            onDismiss = { showCreate = false },
            onConfirm = {
                onCreate(it)
                showCreate = false
            }
        )
    }
}

@Composable
private fun ListRow(list: SharedList, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                list.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    list.visibleItems.isEmpty() -> "Empty"
                    list.remainingCount == 0 -> "All ${list.visibleItems.size} done"
                    else -> "${list.remainingCount} of ${list.visibleItems.size} left"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ListDetailScreen(
    list: SharedList,
    onToggleItem: (String, Boolean) -> Unit,
    onAddItem: (String) -> Unit,
    onRemoveItem: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var draft by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize()) {
        if (list.visibleItems.isEmpty()) {
            EmptyState(
                title = "Nothing on this list",
                body = "Add the first thing below. Anyone connected sees it straight away.",
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(list.visibleItems, key = { it.id }) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleItem(item.id, !item.done) }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = item.done,
                            onCheckedChange = { onToggleItem(item.id, it) }
                        )
                        Text(
                            text = item.text,
                            style = MaterialTheme.typography.bodyLarge,
                            textDecoration = if (item.done) TextDecoration.LineThrough else null,
                            color = if (item.done) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { onRemoveItem(item.id) }) { Text("Remove") }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Add an item") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        onAddItem(draft)
                        draft = ""
                    },
                    enabled = draft.isNotBlank()
                ) {
                    Text("Add")
                }
            }
        }
    }
}

@Composable
private fun TextPromptDialog(
    title: String,
    label: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
