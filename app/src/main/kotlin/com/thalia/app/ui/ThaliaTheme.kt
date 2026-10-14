package com.thalia.app.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import com.thalia.app.R

/**
 * Thalia's theme: the approved V2 design (the "Thalia V1 Everyday Design Preview v2" canvas), not dynamic colour.
 *
 * A cool-paper ground with a teal accent and Atkinson Hyperlegible Next, bundled so no network is needed. The dark palette is a
 * proposal derived from the same tokens and still awaits the owner's review. Light, dark and the accent come from
 * [AppearancePrefs], which is shared state, so a change made in Settings repaints the screens behind it at once.
 */
@Composable
fun ThaliaTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val prefs = AppearancePrefs.of(LocalContext.current)
    val choice = prefs.theme
    val accent = prefs.accent
    val system = isSystemInDarkTheme()
    val dark =
        darkTheme ?: when (choice) {
            ThemeChoice.SYSTEM -> system
            ThemeChoice.LIGHT -> false
            ThemeChoice.DARK -> true
        }
    // The bar icons follow the app's own theme, not the phone's: Light on a dark phone would otherwise leave white icons on pale paper.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                // No system scrim behind the navigation bar: the app's own background shows there, in either theme.
                window.isNavigationBarContrastEnforced = false
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    MaterialTheme(
        colorScheme = thaliaColorScheme(dark, accent),
        typography = THALIA_TYPOGRAPHY,
        content = content,
    )
}

/** The palette for light or dark and an accent. Internal so a test can check its contrast. */
internal fun thaliaColorScheme(
    dark: Boolean,
    accent: Accent,
): ColorScheme = if (dark) darkScheme(accent.dark) else lightScheme(accent.light)

private fun lightScheme(accent: Color): ColorScheme =
    lightColorScheme(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD6E6E3),
        onPrimaryContainer = Color(0xFF1B4F4C),
        secondary = Color(0xFF3E5156),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFD6E6E3),
        onSecondaryContainer = Color(0xFF16282C),
        tertiary = Color(0xFF6A4A00),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFBE9B7),
        onTertiaryContainer = Color(0xFF3B2A00),
        background = Color(0xFFF3F6F4),
        onBackground = Color(0xFF16282C),
        surface = Color(0xFFF3F6F4),
        onSurface = Color(0xFF16282C),
        surfaceVariant = Color(0xFFEDF1F0),
        onSurfaceVariant = Color(0xFF4A5B60),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF8FAF9),
        surfaceContainer = Color(0xFFEDF1F0),
        surfaceContainerHigh = Color(0xFFE4EBE9),
        surfaceContainerHighest = Color(0xFFDCE5E3),
        outline = Color(0xFF8CA2A0),
        outlineVariant = Color(0xFFD5DFDD),
        error = Color(0xFFB3402F),
        onError = Color.White,
        errorContainer = Color(0xFFF8D9D4),
        onErrorContainer = Color(0xFF5B1A12),
    )

private fun darkScheme(accent: Color): ColorScheme =
    darkColorScheme(
        primary = accent,
        onPrimary = Color(0xFF06201F),
        primaryContainer = Color(0xFF1F3A3D),
        onPrimaryContainer = Color(0xFFBFE3DE),
        secondary = Color(0xFFA8BDBB),
        onSecondary = Color(0xFF0E1A1C),
        secondaryContainer = Color(0xFF1F3A3D),
        onSecondaryContainer = Color(0xFFE7F1EF),
        tertiary = Color(0xFFFBE6A9),
        onTertiary = Color(0xFF3B2A00),
        tertiaryContainer = Color(0xFF4A3A10),
        onTertiaryContainer = Color(0xFFFBE6A9),
        background = Color(0xFF0E1A1C),
        onBackground = Color(0xFFE7F1EF),
        surface = Color(0xFF0E1A1C),
        onSurface = Color(0xFFE7F1EF),
        surfaceVariant = Color(0xFF1D3033),
        onSurfaceVariant = Color(0xFFA8BDBB),
        surfaceContainerLowest = Color(0xFF162427),
        surfaceContainerLow = Color(0xFF142124),
        surfaceContainer = Color(0xFF1D3033),
        surfaceContainerHigh = Color(0xFF243A3D),
        surfaceContainerHighest = Color(0xFF2B4245),
        outline = Color(0xFF4B6568),
        outlineVariant = Color(0xFF2B4245),
        error = Color(0xFFF2998C),
        onError = Color(0xFF3A0F09),
        errorContainer = Color(0xFF5A2A24),
        onErrorContainer = Color(0xFFF8D9D4),
    )

/** Atkinson Hyperlegible Next, one variable font file, cut at the three weights the design uses. */
private val ATKINSON =
    FontFamily(
        listOf(400, 600, 700).map { weight ->
            Font(
                R.font.atkinson_hyperlegible_next,
                weight = FontWeight(weight),
                variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
            )
        },
    )

private fun TextStyle.atkinson() = copy(fontFamily = ATKINSON)

private val THALIA_TYPOGRAPHY =
    Typography().let { base ->
        base.copy(
            displayLarge = base.displayLarge.atkinson(),
            displayMedium = base.displayMedium.atkinson(),
            displaySmall = base.displaySmall.atkinson(),
            headlineLarge = base.headlineLarge.atkinson(),
            headlineMedium = base.headlineMedium.atkinson(),
            headlineSmall = base.headlineSmall.atkinson(),
            titleLarge = base.titleLarge.atkinson(),
            titleMedium = base.titleMedium.atkinson(),
            titleSmall = base.titleSmall.atkinson(),
            bodyLarge = base.bodyLarge.atkinson(),
            bodyMedium = base.bodyMedium.atkinson(),
            bodySmall = base.bodySmall.atkinson(),
            labelLarge = base.labelLarge.atkinson(),
            labelMedium = base.labelMedium.atkinson(),
            labelSmall = base.labelSmall.atkinson(),
        )
    }
