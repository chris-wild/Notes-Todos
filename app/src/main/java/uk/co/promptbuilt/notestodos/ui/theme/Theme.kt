package uk.co.promptbuilt.notestodos.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Light or dark follows the phone's setting, as the iOS app does.
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0B2545),
    secondary = Color(0xFFA8C7FA),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    surfaceVariant = Color(0xFF2A2A2A),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF3F5F90),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF0F2F5),
    onSurfaceVariant = Color(0xFF44474E),
    // Neutral greys and a blue accent, as iOS's system colours are; Material's defaults
    // would tint selected chips and the tab bar lavender.
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    secondaryContainer = Color(0xFFD3E3FD),
    onSecondaryContainer = Color(0xFF041E49),
    surfaceContainer = Color(0xFFF3F4F6),
    surfaceContainerLow = Color(0xFFF7F8FA),
    surfaceContainerHigh = Color(0xFFECEEF1),
    surfaceContainerHighest = Color(0xFFE6E8EB),
)

@Composable
fun NotesTodosTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
