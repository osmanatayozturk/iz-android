package com.atay.iz.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Forest = Color(0xFF28604D)
val Leaf = Color(0xFFDCE9D5)
val Ink = Color(0xFF26382F)
val Paper = Color(0xFFF7F7F2)
val Muted = Color(0xFF6E7C73)
val Clay = Color(0xFFAD5D3E)

@Composable
fun IzTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Forest, onPrimary = Color.White, primaryContainer = Leaf,
            onPrimaryContainer = Ink, background = Paper, onBackground = Ink,
            surface = Paper, onSurface = Ink, surfaceVariant = Color(0xFFECEFE7),
            onSurfaceVariant = Muted, outline = Color(0xFFD7DED4),
            secondary = Clay, secondaryContainer = Color(0xFFF4E7DC),
        ),
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 36.sp, lineHeight = 40.sp),
            headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 29.sp, lineHeight = 35.sp),
            titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
            titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        ), content = content,
    )
}
