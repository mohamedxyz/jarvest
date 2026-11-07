package dev.goaltracker.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Small pictures drawn in code, so the app has art without any image files. Each one is drawn on a
 * 24 by 24 grid and scaled to the size asked for. They are decoration: the words next to them carry
 * the meaning, so none of them is read out by a screen reader.
 */
@Composable
private fun Art(size: Dp, modifier: Modifier, draw: DrawScope.() -> Unit) {
    Canvas(modifier.size(size).clearAndSetSemantics {}) {
        val unit = this.size.minDimension / 24f
        withTransform({ scale(unit, unit, Offset.Zero) }) { draw() }
    }
}

private fun path(build: Path.() -> Unit) = Path().apply(build)

/** The color of a hero shield: bronze, silver, gold, then violet for the highest levels. */
fun shieldColor(level: Int): Color = when {
    level < 5 -> Color(0xFFD08A4E)
    level < 10 -> Color(0xFFC3CBDD)
    level < 20 -> Color(0xFFF2C14E)
    else -> Color(0xFFB69CFF)
}

/** A shield for the hero. Higher levels get a richer color and a star on the front. */
@Composable
fun ShieldArt(level: Int, size: Dp = 28.dp, modifier: Modifier = Modifier) {
    val metal = shieldColor(level)
    val dark = MaterialTheme.colorScheme.surfaceContainerHigh
    Art(size, modifier) {
        val body = path {
            moveTo(12f, 1.5f); lineTo(21f, 4.5f); lineTo(21f, 12f)
            cubicTo(21f, 17.5f, 17f, 21f, 12f, 22.8f)
            cubicTo(7f, 21f, 3f, 17.5f, 3f, 12f); lineTo(3f, 4.5f); close()
        }
        drawPath(body, metal)
        val face = path {
            moveTo(12f, 4f); lineTo(18.5f, 6.3f); lineTo(18.5f, 12f)
            cubicTo(18.5f, 16f, 15.8f, 18.8f, 12f, 20.3f)
            cubicTo(8.2f, 18.8f, 5.5f, 16f, 5.5f, 12f); lineTo(5.5f, 6.3f); close()
        }
        drawPath(face, dark)
        // A four-point spark in the middle.
        val spark = path {
            moveTo(12f, 7.5f); lineTo(13.4f, 10.6f); lineTo(16.5f, 12f); lineTo(13.4f, 13.4f)
            lineTo(12f, 16.5f); lineTo(10.6f, 13.4f); lineTo(7.5f, 12f); lineTo(10.6f, 10.6f); close()
        }
        drawPath(spark, metal)
    }
}

/** A horned boss face. */
@Composable
fun BossArt(size: Dp = 28.dp, modifier: Modifier = Modifier, defeated: Boolean = false) {
    val body = if (defeated) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.tertiary
    val eye = MaterialTheme.colorScheme.surface
    Art(size, modifier) {
        val head = path {
            moveTo(12f, 22f)
            cubicTo(6f, 22f, 4f, 17f, 4f, 13f)
            cubicTo(4f, 10f, 5.5f, 8f, 7.5f, 7f)
            lineTo(3.5f, 1.5f); lineTo(10f, 4.8f); lineTo(14f, 4.8f); lineTo(20.5f, 1.5f); lineTo(16.5f, 7f)
            cubicTo(18.5f, 8f, 20f, 10f, 20f, 13f)
            cubicTo(20f, 17f, 18f, 22f, 12f, 22f); close()
        }
        drawPath(head, body)
        if (defeated) {
            // Crossed-out eyes.
            drawLine(eye, Offset(6.8f, 11f), Offset(10f, 14f), strokeWidth = 1.4f)
            drawLine(eye, Offset(10f, 11f), Offset(6.8f, 14f), strokeWidth = 1.4f)
            drawLine(eye, Offset(14f, 11f), Offset(17.2f, 14f), strokeWidth = 1.4f)
            drawLine(eye, Offset(17.2f, 11f), Offset(14f, 14f), strokeWidth = 1.4f)
        } else {
            drawPath(path { moveTo(6.5f, 11f); lineTo(10.5f, 13f); lineTo(7f, 14.5f); close() }, eye)
            drawPath(path { moveTo(17.5f, 11f); lineTo(13.5f, 13f); lineTo(17f, 14.5f); close() }, eye)
        }
        // Teeth.
        drawPath(
            path {
                moveTo(8.5f, 18f); lineTo(9.5f, 20f); lineTo(10.5f, 18f); lineTo(12f, 20.4f)
                lineTo(13.5f, 18f); lineTo(14.5f, 20f); lineTo(15.5f, 18f); close()
            },
            eye,
        )
    }
}

