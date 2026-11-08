package dev.goaltracker.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.ViewQuilt
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * How the app draws, apart from its colors. Each look picks one:
 * - FANTASY: carved tablets, Cinzel, painted art (Dark fantasy and Parchment).
 * - PIXEL: square windows with hard shadows, a pixel font, art drawn on a coarse grid, segmented bars.
 * - NEON: chamfered glass panels with a glow, Orbitron, art drawn as lit outlines, thin bars with ticks.
 * - CLEAN: plain rounded cards, the system font, flat one-color art, thin bars and quiet effects.
 */
enum class Style { FANTASY, PIXEL, NEON, CLEAN }

val LocalStyle = compositionLocalOf { Style.FANTASY }

// ---- Shapes ----

internal fun cardShape(style: Style): Shape = when (style) {
    Style.FANTASY -> TabletShape
    Style.PIXEL -> CutCornerShape(4.dp)
    Style.NEON -> CutCornerShape(topStart = 14.dp, topEnd = 0.dp, bottomEnd = 14.dp, bottomStart = 0.dp)
    Style.CLEAN -> RoundedCornerShape(16.dp)
}

internal fun shapesFor(style: Style): Shapes = when (style) {
    Style.FANTASY -> SoftShapes
    Style.PIXEL -> Shapes(
        extraSmall = CutCornerShape(2.dp),
        small = CutCornerShape(3.dp),
        medium = CutCornerShape(4.dp),
        large = CutCornerShape(6.dp),
        extraLarge = CutCornerShape(8.dp),
    )
    Style.NEON -> Shapes(
        extraSmall = CutCornerShape(4.dp),
        small = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
        medium = CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp),
        large = CutCornerShape(topStart = 16.dp, bottomEnd = 16.dp),
        extraLarge = CutCornerShape(topStart = 22.dp, bottomEnd = 22.dp),
    )
    Style.CLEAN -> Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(14.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )
}

/** A flat-topped hexagon, for the Neon level badge. */
internal val HexagonShape: Shape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    moveTo(w * 0.25f, 0f); lineTo(w * 0.75f, 0f); lineTo(w, h / 2f)
    lineTo(w * 0.75f, h); lineTo(w * 0.25f, h); lineTo(0f, h / 2f); close()
}

/** The shape of small pills such as the coin chip. */
internal fun pillShape(style: Style): Shape = when (style) {
    Style.PIXEL -> CutCornerShape(3.dp)
    Style.NEON -> CutCornerShape(6.dp)
    else -> RoundedCornerShape(percent = 50)
}

internal fun badgeShape(style: Style): Shape = when (style) {
    Style.PIXEL -> CutCornerShape(6.dp)
    Style.NEON -> HexagonShape
    else -> CircleShape
}

// ---- Fonts ----

internal val PixelFont = FontFamily(Font(R.font.silkscreen, FontWeight.Normal), Font(R.font.silkscreen, FontWeight.Bold))

