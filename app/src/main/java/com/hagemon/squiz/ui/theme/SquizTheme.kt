package com.hagemon.squiz.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.hagemon.squiz.model.AppSettings
import com.hagemon.squiz.model.ColorTheme
import com.hagemon.squiz.model.ThemeMode

data class PalettePreview(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val background: Color,
)

fun ColorTheme.previewColors(): PalettePreview = when (this) {
    ColorTheme.LAVENDER -> PalettePreview(
        primary = Color(0xFF655E96),
        secondary = Color(0xFFB995C7),
        tertiary = Color(0xFFF18F82),
        background = Color(0xFFF8F5FC),
    )
    ColorTheme.ROSE -> PalettePreview(
        primary = Color(0xFF76545F),
        secondary = Color(0xFFD5A7B3),
        tertiary = Color(0xFFE4B7A8),
        background = Color(0xFFFFF7F8),
    )
    ColorTheme.OCEAN -> PalettePreview(
        primary = Color(0xFF286573),
        secondary = Color(0xFF88C5D1),
        tertiary = Color(0xFF9B9AD7),
        background = Color(0xFFF4FAFB),
    )
    ColorTheme.SAGE -> PalettePreview(
        primary = Color(0xFF4D6753),
        secondary = Color(0xFFA8C7AA),
        tertiary = Color(0xFFE2B58E),
        background = Color(0xFFF6FAF5),
    )
}

@Composable
fun SquizTheme(
    settings: AppSettings,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val currentDensity = LocalDensity.current
    val scaledDensity = Density(
        density = currentDensity.density,
        fontScale = currentDensity.fontScale * settings.textScale,
    )

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        MaterialTheme(
            colorScheme = colorScheme(settings.colorTheme, darkTheme),
            content = content,
        )
    }
}

private fun colorScheme(theme: ColorTheme, dark: Boolean) = when (theme) {
    ColorTheme.LAVENDER -> if (dark) {
        darkColorScheme(
            primary = Color(0xFFCDC4FF),
            onPrimary = Color(0xFF352E67),
            primaryContainer = Color(0xFF4C467E),
            onPrimaryContainer = Color(0xFFE6DFFF),
            secondary = Color(0xFFD9B8E4),
            tertiary = Color(0xFFFFB4A9),
            background = Color(0xFF121116),
            surface = Color(0xFF121116),
            surfaceVariant = Color(0xFF48464F),
            outline = Color(0xFF918F99),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF5F588F),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFE5DEFF),
            onPrimaryContainer = Color(0xFF1B1450),
            secondary = Color(0xFF76557E),
            tertiary = Color(0xFF9B413B),
            background = Color(0xFFFFF9FF),
            surface = Color(0xFFFFF9FF),
            surfaceVariant = Color(0xFFE6E0EA),
            outline = Color(0xFF79747E),
        )
    }
    ColorTheme.ROSE -> if (dark) {
        darkColorScheme(
            primary = Color(0xFFE7BDC7),
            onPrimary = Color(0xFF442630),
            primaryContainer = Color(0xFF5D3C46),
            onPrimaryContainer = Color(0xFFFFD9E1),
            secondary = Color(0xFFDDBDC3),
            tertiary = Color(0xFFF3BAA7),
            background = Color(0xFF171213),
            surface = Color(0xFF171213),
            surfaceVariant = Color(0xFF514346),
            outline = Color(0xFF9F8C90),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF76545F),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFFFD9E2),
            onPrimaryContainer = Color(0xFF2C151D),
            secondary = Color(0xFF74565D),
            tertiary = Color(0xFF81533F),
            background = Color(0xFFFFF8F8),
            surface = Color(0xFFFFF8F8),
            surfaceVariant = Color(0xFFF1DEE2),
            outline = Color(0xFF837378),
        )
    }
    ColorTheme.OCEAN -> if (dark) {
        darkColorScheme(
            primary = Color(0xFF8CD1E0),
            onPrimary = Color(0xFF00363E),
            primaryContainer = Color(0xFF174E59),
            onPrimaryContainer = Color(0xFFA7EDFC),
            secondary = Color(0xFFB4CAD0),
            tertiary = Color(0xFFC6C2EA),
            background = Color(0xFF0E1416),
            surface = Color(0xFF0E1416),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF286573),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFABEEFC),
            onPrimaryContainer = Color(0xFF001F26),
            secondary = Color(0xFF4B6268),
            tertiary = Color(0xFF5D5C7D),
            background = Color(0xFFF4FAFB),
            surface = Color(0xFFF4FAFB),
        )
    }
    ColorTheme.SAGE -> if (dark) {
        darkColorScheme(
            primary = Color(0xFFB1D0B6),
            onPrimary = Color(0xFF1D3824),
            primaryContainer = Color(0xFF344F3A),
            onPrimaryContainer = Color(0xFFCCECCD),
            secondary = Color(0xFFBACBB9),
            tertiary = Color(0xFFE9C098),
            background = Color(0xFF101510),
            surface = Color(0xFF101510),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF4D6753),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFCFE9D1),
            onPrimaryContainer = Color(0xFF0B2011),
            secondary = Color(0xFF526351),
            tertiary = Color(0xFF75583D),
            background = Color(0xFFF6FAF5),
            surface = Color(0xFFF6FAF5),
        )
    }
}
