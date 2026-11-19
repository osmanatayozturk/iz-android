package org.iz.navigation.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext

private const val PREFERENCES = "iz_appearance"
private const val PALETTE_KEY = "palette"
private const val MODE_KEY = "mode"

internal data class PaletteColors(
    val brand: Color, val onBrand: Color, val route: Color, val surface: Color,
    val ink: Color, val soft: Color, val border: Color,
)

private fun colors(brand: Long, onBrand: Long, route: Long, surface: Long, ink: Long, soft: Long, border: Long) =
    PaletteColors(Color(brand), Color(onBrand), Color(route), Color(surface), Color(ink), Color(soft), Color(border))

internal enum class IzPalette(val id: String, val label: String, val day: PaletteColors, val night: PaletteColors) {
    INDIGO("indigo", "İndigo",
        colors(0xff414276, 0xffffffff, 0xff514b9c, 0xfffbf9ff, 0xff24223d, 0xffe8e6f3, 0xffc7c4d4),
        colors(0xff343654, 0xfff5f1ff, 0xffb8b2ff, 0xff22222e, 0xfff2eff7, 0xff333345, 0xff585769)),
    PETROL("petrol", "Petrol",
        colors(0xff145761, 0xffffffff, 0xff006e78, 0xfffbfaf4, 0xff172e32, 0xffe2edef, 0xffbbc9c8),
        colors(0xff12434c, 0xfff0f8f7, 0xff69d4cf, 0xff202c30, 0xffedf4f0, 0xff2b3c3f, 0xff506365)),
    GRAPHITE_AMBER("graphite_amber", "Grafit ve amber",
        colors(0xff303940, 0xffffffff, 0xffa6540c, 0xfffcfaf2, 0xff2a2a24, 0xfff7eac8, 0xffc6c3b8),
        colors(0xff403527, 0xfffff5dd, 0xffffbf69, 0xff252725, 0xfff2f1e5, 0xff3b3930, 0xff666252));

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: INDIGO
    }
}

internal enum class AppearanceMode(val id: String, val label: String) {
    SYSTEM("system", "Sistem ayarı"), DAY("day", "Gündüz"), NIGHT("night", "Gece")
}

internal data class IzAppearance(val palette: IzPalette, val mode: AppearanceMode)
private fun Context.appearancePreferences() = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
private fun SharedPreferences.appearance() = IzAppearance(
    IzPalette.fromId(getString(PALETTE_KEY, null)),
    AppearanceMode.entries.firstOrNull { it.id == getString(MODE_KEY, null) } ?: AppearanceMode.SYSTEM,
)

fun selectedPaletteId(context: Context): String = IzPalette.fromId(context.appearancePreferences().getString(PALETTE_KEY, null)).id

internal fun observePaletteChanges(context: Context, changed: () -> Unit): () -> Unit {
    val preferences = context.applicationContext.appearancePreferences()
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PALETTE_KEY || key == null) changed()
    }
    preferences.registerOnSharedPreferenceChangeListener(listener)
    return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
}

internal fun savePalette(context: Context, palette: IzPalette) =
    context.appearancePreferences().edit().putString(PALETTE_KEY, palette.id).apply()

internal fun saveAppearanceMode(context: Context, mode: AppearanceMode) =
    context.appearancePreferences().edit().putString(MODE_KEY, mode.id).apply()

internal val LocalIzAppearance = staticCompositionLocalOf { IzAppearance(IzPalette.INDIGO, AppearanceMode.SYSTEM) }

private fun PaletteColors.scheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val emphasis = if (dark) route else brand
    val onAction = if (dark) surface else onBrand
    return base.copy(
        primary = emphasis, onPrimary = onAction, primaryContainer = soft, onPrimaryContainer = ink,
        secondary = route, onSecondary = onAction, secondaryContainer = soft, onSecondaryContainer = ink,
        tertiary = emphasis, onTertiary = onAction, tertiaryContainer = soft, onTertiaryContainer = ink,
        background = surface, onBackground = ink, surface = surface, onSurface = ink, surfaceTint = emphasis,
        surfaceVariant = soft, onSurfaceVariant = lerp(ink, surface, if (dark) .20f else .23f),
        outline = lerp(border, ink, .40f), outlineVariant = border,
        surfaceBright = lerp(surface, Color.White, if (dark) .1f else .4f),
        surfaceDim = lerp(surface, Color.Black, if (dark) .12f else .08f),
        surfaceContainerLowest = if (dark) lerp(surface, Color.Black, .12f) else Color.White,
        surfaceContainerLow = lerp(surface, soft, .32f), surfaceContainer = lerp(surface, soft, .54f),
        surfaceContainerHigh = lerp(surface, soft, .76f), surfaceContainerHighest = soft,
        inverseSurface = ink, inverseOnSurface = surface, inversePrimary = if (dark) brand else soft,
    )
}

val Forest: Color @Composable get() = MaterialTheme.colorScheme.primary
val Leaf: Color @Composable get() = MaterialTheme.colorScheme.primaryContainer
val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
val Paper: Color @Composable get() = MaterialTheme.colorScheme.surface
val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val Clay: Color @Composable get() = MaterialTheme.colorScheme.secondary

@Composable
fun IzTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { context.appearancePreferences() }
    var appearance by remember(preferences) { mutableStateOf(preferences.appearance()) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> appearance = preferences.appearance() }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        appearance = preferences.appearance()
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val dark = when (appearance.mode) {
        AppearanceMode.SYSTEM -> isSystemInDarkTheme()
        AppearanceMode.DAY -> false
        AppearanceMode.NIGHT -> true
    }
    val scheme = remember(appearance.palette, dark) {
        (if (dark) appearance.palette.night else appearance.palette.day).scheme(dark)
    }
    CompositionLocalProvider(LocalIzAppearance provides appearance) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
