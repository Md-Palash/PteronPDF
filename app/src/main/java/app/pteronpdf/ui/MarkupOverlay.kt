package app.pteronpdf.ui

import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import app.pteronpdf.pdf.Geom
import app.pteronpdf.pdf.Item
import app.pteronpdf.pdf.Markup
import app.pteronpdf.pdf.ReaderViewModel
import app.pteronpdf.pdf.ShapeKind
import app.pteronpdf.pdf.Tool
import app.pteronpdf.theme.LocalPteron
import kotlin.math.hypot

/**
 * Per-page markup layer. It always paints this page's markup (vectors, crisp at any zoom). When a tool is active it also
 * takes one-finger input; a second finger cancels the gesture and hands it back to the list, so two-finger scroll/zoom
 * always works. Handles of the selected markup are live in every tool.
 */
@Composable
fun MarkupOverlay(
    vm: ReaderViewModel, pageIndex: Int, pageWidthPdf: Float,
    onText: (page: Int, at: PointF) -> Unit,
    onEditText: (Item) -> Unit,
) {
    val c = LocalPteron.current
    val dens = LocalDensity.current.density
    val tool = vm.tool
    var live by remember { mutableStateOf<Markup?>(null) }

    val input = if (tool == Tool.None) Modifier else Modifier.pointerInput(tool, pageIndex) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true)
            val s = size.width / pageWidthPdf                       // px per PDF point, read live (zoom changes it)
            fun toPdf(o: Offset) = PointF(o.x / s, o.y / s)
            val tolHandle = 24f * dens / s
            val tolHit = 12f * dens / s
            fun movedFar(o: Offset, px: Float) = hypot(o.x - down.position.x, o.y - down.position.y) > px * dens

            // 1) handles of the selected markup work in every tool (except the eraser)
            val sel = vm.selected?.takeIf { it.page == pageIndex }
            val hIdx = if (sel != null && tool != Tool.Erase) handleAt(sel.m, toPdf(down.position), tolHandle) else -1
            if (sel != null && hIdx >= 0) {
                down.consume()
                var cur = sel
                val cancelled = trackDrag(true) { pos, _ ->
                    cur = sel.copy(m = Geom.dragHandle(sel.m, hIdx, toPdf(pos))); vm.liveEdit(cur)
                }
                if (!cancelled && cur.m != sel.m) vm.commitEdit(sel, cur) else vm.liveEdit(null)
                return@awaitEachGesture
            }

            when (tool) {
                Tool.Select -> {
                    val hit = vm.pick(pageIndex, toPdf(down.position), tolHit)
                    if (hit == null) {
                        // Empty space: don't consume, so one finger still scrolls. A quick tap clears the selection.
                        var moved = false
                        trackDrag(false) { pos, _ -> if (movedFar(pos, 8f)) moved = true }
                        if (!moved) vm.select(null)
                    } else {
                        val wasSelected = vm.selectedId == hit.id
                        vm.select(hit.id); down.consume()
                        var cur = hit; var moved = false
                        val cancelled = trackDrag(true) { pos, _ ->
                            if (movedFar(pos, 8f)) moved = true
                            if (moved && Geom.movable(hit.m)) {
                                val a = toPdf(down.position); val b = toPdf(pos)
                                cur = hit.copy(m = Geom.move(hit.m, b.x - a.x, b.y - a.y)); vm.liveEdit(cur)
                            }
                        }
                        if (!cancelled && moved && cur.m != hit.m) vm.commitEdit(hit, cur)
                        else {
                            vm.liveEdit(null)
                            // tapping an already-selected text box edits its text
                            if (!cancelled && !moved && wasSelected && hit.m is Markup.Text) onEditText(hit)
                        }
                    }
                }

                Tool.Pen, Tool.Line, Tool.Curve, Tool.Arrow, Tool.Rect, Tool.Circle -> {
                    vm.select(null)
                    val col = vm.color
                    val w = vm.widths[tool] ?: 2f
                    val start = toPdf(down.position)
                    val pts = arrayListOf(start)
                    var end = down.position
                    val cancelled = trackDrag(true) { pos, _ ->
                        end = pos
                        if (tool == Tool.Pen) pts += toPdf(pos)
                        live = buildMarkup(tool, start, toPdf(pos), pts, col, w)
                    }
                    live = null
                    if (!cancelled) {
                        val far = movedFar(end, 10f)
                        if (tool == Tool.Pen) {
                            if (pts.size >= 2) vm.addMarkup(pageIndex, Markup.Ink(pts.toList(), col, w, 1f), selectIt = false)
                        } else if (far) {
                            buildMarkup(tool, start, toPdf(end), pts, col, w)?.let { vm.addMarkup(pageIndex, it) }
                        }
                    }
                }

                Tool.Highlight -> {
                    down.consume()
                    val start = toPdf(down.position)
                    var last = 0L
                    var endPos = down.position
                    val cancelled = trackDrag(true) { pos, t ->
                        endPos = pos
                        if (t - last > 60) { last = t; vm.previewTextSelection(pageIndex, start, toPdf(pos)) }
                    }
                    if (cancelled) vm.clearTextSelection() else {
                        // final position (the throttle may have skipped it), then commit once that selection is ready
                        vm.previewTextSelection(pageIndex, start, toPdf(endPos))
                        vm.commitTextSelection()
                    }
                }

                Tool.Text -> {
                    var moved = false
                    trackDrag(false) { pos, _ -> if (movedFar(pos, 8f)) moved = true }
                    if (!moved) {
                        val at = toPdf(down.position)
                        val existing = vm.pick(pageIndex, at, tolHit)
                        if (existing != null && existing.m is Markup.Text) { vm.select(existing.id); onEditText(existing) }
                        else onText(pageIndex, at)
                    }
                }

                Tool.Erase -> {
                    down.consume()
                    var lastErase = down.position
                    vm.erase(pageIndex, toPdf(down.position), tolHit)
                    trackDrag(true) { pos, _ ->
                        if (hypot(pos.x - lastErase.x, pos.y - lastErase.y) > 10f * dens) {
                            lastErase = pos; vm.erase(pageIndex, toPdf(pos), tolHit)
                        }
                    }
                }

                Tool.None -> {}
            }
        }
    }

    Canvas(Modifier.fillMaxSize().then(input)) {
        val s = size.width / pageWidthPdf
        val preview = vm.preview
        val selId = vm.selectedId
        vm.items.forEach { it0 ->
            if (it0.page != pageIndex) return@forEach
            val it = if (preview != null && preview.id == it0.id) preview else it0
            drawMarkup(it.m, s)
            if (it.id == selId) drawSelection(it.m, s, c.accent, dens, withHandles = true)
        }
        live?.let { drawMarkup(it, s) }
        if (vm.selPage == pageIndex) vm.selRects.forEach { r ->
            drawRect(Color(vm.color).copy(alpha = 0.35f), Offset(r.left * s, r.top * s), Size(r.width() * s, r.height() * s))
        }
    }
}

