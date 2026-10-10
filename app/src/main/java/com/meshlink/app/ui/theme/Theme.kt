package com.meshlink.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Light MeshLink palette: clean white surfaces with soft green/teal accents.
// Emergency red remains reserved for SOS / critical states.
private val MeshScheme = lightColorScheme(
    primary = Color(0xFF087F5B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9F5E9),
    onPrimaryContainer = Color(0xFF063D2D),

    secondary = Color(0xFF0E7490),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8F3F7),
    onSecondaryContainer = Color(0xFF063B46),

    tertiary = Color(0xFF6D5BD0),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEAE5FF),
    onTertiaryContainer = Color(0xFF302566),

    background = Color(0xFFF8FAF9),
    onBackground = Color(0xFF17201C),

    surface = Color.White,
    onSurface = Color(0xFF17201C),

    surfaceVariant = Color(0xFFF0F5F2),
    onSurfaceVariant = Color(0xFF52615A),
    outline = Color(0xFFC8D5CF),
    outlineVariant = Color(0xFFDDE7E2),

    surfaceContainer = Color(0xFFF5F8F6),
    surfaceContainerHigh = Color(0xFFEDF3F0),
    surfaceContainerHighest = Color(0xFFE7EFEB),

    error = Color(0xFFD92D20),
    onError = Color.White,
    errorContainer = Color(0xFFFFE4E1),
    onErrorContainer = Color(0xFF6B1712)
)

@Composable
fun MeshLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MeshScheme,
        typography = Typography,
        shapes = Shapes(
            small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
        ),
        content = content
    )
}
