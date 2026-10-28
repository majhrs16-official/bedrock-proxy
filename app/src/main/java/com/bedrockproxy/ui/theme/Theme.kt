package com.bedrockproxy.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Paleta oscura "terminal / esmeralda".
 * Fondo casi negro azulado, acentos verde esmeralda y cian.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF34D399),
    onPrimary = Color(0xFF04140D),
    primaryContainer = Color(0xFF065F46),
    onPrimaryContainer = Color(0xFFA7F3D0),

    secondary = Color(0xFF38BDF8),
    onSecondary = Color(0xFF04212E),
    secondaryContainer = Color(0xFF0C4A6E),
    onSecondaryContainer = Color(0xFFBAE6FD),

    tertiary = Color(0xFFA78BFA),
    onTertiary = Color(0xFF1B1035),

    background = Color(0xFF0B0F12),
    onBackground = Color(0xFFE6EDF3),

    surface = Color(0xFF12181D),
    onSurface = Color(0xFFE6EDF3),

    surfaceVariant = Color(0xFF1A2229),
    onSurfaceVariant = Color(0xFF94A3B8),

    outline = Color(0xFF2A3540),
    outlineVariant = Color(0xFF1E2933),

    error = Color(0xFFF87171),
    onError = Color(0xFF2A0A0A),
    errorContainer = Color(0xFF3B1414),
    onErrorContainer = Color(0xFFFECACA),

    inverseSurface = Color(0xFFE6EDF3),
    inverseOnSurface = Color(0xFF0B0F12),
    scrim = Color(0xFF000000)
)

/** Colores propios del panel de registro (no forman parte del esquema M3). */
object LogColors {
    val background = Color(0xFF070B0E)
    val header = Color(0xFF0D1418)
    val debug = Color(0xFF64748B)
    val info = Color(0xFF86EFAC)
    val warn = Color(0xFFFCD34D)
    val error = Color(0xFFFCA5A5)
    val timestamp = Color(0xFF475569)
}

@Composable
fun BedrockProxyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
