package com.luma.remote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.luma.remote.ui.theme.AccentPalette

internal val Ink:Color @Composable get()=MaterialTheme.colorScheme.background
internal val Panel:Color @Composable get()=MaterialTheme.colorScheme.surface
internal val Raised:Color @Composable get()=MaterialTheme.colorScheme.surfaceVariant
internal val Accent:Color @Composable get()=MaterialTheme.colorScheme.primary
internal val Muted:Color @Composable get()=MaterialTheme.colorScheme.onSurfaceVariant

@Composable internal fun OpenTVTheme(hex:String,content:@Composable ()->Unit) {
    val palette=remember(hex) { AccentPalette.from(hex) }
    val scheme=remember(palette) { darkColorScheme(
        primary=Color(palette.accent),onPrimary=Color(palette.background),background=Color(palette.background),onBackground=Color(palette.text),
        surface=Color(palette.surface),onSurface=Color(palette.text),surfaceVariant=Color(palette.raised),onSurfaceVariant=Color(palette.muted),
        secondary=Color(palette.accent),onSecondary=Color(palette.background),primaryContainer=Color(palette.raised),onPrimaryContainer=Color(palette.accent),
        secondaryContainer=Color(palette.raised),onSecondaryContainer=Color(palette.text),outline=Color(palette.outline),error=Color(0xFFFFB4AB)
    ) }
    MaterialTheme(colorScheme=scheme,typography=Typography(),content=content)
}

@Composable internal fun RemoteMark(modifier:Modifier=Modifier) {
    val accent=Accent;val ink=Ink
    Canvas(modifier.aspectRatio(1f)) {
        val u=size.minDimension/24f
        drawRoundRect(accent,Offset(5*u,u),Size(14*u,22*u),CornerRadius(4*u))
        drawCircle(ink,u,Offset(12*u,5*u));drawCircle(ink,4*u,Offset(12*u,12*u));drawCircle(accent,2*u,Offset(12*u,12*u))
        drawRect(ink,Offset(8*u,18*u),Size(3*u,2*u));drawRect(ink,Offset(13*u,18*u),Size(3*u,2*u))
    }
}
