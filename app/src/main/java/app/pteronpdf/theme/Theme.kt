package app.pteronpdf.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Same three-layer presets as the desktop app: light = bars, dark = accent, canvas derived. */
data class ThemePreset(val name: String, val light: Color, val medium: Color, val dark: Color)

val ThemePresets = listOf(
    ThemePreset("Sky Blue", Color(0xFFDCE9FB), Color(0xFF8FB8ED), Color(0xFF2F5FA8)),
    ThemePreset("Graphite", Color(0xFFF0F2F5), Color(0xFFCBD5E1), Color(0xFF1E293B)),
    ThemePreset("Teal", Color(0xFFD8F5EE), Color(0xFF4FC3AE), Color(0xFF0E6E5E)),
    ThemePreset("Green", Color(0xFFE3F5DC), Color(0xFF8AD376), Color(0xFF2F7A2A)),
    ThemePreset("Sage", Color(0xFFE7ECDD), Color(0xFFAEBB8C), Color(0xFF55622F)),
    ThemePreset("Gold", Color(0xFFFBF0C9), Color(0xFFE2C55C), Color(0xFF8A7712)),
    ThemePreset("Orange", Color(0xFFFCE4CF), Color(0xFFEFA876), Color(0xFFA85A22)),
    ThemePreset("Terracotta", Color(0xFFF0DCCF), Color(0xFFCBA087), Color(0xFF6E4A34)),
    ThemePreset("Rose", Color(0xFFFBE1E9), Color(0xFFED91AB), Color(0xFF9C2F52)),
    ThemePreset("Plum", Color(0xFFF3E0EE), Color(0xFFC77FB2), Color(0xFF712864)),
    ThemePreset("Lavender", Color(0xFFEAE3F7), Color(0xFFB29CDD), Color(0xFF4F3585)),
)

/** Resolved colors the UI actually paints with. */
data class PteronColors(
    val bar: Color,        // toolbar / sheets
    val canvas: Color,     // behind the pages
    val accent: Color,     // selected / active controls
    val onBar: Color,      // icons + text on bars
    val onAccent: Color,
    val chip: Color,       // subtle pill backgrounds
    val isDark: Boolean,
)

val LocalPteron = staticCompositionLocalOf<PteronColors> { error("PteronTheme missing") }

enum class DarkMode { System, Light, Dark }

@Composable
fun PteronTheme(preset: ThemePreset, mode: DarkMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        DarkMode.System -> isSystemInDarkTheme()
        DarkMode.Light -> false
        DarkMode.Dark -> true
    }
    val colors = if (dark) {
        // Dark keeps its own near-black palette; only the active color follows the theme.
        PteronColors(
            bar = Color(0xFF161616), canvas = Color(0xFF121620), accent = preset.medium,
            onBar = Color(0xFFF5F5F7), onAccent = Color(0xFF0C0C0E),
            chip = Color(0xFF232326), isDark = true,
        )
    } else {
        PteronColors(
            bar = preset.light, canvas = lerp(preset.light, Color.White, 0.62f), accent = preset.dark,
            onBar = Color(0xFF26272C), onAccent = Color.White,
            chip = lerp(preset.light, Color.White, 0.45f), isDark = false,
        )
    }
    val scheme = if (dark) darkColorScheme(
        primary = colors.accent, onPrimary = colors.onAccent, surface = colors.bar,
        onSurface = colors.onBar, background = colors.canvas, surfaceContainer = colors.bar,
    ) else lightColorScheme(
        primary = colors.accent, onPrimary = colors.onAccent, surface = colors.bar,
        onSurface = colors.onBar, background = colors.canvas, surfaceContainer = colors.bar,
    )
    CompositionLocalProvider(LocalPteron provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
