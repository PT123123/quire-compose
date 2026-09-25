package dev.quire.compose.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The two icons Material's core set does not ship.
 *
 * `material-icons-extended` carries undo and redo, and it costs megabytes for
 * two glyphs — in a UI whose SPEC ranks low RAM above maintainability and
 * feature count, drawing them is the cheaper trade. It is also what the Slint
 * shell does: its icons are original vector paths, not a font.
 *
 * Both are stroke-only and unbaked, so `Icon(tint = …)` colours them.
 */
private fun curveIcon(name: String, mirrored: Boolean): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // An arrowhead, a shaft, and a hook that curves back under it — the
            // shape everyone already reads as "undo"; `redo` is its mirror.
            if (mirrored) {
                moveTo(15f, 6f); lineTo(20f, 10.5f); lineTo(15f, 15f)
                moveTo(20f, 10.5f); lineTo(11.5f, 10.5f)
                arcTo(4.3f, 4.3f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 11.5f, y1 = 19.1f)
                lineTo(16f, 19.1f)
            } else {
                moveTo(9f, 6f); lineTo(4f, 10.5f); lineTo(9f, 15f)
                moveTo(4f, 10.5f); lineTo(12.5f, 10.5f)
                arcTo(4.3f, 4.3f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 12.5f, y1 = 19.1f)
                lineTo(8f, 19.1f)
            }
        }
    }.build()

val IcUndo: ImageVector by lazy { curveIcon("IcUndo", mirrored = false) }

val IcRedo: ImageVector by lazy { curveIcon("IcRedo", mirrored = true) }
