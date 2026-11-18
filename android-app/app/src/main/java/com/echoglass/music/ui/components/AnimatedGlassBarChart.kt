package com.echoglass.music.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echoglass.music.domain.ChartBucket
import com.echoglass.music.ui.theme.AccentIndigo
import com.echoglass.music.ui.theme.MutedInk

@Composable
fun AnimatedGlassBarChart(
    buckets: List<ChartBucket>,
    modifier: Modifier = Modifier
) {
    val maxVal = (buckets.maxOfOrNull { it.valueMs } ?: 1L).coerceAtLeast(1L)
    var triggered by remember(buckets) { mutableStateOf(false) }
    LaunchedEffect(buckets) { triggered = true }

    val progress by animateFloatAsState(
        targetValue = if (triggered) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "chartAnim"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
        ) {
            if (buckets.isEmpty()) return@Canvas
            val count = buckets.size
            val slotWidth = size.width / count
            val barWidth = (slotWidth * 0.56f).coerceIn(6f, 36f)

            buckets.forEachIndexed { idx, bucket ->
                val ratio = (bucket.valueMs.toFloat() / maxVal.toFloat()) * progress
                val barHeight = (size.height * ratio).coerceAtLeast(if (bucket.valueMs > 0) 8f else 3f)
                val x = idx * slotWidth + (slotWidth - barWidth) / 2f
                val y = size.height - barHeight

                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = if (bucket.valueMs > 0) {
                            listOf(AccentIndigo.copy(alpha = 0.85f), AccentIndigo.copy(alpha = 0.35f))
                        } else {
                            listOf(Color(0xFFE2E8F0), Color(0xFFF1F5F9))
                        }
                    ),
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            buckets.forEach { bucket ->
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(text = bucket.label, fontSize = 10.sp, color = MutedInk, maxLines = 1)
                }
            }
        }
    }
}