internal val NeonFont = FontFamily(
    Font(R.font.orbitron, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.orbitron, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.orbitron, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

/** The font a look uses for titles. Settings shows each look's name in its own font. */
internal fun displayFont(style: Style): FontFamily = when (style) {
    Style.FANTASY -> DisplayFont
    Style.PIXEL -> PixelFont
    Style.NEON -> NeonFont
    Style.CLEAN -> FontFamily.Default
}

/** Titles and big numbers change with the look; body text stays in the system font so it reads well. */
internal fun typographyFor(style: Style): Typography = when (style) {
    Style.FANTASY -> SoftTypography
    Style.PIXEL -> Typography().let {
        val f = PixelFont
        it.copy(
            displayLarge = it.displayLarge.copy(fontFamily = f),
            displayMedium = it.displayMedium.copy(fontFamily = f),
            displaySmall = it.displaySmall.copy(fontFamily = f),
            headlineLarge = it.headlineLarge.copy(fontFamily = f),
            headlineMedium = it.headlineMedium.copy(fontFamily = f),
            headlineSmall = it.headlineSmall.copy(fontFamily = f),
            titleLarge = it.titleLarge.copy(fontFamily = f),
            titleMedium = it.titleMedium.copy(fontWeight = FontWeight.Bold),
            labelLarge = it.labelLarge.copy(fontWeight = FontWeight.Bold),
        )
    }
    Style.NEON -> Typography().let {
        val f = NeonFont
        it.copy(
            displayLarge = it.displayLarge.copy(fontFamily = f, fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
            displayMedium = it.displayMedium.copy(fontFamily = f, fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
            displaySmall = it.displaySmall.copy(fontFamily = f, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp),
            headlineLarge = it.headlineLarge.copy(fontFamily = f, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp),
            headlineMedium = it.headlineMedium.copy(fontFamily = f, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp),
            headlineSmall = it.headlineSmall.copy(fontFamily = f, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
            titleLarge = it.titleLarge.copy(fontFamily = f, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
            titleMedium = it.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
            labelLarge = it.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
        )
    }
    Style.CLEAN -> Typography().let {
        it.copy(
            displayLarge = it.displayLarge.copy(fontWeight = FontWeight.Light),
            displayMedium = it.displayMedium.copy(fontWeight = FontWeight.Light),
            displaySmall = it.displaySmall.copy(fontWeight = FontWeight.Normal),
            headlineLarge = it.headlineLarge.copy(fontWeight = FontWeight.Medium),
            headlineMedium = it.headlineMedium.copy(fontWeight = FontWeight.Medium),
            headlineSmall = it.headlineSmall.copy(fontWeight = FontWeight.Medium),
            titleLarge = it.titleLarge.copy(fontWeight = FontWeight.Medium),
            titleMedium = it.titleMedium.copy(fontWeight = FontWeight.Medium),
        )
    }
}

// ---- Drawing helpers ----

/**
 * Draws [content] (which paints on a `grid` by `grid` square) onto a tiny bitmap, makes every
 * pixel fully on or off, and scales it up without smoothing, so anything becomes pixel art.
 */
internal fun DrawScope.drawPixelated(grid: Int, content: DrawScope.() -> Unit) {
    val bitmap = ImageBitmap(grid, grid)
    CanvasDrawScope().draw(this, layoutDirection, androidx.compose.ui.graphics.Canvas(bitmap), Size(grid.toFloat(), grid.toFloat())) {
        content()
    }
    val raw = bitmap.asAndroidBitmap()
    val pixels = IntArray(grid * grid)
    raw.getPixels(pixels, 0, grid, 0, 0, grid, grid)
    for (i in pixels.indices) {
        val alpha = pixels[i] ushr 24
        pixels[i] = if (alpha < 110) 0 else pixels[i] or (0xFF shl 24)
    }
    raw.setPixels(pixels, 0, grid, 0, 0, grid, grid)
    val side = size.minDimension.roundToInt()
    drawImage(
        bitmap,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(grid, grid),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(side, side),
        filterQuality = FilterQuality.None,
    )
}

/**
 * A DrawScope that turns every filled shape into a lit outline. Dark colors (eyes, keyholes)
 * become the [light] color so details still show. Used twice: a wide faint pass for the glow,
 * then a thin bright one.
 */
private class NeonScope(
    private val base: DrawScope,
    private val width: Float,
    private val alphaScale: Float,
    private val light: Color,
) : DrawScope by base {
    private fun lit(c: Color) = if (c.luminance() < 0.12f) light else c
    private val line get() = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)

    override fun drawPath(path: Path, color: Color, alpha: Float, style: DrawStyle, colorFilter: ColorFilter?, blendMode: BlendMode) =
        base.drawPath(path, lit(color), alpha * alphaScale, line, colorFilter, blendMode)

    override fun drawCircle(color: Color, radius: Float, center: Offset, alpha: Float, style: DrawStyle, colorFilter: ColorFilter?, blendMode: BlendMode) =
        base.drawCircle(lit(color), radius, center, alpha * alphaScale, line, colorFilter, blendMode)

    override fun drawRect(color: Color, topLeft: Offset, size: Size, alpha: Float, style: DrawStyle, colorFilter: ColorFilter?, blendMode: BlendMode) =
        base.drawRect(lit(color), topLeft, size, alpha * alphaScale, line, colorFilter, blendMode)

    override fun drawRoundRect(
        color: Color, topLeft: Offset, size: Size, cornerRadius: androidx.compose.ui.geometry.CornerRadius,
        style: DrawStyle, alpha: Float, colorFilter: ColorFilter?, blendMode: BlendMode,
    ) = base.drawRoundRect(lit(color), topLeft, size, cornerRadius, line, alpha * alphaScale, colorFilter, blendMode)

    override fun drawLine(
        color: Color, start: Offset, end: Offset, strokeWidth: Float, cap: StrokeCap,
        pathEffect: PathEffect?, alpha: Float, colorFilter: ColorFilter?, blendMode: BlendMode,
    ) = base.drawLine(lit(color), start, end, maxOf(strokeWidth, width), StrokeCap.Round, pathEffect, alpha * alphaScale, colorFilter, blendMode)
}

/** A DrawScope that paints every light color in one [tint], for flat one-color art. Dark cut-outs stay. */
private class MonoScope(private val base: DrawScope, private val tint: Color) : DrawScope by base {
    private fun flat(c: Color) = if (c.luminance() < 0.12f) c else tint.copy(alpha = c.alpha)

    override fun drawPath(path: Path, color: Color, alpha: Float, style: DrawStyle, colorFilter: ColorFilter?, blendMode: BlendMode) =
        base.drawPath(path, flat(color), alpha, style, colorFilter, blendMode)

    override fun drawCircle(color: Color, radius: Float, center: Offset, alpha: Float, style: DrawStyle, colorFilter: ColorFilter?, blendMode: BlendMode) =
        base.drawCircle(flat(color), radius, center, alpha, style, colorFilter, blendMode)

    override fun drawRect(color: Color, topLeft: Offset, size: Size, alpha: Float, style: DrawStyle, colorFilter: ColorFilter?, blendMode: BlendMode) =
        base.drawRect(flat(color), topLeft, size, alpha, style, colorFilter, blendMode)

    override fun drawRoundRect(
        color: Color, topLeft: Offset, size: Size, cornerRadius: androidx.compose.ui.geometry.CornerRadius,
        style: DrawStyle, alpha: Float, colorFilter: ColorFilter?, blendMode: BlendMode,
    ) = base.drawRoundRect(flat(color), topLeft, size, cornerRadius, style, alpha, colorFilter, blendMode)
}

/**
 * Draws a 24 by 24 picture in the current look: painted (fantasy), pixelated, as neon outlines,
 * or flat in one color ([tint]). [light] replaces dark details in Neon.
 */
internal fun DrawScope.drawStyled(style: Style, light: Color, tint: Color, picture: DrawScope.() -> Unit) {
    val unit = size.minDimension / 24f
    when (style) {
        Style.FANTASY -> scale(unit, unit, Offset.Zero) { picture() }
        Style.PIXEL -> drawPixelated(16) { scale(16f / 24f, 16f / 24f, Offset.Zero) { picture() } }
        Style.NEON -> scale(unit, unit, Offset.Zero) {
            NeonScope(this, 3.2f, 0.28f, light).picture()
            NeonScope(this, 1.3f, 1f, light).picture()
        }
        Style.CLEAN -> scale(unit, unit, Offset.Zero) { MonoScope(this, tint).picture() }
    }
}

// ---- Tab icons ----

/** The bottom-tab icons for each look. Fantasy keeps the original ones. */
internal fun tabIcon(style: Style, tab: String, fantasy: ImageVector): ImageVector = when (style) {
    Style.FANTASY -> fantasy
    Style.PIXEL -> when (tab) {
        "Today" -> Icons.Filled.Today
        "Plan" -> Icons.Filled.Checklist
        "Quests" -> Icons.Filled.SportsEsports
        else -> Icons.Filled.Favorite
    }
    Style.NEON -> when (tab) {
        "Today" -> Icons.Outlined.ViewQuilt
        "Plan" -> Icons.Outlined.Terminal
        "Quests" -> Icons.Outlined.Radar
        else -> Icons.Outlined.Memory
    }
    Style.CLEAN -> when (tab) {
        "Today" -> Icons.Outlined.Today
        "Plan" -> Icons.Outlined.Checklist
        "Quests" -> Icons.Outlined.Flag
        else -> Icons.Outlined.Person
    }
}

/** An icon in the current look: Pixel draws it on a coarse grid; the rest draw it normally. */
@Composable
fun StyledIcon(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    if (LocalStyle.current != Style.PIXEL) {
        Icon(icon, null, modifier, tint = tint)
        return
    }
    val painter = rememberVectorPainter(icon)
    Canvas(modifier.size(24.dp).clearAndSetSemantics {}) {
        drawPixelated(14) {
            with(painter) { draw(Size(14f, 14f), colorFilter = ColorFilter.tint(tint)) }
        }
    }
}

// ---- Celebrations ----

/**
 * The look's own celebration for a met target, drawn over the whole screen for under a second.
 * Pixel: square confetti that falls on a grid. Neon: rings of light and a scan line.
 * Clean: a small check that fades. Fantasy keeps its swelling check (see `CelebrationBurst`).
 */
@Composable
internal fun StyledCelebration(style: Style, trigger: Int) {
    val context = LocalContext.current
    val t = remember { Animatable(1f) }
    val scheme = MaterialTheme.colorScheme
    LaunchedEffect(trigger) {
        if (trigger == 0 || Feel.reducedMotion(context)) return@LaunchedEffect
        t.snapTo(0f)
        t.animateTo(1f, tween(if (style == Style.CLEAN) 650 else 1000, easing = LinearEasing))
    }
    val p = t.value
    if (p >= 1f) return
    val colors = listOf(scheme.primary, scheme.secondary, scheme.tertiary)
    Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        val c = Offset(size.width / 2f, size.height / 2f)
        when (style) {
            Style.PIXEL -> {
                // 12 frames a second, positions snapped to a 6dp grid.
                val frame = (p * 12f).toInt() / 12f
                val cell = 6.dp.toPx()
                for (i in 0 until 28) {
                    val angle = (i * 137.5f) * (PI / 180f).toFloat()
                    val speed = (0.35f + (i % 5) * 0.12f) * size.minDimension
                    val x = c.x + cos(angle) * speed * frame
                    val y = c.y + sin(angle) * speed * frame + frame * frame * size.height * 0.45f
                    val sx = (x / cell).toInt() * cell
                    val sy = (y / cell).toInt() * cell
                    drawRect(colors[i % 3].copy(alpha = 1f - frame * 0.6f), Offset(sx, sy), Size(cell, cell))
                }
            }
            Style.NEON -> {
                for (ring in 0 until 3) {
                    val local = ((p - ring * 0.15f) / 0.7f).coerceIn(0f, 1f)
                    if (local <= 0f || local >= 1f) continue
                    val r = local * size.minDimension * 0.55f
                    val a = 1f - local
                    drawCircle(scheme.primary.copy(alpha = a * 0.3f), r, c, style = Stroke(14.dp.toPx()))
                    drawCircle(scheme.primary.copy(alpha = a), r, c, style = Stroke(2.dp.toPx()))
                }
                // A scan line sweeping down.
                val y = p * size.height
                drawRect(scheme.secondary.copy(alpha = 0.18f * (1f - p)), Offset(0f, y - 30.dp.toPx()), Size(size.width, 30.dp.toPx()))
                drawLine(scheme.secondary.copy(alpha = 1f - p), Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
                // A flickering check.
                val flicker = if (((p * 20).toInt() % 3) == 0) 0.35f else 1f
                drawCheck(c, 48.dp.toPx(), scheme.primary.copy(alpha = flicker * (1f - p)), 4.dp.toPx())
            }
            Style.CLEAN -> {
                val a = if (p < 0.3f) p / 0.3f else 1f - (p - 0.3f) / 0.7f
                val s = 0.9f + 0.1f * minOf(1f, p / 0.3f)
                drawCircle(scheme.primaryContainer.copy(alpha = a), 40.dp.toPx() * s, c)
                drawCheck(c, 22.dp.toPx() * s, scheme.onPrimaryContainer.copy(alpha = a), 3.dp.toPx())
            }
            Style.FANTASY -> Unit
        }
    }
}

private fun DrawScope.drawCheck(center: Offset, half: Float, color: Color, width: Float) {
    val path = Path().apply {
        moveTo(center.x - half * 0.8f, center.y)
        lineTo(center.x - half * 0.2f, center.y + half * 0.6f)
        lineTo(center.x + half * 0.9f, center.y - half * 0.6f)
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Size of a bar in the current look; Clean and Neon are slimmer. */
internal fun barHeight(style: Style, asked: Dp): Dp = when (style) {
    Style.CLEAN -> minOf(asked, 8.dp)
    Style.NEON -> asked * 0.7f
    else -> asked
}
