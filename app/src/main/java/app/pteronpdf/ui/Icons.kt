package app.pteronpdf.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Hand-drawn line icons (24x24 grid) — zero icon-library dependency, so no
 * material-icons-extended (~10 MB of classes) in the APK.
 */
enum class PIcon { Back, Open, Save, Search, Up, Down, Close, Undo, Redo, Edit, Grid, Theme, Pen, Highlighter,
    Line, Curve, Arrow, Rect, Circle, Text, Eraser, Check, More, Select, Settings, Trash, Chevron, Palette, Type, Book, Info, Moon, Sun, Copy }

@Composable
fun PIconView(icon: PIcon, tint: Color, modifier: Modifier = Modifier.size(22.dp), desc: String? = null) {
    Canvas(modifier.then(if (desc != null) Modifier.semantics { contentDescription = desc } else Modifier)) {
        val k = size.minDimension / 24f
        val st = Stroke(width = 1.9f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * k, y * k)
        fun path(block: Path.() -> Unit) = Path().apply(block)
        fun DrawScope.line(a: Offset, b: Offset) = drawLine(tint, a, b, st.width, StrokeCap.Round)
        when (icon) {
            PIcon.Back -> { line(p(15f, 5f), p(8f, 12f)); line(p(8f, 12f), p(15f, 19f)) }
            PIcon.Chevron -> { line(p(9f, 5f), p(16f, 12f)); line(p(16f, 12f), p(9f, 19f)) }
            PIcon.Up -> { line(p(5f, 15f), p(12f, 8f)); line(p(12f, 8f), p(19f, 15f)) }
            PIcon.Down -> { line(p(5f, 9f), p(12f, 16f)); line(p(12f, 16f), p(19f, 9f)) }
            PIcon.Close -> { line(p(6f, 6f), p(18f, 18f)); line(p(18f, 6f), p(6f, 18f)) }
            PIcon.Check -> { line(p(5f, 12.5f), p(10f, 17f)); line(p(10f, 17f), p(19f, 7f)) }
            PIcon.More -> listOf(6f, 12f, 18f).forEach { drawCircle(tint, 1.7f * k, p(12f, it)) }
            PIcon.Open -> drawPath(path { moveTo(3f * k, 7f * k); lineTo(9f * k, 7f * k); lineTo(11f * k, 9.5f * k); lineTo(21f * k, 9.5f * k); lineTo(21f * k, 19f * k); lineTo(3f * k, 19f * k); close() }, tint, style = st)
            PIcon.Save -> {
                drawPath(path { moveTo(5f * k, 4f * k); lineTo(16f * k, 4f * k); lineTo(20f * k, 8f * k); lineTo(20f * k, 20f * k); lineTo(5f * k, 20f * k); close() }, tint, style = st)
                drawRect(tint, p(8f, 4f), Size(7f * k, 5f * k), style = st); drawRect(tint, p(8f, 13f), Size(8f * k, 7f * k), style = st)
            }
            PIcon.Search -> { drawCircle(tint, 6f * k, p(10.5f, 10.5f), style = st); line(p(15f, 15f), p(20f, 20f)) }
            PIcon.Undo, PIcon.Redo -> {
                val d = if (icon == PIcon.Undo) 1f else -1f
                val cx = if (icon == PIcon.Undo) 0f else 24f
                fun x(v: Float) = (cx + d * v) * k
                drawPath(path { moveTo(x(9f), 7f * k); lineTo(x(4f), 12f * k); lineTo(x(9f), 17f * k) }, tint, style = st)
                drawPath(path { moveTo(x(4f), 12f * k); lineTo(x(14f), 12f * k); quadraticTo(x(20f), 12f * k, x(20f), 17.5f * k) }, tint, style = st)
            }
            PIcon.Edit -> {
                drawPath(path { moveTo(4f * k, 20f * k); lineTo(5f * k, 15f * k); lineTo(16f * k, 4f * k); lineTo(20f * k, 8f * k); lineTo(9f * k, 19f * k); close() }, tint, style = st)
                line(p(13.5f, 6.5f), p(17.5f, 10.5f))
            }
            PIcon.Grid -> listOf(4f to 4f, 13.5f to 4f, 4f to 13.5f, 13.5f to 13.5f).forEach { (x, y) -> drawRoundRect(tint, p(x, y), Size(6.5f * k, 6.5f * k), CornerRadius(1.5f * k), style = st) }
            PIcon.Theme -> { drawCircle(tint, 8f * k, p(12f, 12f), style = st); drawPath(path { moveTo(12f * k, 4f * k); arcTo(androidx.compose.ui.geometry.Rect(p(4f, 4f), Size(16f * k, 16f * k)), -90f, 180f, false); close() }, tint) }
            PIcon.Pen -> { drawPath(path { moveTo(4f * k, 20f * k); quadraticTo(8f * k, 6f * k, 12f * k, 12f * k); quadraticTo(15f * k, 17f * k, 20f * k, 5f * k) }, tint, style = st) }
            PIcon.Highlighter -> {
                // a marker pen: slanted body, solid chisel tip at the lower left, and the stroke of ink it leaves
                drawPath(path { moveTo(15f * k, 3.5f * k); lineTo(20.5f * k, 9f * k); lineTo(12f * k, 17.5f * k); lineTo(6.5f * k, 12f * k); close() }, tint, style = st)
                drawPath(path { moveTo(6.5f * k, 12f * k); lineTo(12f * k, 17.5f * k); lineTo(5f * k, 18.5f * k); close() }, tint)
                line(p(12.6f, 6.4f), p(17.6f, 11.4f))
                drawLine(tint.copy(alpha = 0.45f), p(11f, 21f), p(20f, 21f), 3.4f * k, StrokeCap.Round)
            }
            PIcon.Line -> line(p(5f, 19f), p(19f, 5f))
            PIcon.Curve -> drawPath(path { moveTo(4f * k, 18f * k); quadraticTo(6f * k, 4f * k, 20f * k, 6f * k) }, tint, style = st)
            PIcon.Arrow -> { line(p(5f, 19f), p(19f, 5f)); line(p(8f, 5f), p(19f, 5f)); line(p(19f, 5f), p(19f, 16f)) }
            PIcon.Copy -> {
                drawRoundRect(tint, p(9f, 9f), Size(11f * k, 11f * k), CornerRadius(2.2f * k), style = st)
                drawPath(path { moveTo(8.5f * k, 15f * k); lineTo(5.5f * k, 15f * k); lineTo(5.5f * k, 4.5f * k); lineTo(15f * k, 4.5f * k); lineTo(15f * k, 8.5f * k) }, tint, style = st)
            }
            PIcon.Rect -> drawRoundRect(tint, p(5f, 5f), Size(14f * k, 14f * k), CornerRadius(1.5f * k), style = st)
            PIcon.Circle -> drawCircle(tint, 8f * k, p(12f, 12f), style = st)
            PIcon.Text -> { line(p(6f, 6f), p(18f, 6f)); line(p(12f, 6f), p(12f, 19f)); line(p(9f, 19f), p(15f, 19f)) }
            PIcon.Eraser -> { drawPath(path { moveTo(4f * k, 15f * k); lineTo(12f * k, 6f * k); lineTo(19f * k, 12f * k); lineTo(12f * k, 20f * k); lineTo(8f * k, 20f * k); close() }, tint, style = st); line(p(8f, 11f), p(15.5f, 17.5f)) }
            PIcon.Select -> drawPath(path { moveTo(6f * k, 4f * k); lineTo(6f * k, 18f * k); lineTo(10f * k, 14.5f * k); lineTo(13f * k, 20f * k); lineTo(15.5f * k, 18.8f * k); lineTo(12.6f * k, 13.4f * k); lineTo(17.5f * k, 13f * k); close() }, tint, style = st)
            PIcon.Trash -> {
                line(p(4f, 7f), p(20f, 7f)); line(p(9f, 7f), p(9f, 4.5f)); line(p(9f, 4.5f), p(15f, 4.5f)); line(p(15f, 4.5f), p(15f, 7f))
                drawPath(path { moveTo(6f * k, 7f * k); lineTo(7f * k, 20f * k); lineTo(17f * k, 20f * k); lineTo(18f * k, 7f * k) }, tint, style = st)
                line(p(10f, 11f), p(10f, 16f)); line(p(14f, 11f), p(14f, 16f))
            }
            PIcon.Settings -> {
                // 8-tooth gear outline with a round hole
                val gear = path {
                    for (i in 0 until 8) {
                        val base = Math.toRadians(i * 45.0)
                        fun gp(offDeg: Double, r: Float) = Offset(
                            (12f + r * kotlin.math.cos(base + Math.toRadians(offDeg)).toFloat()) * k,
                            (12f + r * kotlin.math.sin(base + Math.toRadians(offDeg)).toFloat()) * k,
                        )
                        val a1 = gp(-13.0, 7.6f); val a2 = gp(-8.0, 10.3f); val a3 = gp(8.0, 10.3f); val a4 = gp(13.0, 7.6f)
                        if (i == 0) moveTo(a1.x, a1.y) else lineTo(a1.x, a1.y)
                        lineTo(a2.x, a2.y); lineTo(a3.x, a3.y); lineTo(a4.x, a4.y)
                    }
                    close()
                }
                drawPath(gear, tint, style = st)
                drawCircle(tint, 3.2f * k, p(12f, 12f), style = st)
            }
            PIcon.Moon -> {
                // crescent = big disc minus a smaller disc shifted to the upper right
                val outer = Path().apply { addOval(androidx.compose.ui.geometry.Rect(p(3.5f, 3.5f), Size(17f * k, 17f * k))) }
                val bite = Path().apply { addOval(androidx.compose.ui.geometry.Rect(p(9.5f, 0.5f), Size(15f * k, 15f * k))) }
                drawPath(Path.combine(PathOperation.Difference, outer, bite), tint, style = st)
            }
            PIcon.Sun -> {
                drawCircle(tint, 4.3f * k, p(12f, 12f), style = st)
                for (i in 0 until 8) {
                    val ang = Math.toRadians(i * 45.0)
                    val cs = kotlin.math.cos(ang).toFloat(); val sn = kotlin.math.sin(ang).toFloat()
                    line(p(12f + 7.4f * cs, 12f + 7.4f * sn), p(12f + 10f * cs, 12f + 10f * sn))
                }
            }
            PIcon.Palette -> {
                drawCircle(tint, 8.5f * k, p(12f, 12f), style = st)
                drawCircle(tint, 1.3f * k, p(8.5f, 10f)); drawCircle(tint, 1.3f * k, p(12f, 7.5f)); drawCircle(tint, 1.3f * k, p(15.5f, 10f))
            }
            PIcon.Type -> { line(p(5f, 18f), p(10f, 5f)); line(p(10f, 5f), p(15f, 18f)); line(p(7f, 13f), p(13f, 13f)); line(p(17f, 11f), p(20f, 11f)); line(p(18.5f, 11f), p(18.5f, 18f)) }
            PIcon.Book -> { drawPath(path { moveTo(12f * k, 6f * k); quadraticTo(8f * k, 4f * k, 4f * k, 5.5f * k); lineTo(4f * k, 18.5f * k); quadraticTo(8f * k, 17f * k, 12f * k, 19f * k); quadraticTo(16f * k, 17f * k, 20f * k, 18.5f * k); lineTo(20f * k, 5.5f * k); quadraticTo(16f * k, 4f * k, 12f * k, 6f * k) }, tint, style = st); line(p(12f, 6f), p(12f, 19f)) }
            PIcon.Info -> { drawCircle(tint, 8.5f * k, p(12f, 12f), style = st); line(p(12f, 11f), p(12f, 16f)); drawCircle(tint, 1.1f * k, p(12f, 8f)) }
        }
    }
}
