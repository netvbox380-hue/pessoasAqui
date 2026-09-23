package br.com.pessoasaqui.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = RadarCyan,
    onPrimary = DeepBlack,
    primaryContainer = RadarCyanGlow,
    onPrimaryContainer = RadarCyan,
    secondary = EmeraldGreen,
    onSecondary = DeepBlack,
    secondaryContainer = EmeraldGreenGlow,
    onSecondaryContainer = EmeraldGreen,
    background = DeepBlack,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkCard,
    onSurfaceVariant = TextSecondary,
    error = AlertRed,
    onError = DeepBlack
)

@Composable
fun PessoasAquiTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
