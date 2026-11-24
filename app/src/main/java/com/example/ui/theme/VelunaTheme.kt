package com.example.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.example.R
import com.example.data.local.VelunaUserPrefs
import com.example.model.ThemeMode
import com.example.model.VelunaThemePreset

val OxaniumFontFamily = FontFamily(
    Font(R.font.oxanium, FontWeight.Normal),
    Font(R.font.oxanium, FontWeight.Medium),
    Font(R.font.oxanium, FontWeight.SemiBold),
    Font(R.font.oxanium, FontWeight.Bold)
)

val IbmPlexSansFontFamily = FontFamily(
    Font(R.font.ibm_plex_sans, FontWeight.Normal),
    Font(R.font.ibm_plex_sans, FontWeight.Medium),
    Font(R.font.ibm_plex_sans, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans, FontWeight.Bold)
)

val VelunaTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = OxaniumFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = OxaniumFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = OxaniumFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleLarge = TextStyle(
        fontFamily = OxaniumFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = IbmPlexSansFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontFamily = IbmPlexSansFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = IbmPlexSansFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = IbmPlexSansFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = IbmPlexSansFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = IbmPlexSansFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = IbmPlexSansFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = IbmPlexSansFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp
    )
)

fun parseHexColorOrNull(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    val cleaned = hex.trim().removePrefix("#")
    val value = cleaned.toLongOrNull(16) ?: return null
    return when (cleaned.length) {
        6 -> Color(0xFF000000L or value)
        8 -> Color(value)
        else -> null
    }
}

fun contrastRatio(a: Color, b: Color): Float {
    val l1 = a.luminance()
    val l2 = b.luminance()
    val lighter = maxOf(l1, l2)
    val darker = minOf(l1, l2)
    return (lighter + 0.05f) / (darker + 0.05f)
}

fun bestOnColor(background: Color): Color {
    val darkInk = Color(0xFF111114)
    val lightInk = Color(0xFFFFFFFF)
    return if (contrastRatio(lightInk, background) >= contrastRatio(darkInk, background)) lightInk else darkInk
}

fun ensureContrast(foreground: Color, background: Color, minRatio: Float): Color {
    if (contrastRatio(foreground, background) >= minRatio) return foreground
    val target = if (background.luminance() < 0.45f) Color.White else Color(0xFF0A0A0D)
    if (contrastRatio(target, background) < minRatio) {
        return bestOnColor(background)
    }
    var low = 0f
    var high = 1f
    repeat(16) {
        val mid = (low + high) * 0.5f
        val candidate = lerp(foreground, target, mid)
        if (contrastRatio(candidate, background) >= minRatio) {
            high = mid
        } else {
            low = mid
        }
    }
    return lerp(foreground, target, high)
}

private fun ensureContrastAcross(
    foreground: Color,
    backgrounds: List<Color>,
    minRatio: Float
): Color {
    var result = foreground
    repeat(2) {
        backgrounds.forEach { bg ->
            result = ensureContrast(result, bg, minRatio)
        }
    }
    return result
}

