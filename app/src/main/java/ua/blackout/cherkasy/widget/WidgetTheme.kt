package ua.blackout.cherkasy.widget

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.glance.GlanceTheme
import androidx.glance.material3.ColorProviders

/** Amber "electric" fallback for devices without Material You (API < 31). */
val FallbackLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF7A5900), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDEA6), onPrimaryContainer = Color(0xFF261900),
    secondary = Color(0xFF6B5D3F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF5E0BB), onSecondaryContainer = Color(0xFF241A04),
    tertiary = Color(0xFF4A6547), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCCEBC4), onTertiaryContainer = Color(0xFF072109),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F2), onBackground = Color(0xFF1F1B13),
    surface = Color(0xFFFFF8F2), onSurface = Color(0xFF1F1B13),
    surfaceVariant = Color(0xFFEDE1CF), onSurfaceVariant = Color(0xFF4D4639),
    outline = Color(0xFF7F7667),
)

val FallbackDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFF5BD48), onPrimary = Color(0xFF402D00),
    primaryContainer = Color(0xFF5C4300), onPrimaryContainer = Color(0xFFFFDEA6),
    secondary = Color(0xFFD8C4A0), onSecondary = Color(0xFF3B2F15),
    secondaryContainer = Color(0xFF52452A), onSecondaryContainer = Color(0xFFF5E0BB),
    tertiary = Color(0xFFB0CFAA), onTertiary = Color(0xFF1D361C),
    tertiaryContainer = Color(0xFF334D31), onTertiaryContainer = Color(0xFFCCEBC4),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF17130B), onBackground = Color(0xFFEBE1D4),
    surface = Color(0xFF17130B), onSurface = Color(0xFFEBE1D4),
    surfaceVariant = Color(0xFF4D4639), onSurfaceVariant = Color(0xFFD0C5B4),
    outline = Color(0xFF998F80),
)

private val fallbackProviders = ColorProviders(light = FallbackLight, dark = FallbackDark)

@Composable
fun BlackoutGlanceTheme(content: @Composable () -> Unit) {
    // GlanceTheme's default colors are the dynamic (wallpaper-based) ones on API 31+.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) GlanceTheme(content = content)
    else GlanceTheme(colors = fallbackProviders, content = content)
}
