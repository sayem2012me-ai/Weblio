package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.data.db.ExtensionEntity
import com.example.engine.ExtensionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

private val BUILTIN_LOGO_IDS = setOf(
    "weblio.darkreader.engine",
    "weblio.ublock.cosmetic",
    "weblio.privacy.badger",
    "weblio.tampermonkey.host",
    "weblio.bitwarden.bridge",
    "weblio.cws.sponsorblock",
    "weblio.cws.translator",
    "weblio.cws.jsonformatter",
    "weblio.cws.readermode",
    "weblio.cws.cookiehider"
)

fun hasBuiltInExtensionLogo(extId: String): Boolean = extId in BUILTIN_LOGO_IDS

/**
 * Renders the genuine logo of an installed or catalog extension:
 * 1. Local raster icon declared inside `manifest.json` (`icons` / `action.default_icon`) for unpacked `.zip`/`.crx` bundles.
 * 2. Remote `@icon` / `@iconURL` / `@icon64` from Tampermonkey `==UserScript==` metadata.
 * 3. Bespoke high-craft vector marks for all built-in/catalog extensions.
 * 4. Crisp deterministic gradient + monogram badge fallback for custom scripts/bundles without an icon asset.
 */
@Composable
fun ExtensionLogo(
    extension: ExtensionEntity,
    size: Dp = 36.dp,
    dimWhenDisabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val localIconFile by produceState<File?>(
        initialValue = null,
        key1 = extension.installPath,
        key2 = extension.extId
    ) {
        value = if (extension.installPath.isNotBlank()) {
            withContext(Dispatchers.IO) {
                ExtensionEngine.resolveExtensionIconFile(extension.installPath)
            }
        } else {
            null
        }
    }

    val remoteIconUrl = remember(extension.sourceType, extension.scriptContent) {
        if (extension.sourceType == "USERSCRIPT_JS") {
            ExtensionEngine.resolveUserScriptIconUrl(extension.scriptContent)
        } else {
            null
        }
    }

    val modelData: Any? = localIconFile ?: remoteIconUrl
    val visualAlpha = if (dimWhenDisabled && !extension.enabled) 0.58f else 1f
    val shape = RoundedCornerShape(size * 0.26f)

    Box(
        modifier = modifier
            .size(size)
            .alpha(visualAlpha)
            .clip(shape),
        contentAlignment = Alignment.Center
    ) {
        if (modelData != null) {
            val request = remember(context, modelData) {
                ImageRequest.Builder(context)
                    .data(modelData)
                    .crossfade(true)
                    .build()
            }
            SubcomposeAsyncImage(
                model = request,
                contentDescription = extension.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    BuiltInOrMonogramExtensionLogo(extension = extension, size = size)
                },
                error = {
                    BuiltInOrMonogramExtensionLogo(extension = extension, size = size)
                }
            )
        } else {
            BuiltInOrMonogramExtensionLogo(extension = extension, size = size)
        }
    }
}

@Composable
private fun BuiltInOrMonogramExtensionLogo(
    extension: ExtensionEntity,
    size: Dp
) {
    when (extension.extId) {
        "weblio.darkreader.engine" -> DarkReaderLogoMark(size = size)
        "weblio.ublock.cosmetic" -> UBlockLogoMark(size = size)
        "weblio.privacy.badger" -> PrivacyBadgerLogoMark(size = size)
        "weblio.tampermonkey.host" -> TampermonkeyLogoMark(size = size)
        "weblio.bitwarden.bridge" -> BitwardenLogoMark(size = size)
        "weblio.cws.sponsorblock" -> SponsorBlockLogoMark(size = size)
        "weblio.cws.translator" -> TranslatorLogoMark(size = size)
        "weblio.cws.jsonformatter" -> JsonFormatterLogoMark(size = size)
        "weblio.cws.readermode" -> ReaderModeLogoMark(size = size)
        "weblio.cws.cookiehider" -> CookieHiderLogoMark(size = size)
        else -> CustomExtensionMonogramLogo(extension = extension, size = size)
    }
}