private fun buildMarkup(tool: Tool, a: PointF, b: PointF, pts: List<PointF>, color: Int, w: Float): Markup? = when (tool) {
    Tool.Pen -> if (pts.size >= 2) Markup.Ink(pts.toList(), color, w, 1f) else null
    Tool.Line -> Markup.Shape(ShapeKind.Line, a, b, color, w)
    Tool.Arrow -> Markup.Shape(ShapeKind.Arrow, a, b, color, w)
    Tool.Rect -> Markup.Shape(ShapeKind.Rect, a, b, color, w)
    Tool.Circle -> Markup.Shape(ShapeKind.Circle, a, b, color, w)
    Tool.Curve -> {
        // Start with a gentle bow; drag the middle handle afterwards to reshape.
        val mx = (a.x + b.x) / 2; val my = (a.y + b.y) / 2
        val dx = b.x - a.x; val dy = b.y - a.y
        val k = 0.4f
        Markup.Curve(a, PointF(mx - dy * k, my + dx * k), b, color, w)
    }
    else -> null
}

private fun handleAt(m: Markup, p: PointF, tol: Float): Int {
    var best = -1; var bd = tol
    Geom.handles(m).forEachIndexed { i, h ->
        val d = hypot(p.x - h.x, p.y - h.y)
        if (d <= bd) { bd = d; best = i }
    }
    return best
}

/** Tracks one finger. Returns true if a second finger arrived (the gesture was cancelled). */
private suspend fun AwaitPointerEventScope.trackDrag(consume: Boolean, onMove: (Offset, Long) -> Unit): Boolean {
    while (true) {
        val ev = awaitPointerEvent(PointerEventPass.Main)
        if (ev.changes.count { it.pressed } > 1) return true
        val ch = ev.changes.firstOrNull() ?: return false
        if (!ch.pressed) return false
        onMove(ch.position, ch.uptimeMillis)
        if (consume) ch.consume()
    }
}

