package com.gemmatranslator.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkColorScheme = darkColorScheme(
    primary = Primary80,
    onPrimary = Primary20,
    primaryContainer = Primary40,
    onPrimaryContainer = Primary80,

    secondary = Secondary80,
    onSecondary = Secondary20,
    secondaryContainer = Secondary40,
    onSecondaryContainer = Secondary80,

    tertiary = Tertiary80,
    onTertiary = Tertiary20,
    tertiaryContainer = Tertiary40,
    onTertiaryContainer = Tertiary80,

    background = Neutral10,
    onBackground = Neutral90,

    surface = Neutral15,
    onSurface = Neutral90,
    surfaceVariant = NeutralVariant30,
    onSurfaceVariant = NeutralVariant80,

    outline = NeutralVariant50,
    outlineVariant = NeutralVariant30,

    error = OnErrorDark,
    errorContainer = ErrorContainerDark,

    inverseSurface = Neutral90,
    inverseOnSurface = Neutral10,
    inversePrimary = Primary40,
)

private val LightColorScheme = lightColorScheme(
    primary = Primary40,
    onPrimary = Neutral99,
    primaryContainer = Primary80,
    onPrimaryContainer = Primary20,

    secondary = Secondary40,
    onSecondary = Neutral99,
    secondaryContainer = Secondary80,
    onSecondaryContainer = Secondary20,

    tertiary = Tertiary40,
    onTertiary = Neutral99,
    tertiaryContainer = Tertiary80,
    onTertiaryContainer = Tertiary20,

    background = Neutral99,
    onBackground = Neutral10,

    surface = Neutral95,
    onSurface = Neutral10,
    surfaceVariant = NeutralVariant90,
    onSurfaceVariant = NeutralVariant30,

    outline = NeutralVariant50,
    outlineVariant = NeutralVariant80,

    error = ErrorColor,
    inverseSurface = Neutral20,
    inverseOnSurface = Neutral95,
    inversePrimary = Primary80,
)

val GemmaTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp,
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

@Composable
fun GemmaTranslatorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = GemmaTypography,
        content = content
    )
}
