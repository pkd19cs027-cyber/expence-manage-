package com.kharcha.ledger.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * A deep green ledger palette: money-adjacent without being a bank's brand, and
 * legible in sunlight, which is where most people check their phone.
 */
private val Ink = Color(0xFF0B1F1A)
private val Forest = Color(0xFF0F4C3A)
private val Mint = Color(0xFF4FD1A5)
private val Sand = Color(0xFFF6F4EE)
private val Clay = Color(0xFFC2410C)
private val Slate = Color(0xFF5B6B66)

val SpendRed = Color(0xFFB3261E)
val IncomeGreen = Color(0xFF15803D)
val NeutralBlue = Color(0xFF1D4ED8)

private val LightColors = lightColorScheme(
    primary = Forest,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEFE2),
    onPrimaryContainer = Ink,
    secondary = Slate,
    tertiary = Clay,
    background = Sand,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE7E5DE),
    onSurfaceVariant = Slate,
    error = SpendRed
)

private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = Ink,
    primaryContainer = Color(0xFF14342B),
    onPrimaryContainer = Color(0xFFCDEFE2),
    secondary = Color(0xFF9FB3AC),
    tertiary = Color(0xFFFFB59A),
    background = Color(0xFF0A100E),
    onBackground = Color(0xFFE6EAE8),
    surface = Color(0xFF121917),
    onSurface = Color(0xFFE6EAE8),
    surfaceVariant = Color(0xFF23302C),
    onSurfaceVariant = Color(0xFFB3C2BC),
    error = Color(0xFFFFB4AB)
)

private val KharchaTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun KharchaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = colors, typography = KharchaTypography, content = content)
}
