package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Clean vector logo mark representing Chrome Web Store / Chrome Web Extensions.
 */
@Composable
fun ChromeStoreLogoMark(
    size: Dp = 28.dp,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(size)) {
        val diameter = this.size.minDimension
        val radius = diameter / 2f
        val stroke = diameter * 0.24f
        val arcRectSize = Size(diameter - stroke, diameter - stroke)
        val topLeft = Offset(stroke / 2f, stroke / 2f)

        // Chrome Red top segment
        drawArc(
            color = Color(0xFFEA4335),
            startAngle = 210f,
            sweepAngle = 120f,
            useCenter = false,
            topLeft = topLeft,
            size = arcRectSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
        // Chrome Yellow right segment
        drawArc(
            color = Color(0xFFFBBC05),
            startAngle = 330f,
            sweepAngle = 120f,
            useCenter = false,
            topLeft = topLeft,
            size = arcRectSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
        // Chrome Green left-bottom segment
        drawArc(
            color = Color(0xFF34A853),
            startAngle = 90f,
            sweepAngle = 120f,
            useCenter = false,
            topLeft = topLeft,
            size = arcRectSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
        // White ring + Google Blue center core
        drawCircle(
            color = Color.White,
            radius = radius * 0.44f
        )
        drawCircle(
            color = Color(0xFF4285F4),
            radius = radius * 0.34f
        )
    }
}

/**
 * Clean vector logo mark representing Microsoft Edge Add-ons.
 */
@Composable
fun EdgeAddonsLogoMark(
    size: Dp = 28.dp,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(size)) {
        val d = this.size.minDimension
        val center = Offset(d / 2f, d / 2f)
        val r = d * 0.45f

        // Base ocean-blue globe
        drawCircle(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF0078D4), Color(0xFF0A58A8)),
                start = Offset(0f, d),
                end = Offset(d, 0f)
            ),
            radius = r,
            center = center
        )

        // Teal/mint top wave crest characteristic of Edge
        val wave = Path().apply {
            moveTo(d * 0.14f, d * 0.48f)
            cubicTo(
                d * 0.18f, d * 0.08f,
                d * 0.84f, d * 0.06f,
                d * 0.90f, d * 0.44f
            )
            cubicTo(
                d * 0.72f, d * 0.30f,
                d * 0.44f, d * 0.38f,
                d * 0.44f, d * 0.56f
            )
            cubicTo(
                d * 0.44f, d * 0.70f,
                d * 0.62f, d * 0.80f,
                d * 0.80f, d * 0.74f
            )
            cubicTo(
                d * 0.56f, d * 0.96f,
                d * 0.10f, d * 0.82f,
                d * 0.14f, d * 0.48f
            )
            close()
        }
        drawPath(
            path = wave,
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF5CE1A6), Color(0xFF1EC8F5), Color(0xFF0078D4)),
                start = Offset(d * 0.2f, d * 0.1f),
                end = Offset(d * 0.8f, d * 0.85f)
            )
        )

        // Inner dark cutout creating the crisp Edge swirl
        drawCircle(
            color = Color(0xFF0B3C6F),
            radius = d * 0.16f,
            center = Offset(d * 0.54f, d * 0.55f)
        )
    }
}