@Composable
private fun DarkReaderLogoMark(size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val d = this.size.minDimension
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF141E24), Color(0xFF1F2F39)),
                start = Offset.Zero,
                end = Offset(d, d)
            ),
            cornerRadius = CornerRadius(d * 0.26f, d * 0.26f)
        )
        // Iconic Dark Reader spectacles silhouette in crimson coral
        val lensColor = Color(0xFFE9564E)
        val r = d * 0.165f
        val cy = d * 0.54f
        val leftX = d * 0.31f
        val rightX = d * 0.69f
        drawCircle(color = lensColor, radius = r, center = Offset(leftX, cy), style = Stroke(width = d * 0.085f))
        drawCircle(color = lensColor, radius = r, center = Offset(rightX, cy), style = Stroke(width = d * 0.085f))
        // Inner half-moon dark shading
        drawArc(
            color = Color(0xFFF68A7E),
            startAngle = -90f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(leftX - r * 0.65f, cy - r * 0.65f),
            size = Size(r * 1.3f, r * 1.3f)
        )
        drawArc(
            color = Color(0xFFF68A7E),
            startAngle = -90f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(rightX - r * 0.65f, cy - r * 0.65f),
            size = Size(r * 1.3f, r * 1.3f)
        )
        // Spectacle bridge
        drawArc(
            color = lensColor,
            startAngle = 200f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(d * 0.42f, d * 0.44f),
            size = Size(d * 0.16f, d * 0.14f),
            style = Stroke(width = d * 0.075f, cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun UBlockLogoMark(size: Dp) {
    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val d = this.size.minDimension
            val shield = Path().apply {
                moveTo(d * 0.50f, d * 0.08f)
                lineTo(d * 0.86f, d * 0.22f)
                lineTo(d * 0.86f, d * 0.54f)
                cubicTo(d * 0.86f, d * 0.76f, d * 0.66f, d * 0.88f, d * 0.50f, d * 0.94f)
                cubicTo(d * 0.34f, d * 0.88f, d * 0.14f, d * 0.76f, d * 0.14f, d * 0.54f)
                lineTo(d * 0.14f, d * 0.22f)
                close()
            }
            drawPath(
                path = shield,
                brush = Brush.verticalGradient(listOf(Color(0xFFC62828), Color(0xFF881111)))
            )
            drawPath(
                path = shield,
                color = Color(0xFFFFCDD2).copy(alpha = 0.45f),
                style = Stroke(width = d * 0.045f, join = StrokeJoin.Round)
            )
        }
        Text(
            text = "uB",
            color = Color.White,
            fontSize = (size.value * 0.36f).sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
private fun PrivacyBadgerLogoMark(size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val d = this.size.minDimension
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFFFFB300), Color(0xFFE65100)),
                start = Offset.Zero,
                end = Offset(d, d)
            ),
            cornerRadius = CornerRadius(d * 0.26f, d * 0.26f)
        )
        // Badger face emblem
        drawOval(
            color = Color(0xFFFAF6EE),
            topLeft = Offset(d * 0.18f, d * 0.20f),
            size = Size(d * 0.64f, d * 0.60f)
        )
        // Dark badger stripes over eyes
        val leftStripe = Path().apply {
            moveTo(d * 0.24f, d * 0.28f)
            lineTo(d * 0.44f, d * 0.34f)
            lineTo(d * 0.40f, d * 0.66f)
            lineTo(d * 0.22f, d * 0.56f)
            close()
        }
        val rightStripe = Path().apply {
            moveTo(d * 0.76f, d * 0.28f)
            lineTo(d * 0.56f, d * 0.34f)
            lineTo(d * 0.60f, d * 0.66f)
            lineTo(d * 0.78f, d * 0.56f)
            close()
        }
        drawPath(leftStripe, color = Color(0xFF2D231C))
        drawPath(rightStripe, color = Color(0xFF2D231C))
        drawCircle(color = Color.White, radius = d * 0.055f, center = Offset(d * 0.34f, d * 0.47f))
        drawCircle(color = Color.White, radius = d * 0.055f, center = Offset(d * 0.66f, d * 0.47f))
        drawCircle(color = Color(0xFF2D231C), radius = d * 0.06f, center = Offset(d * 0.50f, d * 0.71f))
    }
}

