package app.pteronpdf.ui

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import app.pteronpdf.pdf.*
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Per-page drawing layer. One finger draws; a second finger cancels the stroke and hands the
 * gesture back to the list so two-finger scroll/zoom always works while a tool is active.
 */
@Composable
fun MarkupOverlay(
    vm: ReaderViewModel, pageIndex: Int, pageWidthPdf: Float,
    onText: (page: Int, at: PointF) -> Unit,
) {
    val tool = vm.tool
    if (tool == Tool.None) return
    val density = LocalDensity.current.density
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var start by remember { mutableStateOf<Offset?>(null) }
    val color = vm.color
    @Suppress("UNUSED_VARIABLE") val tick = vm.widthTick

    Canvas(
        Modifier.fillMaxSize().pointerInput(tool, color, vm.widthTick) {
            val s = size.width / pageWidthPdf          // px per PDF point
            fun toPdf(o: Offset) = PointF(o.x / s, o.y / s)
            val strokePx = (vm.widths[tool] ?: 3f) * density
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                var cancelled = false
                val pts = ArrayList<Offset>().apply { add(down.position) }
                start = down.position; live = pts.toList()
                var moved = false
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Main)
                    if (ev.changes.count { it.pressed } > 1) { cancelled = true; break }
                    val ch = ev.changes.firstOrNull() ?: break
                    if (!ch.pressed) break
                    val p = ch.position
                    if (hypot(p.x - down.position.x, p.y - down.position.y) > 6f) moved = true
                    when (tool) {
                        Tool.PenWrite, Tool.PenMark, Tool.Highlighter -> { pts += p; live = pts.toList() }
                        else -> live = listOf(down.position, p)
                    }
                    ch.consume()
                }
                val end = pts.lastOrNull() ?: down.position
                val finalLive = live
                live = emptyList(); start = null
                if (cancelled) return@awaitEachGesture
                when (tool) {
                    Tool.PenWrite, Tool.PenMark, Tool.Highlighter -> if (pts.size >= 2) {
                        vm.addMarkup(pageIndex, Markup.Ink(
                            pts.map(::toPdf), color, strokePx / s,
                            if (tool == Tool.Highlighter) 0.35f else 1f,
                        ))
                    }
                    Tool.Line, Tool.Arrow, Tool.Rect, Tool.Circle -> {
                        val e = finalLive.lastOrNull() ?: end
                        if (moved && hypot(e.x - down.position.x, e.y - down.position.y) > 8f) {
                            val kind = when (tool) {
                                Tool.Line -> ShapeKind.Line; Tool.Arrow -> ShapeKind.Arrow
                                Tool.Rect -> ShapeKind.Rect; else -> ShapeKind.Circle
                            }
                            val w = (if (tool == Tool.Arrow) strokePx * 0.7f else strokePx) / s
                            vm.addMarkup(pageIndex, Markup.Shape(kind, toPdf(down.position), toPdf(e), color, w.coerceAtLeast(0.4f)))
                        }
                    }
                    Tool.Text -> if (!moved) onText(pageIndex, toPdf(down.position))
                    Tool.Erase -> if (!moved) vm.erase(pageIndex, toPdf(down.position), 6f * density / s)
                    Tool.None -> {}
                }
            }
        }
    ) {
        val strokePx = (vm.widths[tool] ?: 3f) * density
        val c = Color(color)
        when (tool) {
            Tool.PenWrite, Tool.PenMark, Tool.Highlighter -> if (live.size > 1) {
                val path = Path().apply {
                    moveTo(live[0].x, live[0].y)
                    for (i in 1 until live.size) {
                        val a = live[i - 1]; val b = live[i]
                        quadraticTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2)
                    }
                }
                drawPath(path, c.copy(alpha = if (tool == Tool.Highlighter) 0.35f else 1f),
                    style = Stroke(strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            Tool.Line, Tool.Arrow, Tool.Rect, Tool.Circle -> if (live.size == 2) {
                val a = live[0]; val b = live[1]
                val st = Stroke(if (tool == Tool.Arrow) strokePx * 0.7f else strokePx, cap = StrokeCap.Round)
                when (tool) {
                    Tool.Line -> drawLine(c, a, b, st.width, StrokeCap.Round)
                    Tool.Arrow -> {
                        drawLine(c, a, b, st.width, StrokeCap.Round)
                        val ang = kotlin.math.atan2(b.y - a.y, b.x - a.x); val len = 14f * density
                        for (d in listOf(0.5f, -0.5f)) {
                            drawLine(c, b, Offset(b.x - len * kotlin.math.cos(ang + d), b.y - len * kotlin.math.sin(ang + d)), st.width, StrokeCap.Round)
                        }
                    }
                    Tool.Rect -> drawRect(c, Offset(minOf(a.x, b.x), minOf(a.y, b.y)),
                        androidx.compose.ui.geometry.Size(abs(a.x - b.x), abs(a.y - b.y)), style = st)
                    else -> drawOval(c, Offset(minOf(a.x, b.x), minOf(a.y, b.y)),
                        androidx.compose.ui.geometry.Size(abs(a.x - b.x), abs(a.y - b.y)), style = st)
                }
            }
            else -> {}
        }
    }
}
