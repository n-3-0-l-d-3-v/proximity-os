package os.proximity.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.proximity.android.ui.theme.LocalDecisionColors
import os.proximity.shared.guardrail.AuditLogEntry
import os.proximity.shared.guardrail.TrustState

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 10.dp)
    )
}

/**
 * Empty states carry real weight in this app: "no devices nearby" is the
 * normal condition most of the time, not an error, and it should read that
 * way rather than looking broken.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun TrustBadge(trustState: TrustState, modifier: Modifier = Modifier) {
    val colors = LocalDecisionColors.current
    val verified = trustState == TrustState.VERIFIED
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = if (verified) colors.allowContainer else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = if (verified) "Verified" else "Not verified",
            style = MaterialTheme.typography.labelSmall,
            color = if (verified) colors.allow else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .size(8.dp)
            .background(color, CircleShape)
    )
}

@Composable
fun DecisionBadge(outcome: AuditLogEntry.DecisionOutcome, modifier: Modifier = Modifier) {
    val colors = LocalDecisionColors.current
    val (label, foreground, background) = when (outcome) {
        AuditLogEntry.DecisionOutcome.ALLOW -> Triple("Allowed", colors.allow, colors.allowContainer)
        AuditLogEntry.DecisionOutcome.DENY -> Triple("Blocked", colors.deny, colors.denyContainer)
        AuditLogEntry.DecisionOutcome.ASK_USER -> Triple("Asked you", colors.ask, colors.askContainer)
    }
    Surface(modifier = modifier, shape = RoundedCornerShape(6.dp), color = background) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = foreground,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/** Transient message strip, used for blocks and status changes. */
@Composable
fun Banner(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

/** Renders a fingerprint in a way that is easy to read aloud. */
@Composable
fun FingerprintText(fingerprint: String, modifier: Modifier = Modifier) {
    Text(
        text = fingerprint,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 2.sp,
        modifier = modifier
    )
}
