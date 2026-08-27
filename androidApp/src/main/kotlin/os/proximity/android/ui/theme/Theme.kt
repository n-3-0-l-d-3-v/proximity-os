package os.proximity.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColors = darkColorScheme(
    primary = Signal80,
    onPrimary = Signal20,
    primaryContainer = Signal30,
    onPrimaryContainer = Signal90,
    secondary = Mesh80,
    onSecondary = Mesh20,
    secondaryContainer = Mesh30,
    onSecondaryContainer = Mesh90,
    background = Neutral6,
    onBackground = Neutral90,
    surface = Neutral10,
    onSurface = Neutral90,
    surfaceVariant = Neutral17,
    onSurfaceVariant = Color(0xFFC2C7CF),
    error = DenyRedDark,
    onError = Color(0xFF690005)
)

private val LightColors = lightColorScheme(
    primary = Signal40,
    onPrimary = Color.White,
    primaryContainer = Signal90,
    onPrimaryContainer = Signal10,
    secondary = Mesh40,
    onSecondary = Color.White,
    secondaryContainer = Mesh90,
    onSecondaryContainer = Mesh10,
    background = Neutral99,
    onBackground = Neutral10,
    surface = Color.White,
    onSurface = Neutral10,
    surfaceVariant = Neutral95,
    onSurfaceVariant = Color(0xFF43474E),
    error = DenyRed,
    onError = Color.White
)

private val ProximityShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
)

/**
 * Semantic colors for Guardrail decisions. These are intentionally *not*
 * folded into the Material color scheme: allow / ask / deny carry meaning
 * the standard roles (primary, error, …) don't express, and a user must be
 * able to tell them apart instantly in the audit log.
 */
data class DecisionColors(
    val allow: Color,
    val allowContainer: Color,
    val ask: Color,
    val askContainer: Color,
    val deny: Color,
    val denyContainer: Color
)

val LocalDecisionColors = staticCompositionLocalOf {
    DecisionColors(
        allow = AllowGreen,
        allowContainer = AllowGreenContainer,
        ask = AskAmber,
        askContainer = AskAmberContainer,
        deny = DenyRed,
        denyContainer = DenyRedContainer
    )
}

private val DarkDecisionColors = DecisionColors(
    allow = AllowGreenDark,
    allowContainer = Color(0xFF00522F),
    ask = AskAmberDark,
    askContainer = Color(0xFF5C4300),
    deny = DenyRedDark,
    denyContainer = Color(0xFF8C1D18)
)

private val LightDecisionColors = DecisionColors(
    allow = AllowGreen,
    allowContainer = AllowGreenContainer,
    ask = AskAmber,
    askContainer = AskAmberContainer,
    deny = DenyRed,
    denyContainer = DenyRedContainer
)

@Composable
fun ProximityTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val decisionColors = if (darkTheme) DarkDecisionColors else LightDecisionColors

    CompositionLocalProvider(LocalDecisionColors provides decisionColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ProximityTypography,
            shapes = ProximityShapes,
            content = content
        )
    }
}