private fun enforceWcagContrast(scheme: ColorScheme): ColorScheme {
    val primaryTextSurfaces = listOf(
        scheme.background,
        scheme.surface,
        scheme.surfaceContainer,
        scheme.surfaceContainerHigh
    )
    val allTextSurfaces = listOf(
        scheme.background,
        scheme.surface,
        scheme.surfaceContainer,
        scheme.surfaceContainerHigh,
        scheme.surfaceContainerHighest,
        scheme.surfaceVariant
    )

    val safePrimary = ensureContrastAcross(scheme.primary, primaryTextSurfaces, 4.5f)
    val safeOnPrimary = ensureContrast(scheme.onPrimary, safePrimary, 4.6f)
    val safeOnPrimaryContainer = ensureContrast(scheme.onPrimaryContainer, scheme.primaryContainer, 5.0f)

    val safeSecondary = ensureContrastAcross(scheme.secondary, primaryTextSurfaces, 4.5f)
    val safeOnSecondary = ensureContrast(scheme.onSecondary, safeSecondary, 4.6f)
    val safeOnSecondaryContainer = ensureContrast(scheme.onSecondaryContainer, scheme.secondaryContainer, 5.0f)

    val safeTertiary = ensureContrastAcross(scheme.tertiary, listOf(scheme.background, scheme.surface, scheme.surfaceContainer), 4.5f)
    val safeOnTertiary = ensureContrast(scheme.onTertiary, safeTertiary, 4.6f)

    val safeOnBackground = ensureContrast(scheme.onBackground, scheme.background, 7.5f)
    val safeOnSurface = ensureContrastAcross(scheme.onSurface, allTextSurfaces, 7.0f)
    val safeOnSurfaceVariant = ensureContrastAcross(scheme.onSurfaceVariant, allTextSurfaces, 4.6f)
    val safeOutline = ensureContrastAcross(scheme.outline, listOf(scheme.background, scheme.surfaceContainer, scheme.surfaceContainerHigh), 2.8f)
    val safeOutlineVariant = ensureContrastAcross(scheme.outlineVariant, listOf(scheme.background, scheme.surfaceContainer, scheme.surfaceContainerHigh), 1.6f)
    val safeError = ensureContrastAcross(scheme.error, primaryTextSurfaces, 4.6f)
    val safeOnError = ensureContrast(scheme.onError, safeError, 4.6f)

    return scheme.copy(
        primary = safePrimary,
        onPrimary = safeOnPrimary,
        onPrimaryContainer = safeOnPrimaryContainer,
        secondary = safeSecondary,
        onSecondary = safeOnSecondary,
        onSecondaryContainer = safeOnSecondaryContainer,
        tertiary = safeTertiary,
        onTertiary = safeOnTertiary,
        onBackground = safeOnBackground,
        onSurface = safeOnSurface,
        onSurfaceVariant = safeOnSurfaceVariant,
        outline = safeOutline,
        outlineVariant = safeOutlineVariant,
        error = safeError,
        onError = safeOnError
    )
}

fun buildVelunaColorScheme(
    preset: VelunaThemePreset,
    darkTheme: Boolean,
    customAccentHex: String? = null,
    artworkAccent: Color? = null,
    adaptToArtwork: Boolean = false
): ColorScheme {
    val base = if (darkTheme) darkPresetScheme(preset) else lightPresetScheme(preset)
    val custom = parseHexColorOrNull(customAccentHex)
    val accent = when {
        custom != null -> custom
        adaptToArtwork && artworkAccent != null -> lerp(base.primary, artworkAccent, 0.55f)
        else -> null
    } ?: return enforceWcagContrast(base)

    val safeAccent = ensureContrast(
        ensureContrast(accent, base.surface, 4.5f),
        base.surfaceContainer,
        4.5f
    )
    val onAccent = bestOnColor(safeAccent)
    val accentContainer = if (darkTheme) lerp(base.surfaceContainerHigh, safeAccent, 0.28f) else lerp(Color.White, safeAccent, 0.16f)
    val onAccentContainer = if (darkTheme) lerp(safeAccent, Color.White, 0.72f) else lerp(safeAccent, Color.Black, 0.58f)

    return enforceWcagContrast(
        base.copy(
            primary = safeAccent,
            onPrimary = onAccent,
            primaryContainer = accentContainer,
            onPrimaryContainer = onAccentContainer,
            secondary = lerp(base.secondary, safeAccent, 0.35f),
            tertiary = lerp(base.tertiary, safeAccent, 0.4f)
        )
    )
}


private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun VelunaTheme(
    prefs: VelunaUserPrefs,
    artworkAccent: Color? = null,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (prefs.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }
    val colorScheme = remember(
        prefs.themePreset,
        useDark,
        prefs.customAccentHex,
        artworkAccent,
        prefs.adaptToArtwork
    ) {
        buildVelunaColorScheme(
            preset = prefs.themePreset,
            darkTheme = useDark,
            customAccentHex = prefs.customAccentHex,
            artworkAccent = artworkAccent,
            adaptToArtwork = prefs.adaptToArtwork
        )
    }
    val shapes = remember(prefs.cornerRadiusDp) {
        val r = prefs.cornerRadiusDp.dp
        Shapes(
            extraSmall = RoundedCornerShape((r * 0.4f).coerceAtLeast(4.dp)),
            small = RoundedCornerShape((r * 0.7f).coerceAtLeast(6.dp)),
            medium = RoundedCornerShape(r),
            large = RoundedCornerShape((r * 1.4f).coerceAtMost(28.dp)),
            extraLarge = RoundedCornerShape((r * 1.8f).coerceAtMost(32.dp))
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        LaunchedEffect(view, useDark) {
            val window = view.context.findActivity()?.window ?: return@LaunchedEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !useDark
            controller.isAppearanceLightNavigationBars = !useDark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VelunaTypography,
        shapes = shapes
    ) {
        CompositionLocalProvider(LocalContentColor provides colorScheme.onBackground) {
            content()
        }
    }
}