@Composable
private fun TampermonkeyLogoMark(size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val d = this.size.minDimension
        drawRoundRect(
            color = Color(0xFF181A1F),
            cornerRadius = CornerRadius(d * 0.26f, d * 0.26f)
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.85f),
            topLeft = Offset(d * 0.10f, d * 0.10f),
            size = Size(d * 0.80f, d * 0.80f),
            cornerRadius = CornerRadius(d * 0.18f, d * 0.18f),
            style = Stroke(width = d * 0.07f)
        )
        // Signature two white circles along the bottom half
        drawCircle(color = Color.White, radius = d * 0.125f, center = Offset(d * 0.34f, d * 0.64f))
        drawCircle(color = Color.White, radius = d * 0.125f, center = Offset(d * 0.66f, d * 0.64f))
    }
}

@Composable
private fun BitwardenLogoMark(size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val d = this.size.minDimension
        val shield = Path().apply {
            moveTo(d * 0.16f, d * 0.16f)
            lineTo(d * 0.84f, d * 0.16f)
            lineTo(d * 0.84f, d * 0.55f)
            cubicTo(d * 0.84f, d * 0.76f, d * 0.66f, d * 0.87f, d * 0.50f, d * 0.93f)
            cubicTo(d * 0.34f, d * 0.87f, d * 0.16f, d * 0.76f, d * 0.16f, d * 0.55f)
            close()
        }
        drawPath(path = shield, color = Color(0xFF175DDC))
        // Right inner half cutout for Bitwarden vault contrast
        val rightHalf = Path().apply {
            moveTo(d * 0.50f, d * 0.25f)
            lineTo(d * 0.75f, d * 0.25f)
            lineTo(d * 0.75f, d * 0.54f)
            cubicTo(d * 0.75f, d * 0.69f, d * 0.62f, d * 0.78f, d * 0.50f, d * 0.83f)
            close()
        }
        drawPath(path = rightHalf, color = Color.White)
    }
}

@Composable
private fun SponsorBlockLogoMark(size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val d = this.size.minDimension
        val shield = Path().apply {
            moveTo(d * 0.50f, d * 0.08f)
            lineTo(d * 0.86f, d * 0.22f)
            lineTo(d * 0.86f, d * 0.54f)
            cubicTo(d * 0.86f, d * 0.76f, d * 0.66f, d * 0.88f, d * 0.50f, d * 0.94f)
            cubicTo(d * 0.34f, d * 0.88f, d * 0.14f, d * 0.76f, d * 0.14f, d * 0.54f)
            lineTo(d * 0.14f, d * 0.22f)
            close()
        }
        drawPath(
            path = shield,
            brush = Brush.verticalGradient(listOf(Color(0xFFFF3B30), Color(0xFFB71C1C)))
        )
        val play = Path().apply {
            moveTo(d * 0.42f, d * 0.34f)
            lineTo(d * 0.68f, d * 0.50f)
            lineTo(d * 0.42f, d * 0.66f)
            close()
        }
        drawPath(path = play, color = Color.White)
    }
}

@Composable
private fun TranslatorLogoMark(size: Dp) {
    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val d = this.size.minDimension
            // Back slate tile
            drawRoundRect(
                color = Color(0xFFDCE3EC),
                topLeft = Offset(d * 0.34f, d * 0.24f),
                size = Size(d * 0.58f, d * 0.64f),
                cornerRadius = CornerRadius(d * 0.14f, d * 0.14f)
            )
            // Front Google blue tile
            drawRoundRect(
                color = Color(0xFF4285F4),
                topLeft = Offset(d * 0.08f, d * 0.12f),
                size = Size(d * 0.60f, d * 0.66f),
                cornerRadius = CornerRadius(d * 0.14f, d * 0.14f)
            )
        }
        Text(
            text = "A",
            color = Color.White,
            fontSize = (size.value * 0.34f).sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = size * 0.18f, top = size * 0.14f)
        )
        Text(
            text = "文",
            color = Color(0xFF1F2937),
            fontSize = (size.value * 0.28f).sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = size * 0.15f, bottom = size * 0.14f)
        )
    }
}