/** A rolled scroll for the daily quest. A check mark shows when the quest is done. */
@Composable
fun QuestArt(done: Boolean, size: Dp = 28.dp, modifier: Modifier = Modifier) {
    val paper = MaterialTheme.colorScheme.secondary
    val ink = MaterialTheme.colorScheme.surface
    Art(size, modifier) {
        drawRoundRect(paper, Offset(5f, 3f), Size(14f, 18f), CornerRadius(2f, 2f))
        drawRoundRect(paper, Offset(3f, 2f), Size(4f, 20f), CornerRadius(2f, 2f))
        drawRoundRect(paper, Offset(17f, 2f), Size(4f, 20f), CornerRadius(2f, 2f))
        drawRoundRect(ink.copy(alpha = 0.35f), Offset(4f, 4f), Size(2f, 16f), CornerRadius(1f, 1f))
        if (done) {
            drawPath(
                path { moveTo(8.5f, 12.5f); lineTo(11f, 15f); lineTo(15.5f, 9f) },
                ink,
                style = Stroke(width = 2.2f),
            )
        } else {
            drawLine(ink, Offset(9f, 8f), Offset(15f, 8f), strokeWidth = 1.6f)
            drawLine(ink, Offset(9f, 12f), Offset(15f, 12f), strokeWidth = 1.6f)
            drawLine(ink, Offset(9f, 16f), Offset(13f, 16f), strokeWidth = 1.6f)
        }
    }
}

/** A flame for streaks and Blaze days. */
@Composable
fun FlameArt(size: Dp = 28.dp, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.tertiary) {
    val core = MaterialTheme.colorScheme.secondary
    Art(size, modifier) {
        drawPath(
            path {
                moveTo(12f, 1.5f)
                cubicTo(13f, 6f, 19f, 8.5f, 19f, 15f)
                cubicTo(19f, 19.5f, 15.9f, 22.5f, 12f, 22.5f)
                cubicTo(8.1f, 22.5f, 5f, 19.5f, 5f, 15.5f)
                cubicTo(5f, 12.5f, 6.8f, 11f, 8f, 9.2f)
                cubicTo(8.6f, 11f, 9.4f, 11.8f, 10.2f, 12f)
                cubicTo(10.2f, 8f, 10.6f, 4.5f, 12f, 1.5f); close()
            },
            color,
        )
        drawPath(
            path {
                moveTo(12f, 12.5f)
                cubicTo(12.4f, 14.5f, 15f, 15.5f, 15f, 18.2f)
                cubicTo(15f, 20.2f, 13.7f, 21.5f, 12f, 21.5f)
                cubicTo(10.3f, 21.5f, 9f, 20.2f, 9f, 18.5f)
                cubicTo(9f, 16.5f, 11f, 15.5f, 12f, 12.5f); close()
            },
            core,
        )
    }
}

/** A gold coin with a notch. */
@Composable
fun CoinArt(size: Dp = 20.dp, modifier: Modifier = Modifier) {
    val gold = MaterialTheme.colorScheme.secondary
    val shade = MaterialTheme.colorScheme.onSecondary
    Art(size, modifier) {
        drawCircle(gold, radius = 10.5f, center = Offset(12f, 12f))
        drawCircle(shade.copy(alpha = 0.45f), radius = 7f, center = Offset(12f, 12f), style = Stroke(width = 1.5f))
        drawLine(shade.copy(alpha = 0.6f), Offset(12f, 8f), Offset(12f, 16f), strokeWidth = 1.8f)
    }
}

/** A treasure chest for the treat jar and the shop. */
@Composable
fun ChestArt(size: Dp = 28.dp, modifier: Modifier = Modifier) {
    val wood = MaterialTheme.colorScheme.tertiaryContainer
    val gold = MaterialTheme.colorScheme.secondary
    val keyhole = MaterialTheme.colorScheme.surface
    Art(size, modifier) {
        drawPath(
            path { moveTo(3f, 10f); cubicTo(3f, 5.5f, 7f, 3.5f, 12f, 3.5f); cubicTo(17f, 3.5f, 21f, 5.5f, 21f, 10f); close() },
            wood,
        )
        drawRoundRect(wood, Offset(3f, 10f), Size(18f, 10.5f), CornerRadius(1.5f, 1.5f))
        drawRect(gold, Offset(3f, 9.2f), Size(18f, 1.8f))
        drawRoundRect(gold, Offset(10.2f, 9f), Size(3.6f, 5.2f), CornerRadius(0.8f, 0.8f))
        drawCircle(keyhole, 0.9f, Offset(12f, 11.8f))
    }
}
