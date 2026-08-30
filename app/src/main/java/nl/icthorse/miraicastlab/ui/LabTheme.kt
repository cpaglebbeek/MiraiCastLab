package nl.icthorse.miraicastlab.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Lab palette.
 *
 * Deliberately high-contrast and dark by default: the tester reads this screen in a car, often in
 * daylight, at arm's length (spec section 21 "very large status labels").
 */
object LabColors {
    val Ink = Color(0xFF0B0F14)
    val Surface = Color(0xFF141A22)
    val SurfaceHigh = Color(0xFF1E2731)
    val Line = Color(0xFF2C3846)
    val Text = Color(0xFFE8EEF4)
    val TextDim = Color(0xFF93A3B4)

    val Accent = Color(0xFF00E5A0)
    val AccentDim = Color(0xFF0A8F66)

    // One colour per evidence grade. Used everywhere a status is shown.
    val Confirmed = Color(0xFF00E5A0)
    val Observed = Color(0xFF4FC3F7)
    val Inferred = Color(0xFFFFC845)
    val Unsupported = Color(0xFFFF6B6B)
    val NotTested = Color(0xFF7A8899)
    val Error = Color(0xFFFF3D71)
}

private val DarkScheme = darkColorScheme(
    primary = LabColors.Accent,
    onPrimary = LabColors.Ink,
    secondary = LabColors.Observed,
    background = LabColors.Ink,
    onBackground = LabColors.Text,
    surface = LabColors.Surface,
    onSurface = LabColors.Text,
    surfaceVariant = LabColors.SurfaceHigh,
    onSurfaceVariant = LabColors.TextDim,
    outline = LabColors.Line,
    error = LabColors.Error,
)

private val LightScheme = lightColorScheme(
    primary = LabColors.AccentDim,
    background = Color(0xFFF4F7FA),
    surface = Color(0xFFFFFFFF),
)

private val LabTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
)

/** Monospaced style for evidence values, coordinates and timestamps. */
val LabMono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

@Composable
fun LabTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // The lab UI is dark regardless of system setting when the tester is in a vehicle; we still
    // honour an explicitly light system theme for bench work.
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = LabTypography,
        content = content,
    )
}