@Composable
private fun JsonFormatterLogoMark(size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.26f))
            .background(
                Brush.linearGradient(listOf(Color(0xFF0F172A), Color(0xFF1E293B)))
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "{ }",
            color = Color(0xFF38BDF8),
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
private fun ReaderModeLogoMark(size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val d = this.size.minDimension
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF3B5BDB), Color(0xFF2B44B8)),
                start = Offset.Zero,
                end = Offset(d, d)
            ),
            cornerRadius = CornerRadius(d * 0.26f, d * 0.26f)
        )
        // White page sheet
        drawRoundRect(
            color = Color(0xFFF8FAFC),
            topLeft = Offset(d * 0.22f, d * 0.18f),
            size = Size(d * 0.56f, d * 0.64f),
            cornerRadius = CornerRadius(d * 0.08f, d * 0.08f)
        )
        // Crisp typography lines
        val lineColor = Color(0xFF334155)
        drawLine(lineColor, Offset(d * 0.31f, d * 0.34f), Offset(d * 0.69f, d * 0.34f), strokeWidth = d * 0.06f, cap = StrokeCap.Round)
        drawLine(lineColor, Offset(d * 0.31f, d * 0.49f), Offset(d * 0.69f, d * 0.49f), strokeWidth = d * 0.06f, cap = StrokeCap.Round)
        drawLine(Color(0xFF64748B), Offset(d * 0.31f, d * 0.64f), Offset(d * 0.55f, d * 0.64f), strokeWidth = d * 0.06f, cap = StrokeCap.Round)
    }
}

@Composable
private fun CookieHiderLogoMark(size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val d = this.size.minDimension
        drawRoundRect(
            color = Color(0xFF232732),
            cornerRadius = CornerRadius(d * 0.26f, d * 0.26f)
        )
        // Warm biscuit circle
        drawCircle(
            color = Color(0xFFC68642),
            radius = d * 0.30f,
            center = Offset(d * 0.50f, d * 0.50f)
        )
        // Chocolate chips
        val chipColor = Color(0xFF4A2C16)
        drawCircle(chipColor, radius = d * 0.045f, center = Offset(d * 0.41f, d * 0.40f))
        drawCircle(chipColor, radius = d * 0.045f, center = Offset(d * 0.59f, d * 0.43f))
        drawCircle(chipColor, radius = d * 0.045f, center = Offset(d * 0.48f, d * 0.60f))
        // Prohibition ring + slash
        val banColor = Color(0xFFEF4444)
        drawCircle(
            color = banColor,
            radius = d * 0.34f,
            center = Offset(d * 0.50f, d * 0.50f),
            style = Stroke(width = d * 0.08f)
        )
        drawLine(
            color = banColor,
            start = Offset(d * 0.26f, d * 0.74f),
            end = Offset(d * 0.74f, d * 0.26f),
            strokeWidth = d * 0.08f,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun CustomExtensionMonogramLogo(
    extension: ExtensionEntity,
    size: Dp
) {
    val palette = listOf(
        Color(0xFF1D6FFF) to Color(0xFF00B4D8),
        Color(0xFF7C3AED) to Color(0xFFC084FC),
        Color(0xFF059669) to Color(0xFF34D399),
        Color(0xFFEA580C) to Color(0xFFFB923C),
        Color(0xFFDB2777) to Color(0xFFF472B6),
        Color(0xFF2563EB) to Color(0xFF60A5FA)
    )
    val seed = abs((extension.extId.ifBlank { extension.name }).hashCode())
    val (c1, c2) = palette[seed % palette.size]
    val words = extension.name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    val monogram = when {
        words.size >= 2 -> "${words[0].first().uppercaseChar()}${words[1].first().uppercaseChar()}"
        words.size == 1 -> words[0].take(2).uppercase()
        extension.sourceType == "USERSCRIPT_JS" -> "JS"
        else -> "EX"
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.26f))
            .background(Brush.linearGradient(listOf(c1, c2))),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = monogram,
            color = Color.White,
            fontSize = (size.value * 0.38f).sp,
            fontWeight = FontWeight.Bold
        )
    }
}

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
