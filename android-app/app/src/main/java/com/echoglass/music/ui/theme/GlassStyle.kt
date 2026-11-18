package com.echoglass.music.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val PureWhite = Color(0xFFFFFFFF)
val SoftInk = Color(0xFF0F172A)
val MutedInk = Color(0xFF64748B)
val AccentIndigo = Color(0xFF4F46E5)
val AccentMint = Color(0xFF047857)
val AccentRose = Color(0xFFBE123C)

@Composable
fun TranslucentWhiteScaffoldBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PureWhite)
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0x22E0E7FF), Color.Transparent),
                    center = Offset(180f, 220f),
                    radius = 700f
                )
            )
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0x20E0F2FE), Color.Transparent),
                    center = Offset(920f, 1100f),
                    radius = 750f
                )
            ),
        content = content
    )
}

fun Modifier.glassSurface(cornerRadius: Dp = 24.dp): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    return this
        .shadow(
            elevation = 14.dp,
            shape = shape,
            ambientColor = Color(0x1864748B),
            spotColor = Color(0x1864748B)
        )
        .clip(shape)
        .background(
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.84f),
                    Color(0xFFF8FAFC).copy(alpha = 0.68f)
                )
            )
        )
        .border(
            width = 1.dp,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.95f),
                    Color(0xFFE2E8F0).copy(alpha = 0.85f)
                )
            ),
            shape = shape
        )
}