// ───────────────────────── painting ─────────────────────────

private fun DrawScope.drawMarkup(m: Markup, s: Float) {
    when (m) {
        is Markup.Highlight -> m.rects.forEach { r ->
            drawRect(Color(m.color).copy(alpha = 0.38f), Offset(r.left * s, r.top * s), Size(r.width() * s, r.height() * s))
        }
        is Markup.Text -> drawText(m, s)
        is Markup.Ink -> {
            val pts = m.pts
            if (pts.size < 2) return
            val path = Path().apply {
                moveTo(pts[0].x * s, pts[0].y * s)
                for (i in 1 until pts.size) {
                    val a = pts[i - 1]; val b = pts[i]
                    quadraticTo(a.x * s, a.y * s, (a.x + b.x) / 2 * s, (a.y + b.y) / 2 * s)
                }
            }
            drawPath(path, Color(m.color).copy(alpha = m.opacity),
                style = Stroke((m.width * s).coerceAtLeast(1f), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        else -> {
            val width = ((Geom.widthOf(m) ?: 2f) * s).coerceAtLeast(1f)
            Geom.strokes(m).forEach { line ->
                if (line.size < 2) return@forEach
                val path = Path().apply {
                    moveTo(line[0].x * s, line[0].y * s)
                    for (i in 1 until line.size) lineTo(line[i].x * s, line[i].y * s)
                }
                drawPath(path, Color(m.color), style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

private fun wrapLines(text: String, paint: Paint, maxW: Float): List<String> {
    val out = ArrayList<String>()
    for (para in text.split('\n')) {
        var line = ""
        for (word in para.split(' ')) {
            val trial = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && paint.measureText(trial) > maxW) { out += line; line = word } else line = trial
        }
        out += line
    }
    return out
}

private fun DrawScope.drawText(m: Markup.Text, s: Float) {
    val r = m.rect
    drawIntoCanvas { cv ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = m.fontSize * s; color = m.color
            typeface = if (m.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val pad = 2f * s
        val lines = wrapLines(m.text, paint, r.width() * s - 2 * pad)
        val lh = m.fontSize * s * 1.2f
        cv.nativeCanvas.save()
        cv.nativeCanvas.clipRect(r.left * s, r.top * s, r.right * s, r.bottom * s)
        var y = r.top * s + pad - paint.ascent()
        lines.forEach { cv.nativeCanvas.drawText(it, r.left * s + pad, y, paint); y += lh }
        cv.nativeCanvas.restore()
    }
}

private fun DrawScope.drawSelection(m: Markup, s: Float, accent: Color, dens: Float, withHandles: Boolean) {
    val b: RectF = Geom.bounds(m)
    val pad = 4f * dens
    val dash = PathEffect.dashPathEffect(floatArrayOf(10f * dens, 7f * dens))
    when (m) {
        is Markup.Curve -> {
            // guide from the end points through the control point so the shape of the bow is visible
            val st = Stroke(1.2f * dens, pathEffect = dash)
            drawLine(accent.copy(alpha = 0.5f), Offset(m.p0.x * s, m.p0.y * s), Offset(m.c.x * s, m.c.y * s), st.width, pathEffect = dash)
            drawLine(accent.copy(alpha = 0.5f), Offset(m.c.x * s, m.c.y * s), Offset(m.p1.x * s, m.p1.y * s), st.width, pathEffect = dash)
        }
        is Markup.Shape -> if (m.kind == ShapeKind.Line || m.kind == ShapeKind.Arrow) Unit else
            drawRoundRect(accent, Offset(b.left * s - pad, b.top * s - pad), Size(b.width() * s + 2 * pad, b.height() * s + 2 * pad),
                CornerRadius(4f * dens), style = Stroke(1.5f * dens, pathEffect = dash))
        else -> drawRoundRect(accent, Offset(b.left * s - pad, b.top * s - pad), Size(b.width() * s + 2 * pad, b.height() * s + 2 * pad),
            CornerRadius(4f * dens), style = Stroke(1.5f * dens, pathEffect = dash))
    }
    if (withHandles) Geom.handles(m).forEach { h ->
        val o = Offset(h.x * s, h.y * s)
        drawCircle(Color.White, 8f * dens, o)
        drawCircle(accent, 6f * dens, o)
    }
}
