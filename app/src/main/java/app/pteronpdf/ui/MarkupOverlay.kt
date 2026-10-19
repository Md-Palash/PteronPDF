package app.pteronpdf.ui

import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp as lerpOffset
import androidx.compose.ui.util.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import app.pteronpdf.pdf.Geom
import app.pteronpdf.pdf.Ghost
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
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }

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
                        var moved = false; var finished = false
                        // The mark is carried in ROOT (screen) coordinates by a screen-wide "ghost" layer, so it follows the finger
                        // freely, passes smoothly between pages and keeps up while the list scrolls. Only when you let go is the
                        // page under it decided, and the mark glides into place there.
                        val lc = coords
                        val srcRoot = vm.pageRoots[pageIndex]
                        val b0 = Geom.bounds(hit.m)
                        val downRoot = lc?.localToRoot(down.position) ?: down.position
                        val tl0 = if (srcRoot != null) Offset(srcRoot.left + b0.left * s, srcRoot.top + b0.top * s) else Offset.Zero
                        var ptr = downRoot
                        var lastLocal = down.position
                        fun tlNow() = Offset(tl0.x + (ptr.x - downRoot.x), tl0.y + (ptr.y - downRoot.y))

                        /** Where the mark ends up if released now: the page its centre is over (or the nearest), kept inside that page. */
                        fun landing(): Landing {
                            val e = vm.engine
                            if (e == null || srcRoot == null) {            // layout unknown: stay on this page
                                val a = toPdf(down.position); val b = toPdf(lastLocal)
                                return Landing(hit.copy(m = Geom.move(hit.m, b.x - a.x, b.y - a.y)), null, s)
                            }
                            val tl = tlNow()
                            val centre = Offset(tl.x + b0.width() * s / 2, tl.y + b0.height() * s / 2)
                            val target = (vm.pageNear(centre) ?: pageIndex).coerceIn(0, e.pageCount - 1)
                            val r = vm.pageRoots[target] ?: srcRoot
                            val info = e.pages[target]
                            val st = r.width / info.width
                            val shifted = Geom.move(hit.m, (tl.x - r.left) / st - b0.left, (tl.y - r.top) / st - b0.top)
                            val fin = Geom.clampInto(shifted, info.width, info.height)
                            val fb = Geom.bounds(fin)
                            return Landing(hit.copy(page = target, m = fin), Offset(r.left + fb.left * st, r.top + fb.top * st), st)
                        }
                        fun land() {
                            if (!moved) { vm.ghost = null; return }
                            val l = landing()
                            if (l.item.m != hit.m || l.item.page != hit.page) {
                                vm.commitEdit(hit, l.item)
                                // the item is already stored on its new page (hidden while the ghost glides to it)
                                vm.ghost = l.tl?.let { Ghost(hit.id, hit.m, tlNow(), s, settleTo = it, settleScale = l.scale) }
                            } else vm.ghost = null
                        }
                        fun autoScroll() {
                            val zone = 48f * dens; val speed = 14f * dens
                            if (ptr.y < vm.autoScrollTop) vm.edgeScroll(-speed * ((vm.autoScrollTop - ptr.y) / zone).coerceIn(0.2f, 1f))
                            else if (ptr.y > vm.autoScrollBottom) vm.edgeScroll(speed * ((ptr.y - vm.autoScrollBottom) / zone).coerceIn(0.2f, 1f))
                        }
                        try {
                            val cancelled = trackDrag(true, onTick = { if (moved) autoScroll() }) { pos, _ ->
                                lastLocal = pos
                                ptr = lc?.localToRoot(pos) ?: pos
                                if (!moved && movedFar(pos, 8f)) moved = true
                                if (moved) vm.ghost = Ghost(hit.id, hit.m, tlNow(), s)
                            }
                            finished = true
                            if (cancelled) vm.ghost = null else land()
                            // tapping an already-selected text box edits its text
                            if (!cancelled && !moved && wasSelected && hit.m is Markup.Text) onEditText(hit)
                        } finally {
                            // The gesture can be torn down mid-drag (its page scrolled out of the list): keep the move, never leave a ghost.
                            if (!finished) land()
                        }
                    }
                }

                Tool.Pen, Tool.Highlighter, Tool.Line, Tool.Curve, Tool.Arrow, Tool.Rect, Tool.Circle -> {
                    vm.select(null)
                    val col = vm.color
                    val w = vm.widths[tool] ?: 2f
                    val start = toPdf(down.position)
                    val pts = arrayListOf(start)
                    var end = down.position
                    val cancelled = trackDrag(true) { pos, _ ->
                        end = pos
                        if (tool == Tool.Pen || tool == Tool.Highlighter) pts += toPdf(pos)
                        live = buildMarkup(tool, start, toPdf(pos), pts, col, w)
                    }
                    live = null
                    if (!cancelled) {
                        val far = movedFar(end, 10f)
                        if (tool == Tool.Pen || tool == Tool.Highlighter) {
                            buildMarkup(tool, start, toPdf(end), pts, col, w)?.let { vm.addMarkup(pageIndex, it, selectIt = false) }
                        } else if (far) {
                            buildMarkup(tool, start, toPdf(end), pts, col, w)?.let { vm.addMarkup(pageIndex, it) }
                        }
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

    Canvas(Modifier.fillMaxSize().onGloballyPositioned { coords = it }.then(input)) {
        val s = size.width / pageWidthPdf
        val preview = vm.preview
        val selId = vm.selectedId
        val ghostId = vm.ghostId
        vm.items.forEach { stored ->
            if (stored.page != pageIndex) return@forEach
            if (preview != null && preview.id == stored.id) return@forEach      // being resized: painted below
            if (ghostId != null && ghostId == stored.id) return@forEach         // being carried: painted by the ghost layer
            drawMarkup(stored.m, s)
            if (stored.id == selId) drawSelection(stored.m, s, c.accent, dens, withHandles = true)
        }
        // the mark being resized is painted here in place of its stored version
        if (preview != null && preview.page == pageIndex) {
            drawMarkup(preview.m, s)
            if (preview.id == selId) drawSelection(preview.m, s, c.accent, dens, withHandles = true)
        }
        live?.let { drawMarkup(it, s) }
    }
}

/** Where a carried mark would land: the item (page + position in that page's points) and its on-screen top-left / scale there. */
private class Landing(val item: Item, val tl: Offset?, val scale: Float)

/**
 * Screen-wide layer that paints the mark being carried (see [Ghost]). It draws above the pages, so a mark crossing the gap between two
 * pages is never cut off, and on release it glides into its final spot (position and size) before the real item takes over.
 */
@Composable
fun DragGhost(vm: ReaderViewModel) {
    val c = LocalPteron.current
    val dens = LocalDensity.current.density
    var origin by remember { mutableStateOf(Offset.Zero) }
    // only changes when a release starts, not on every move of the finger
    val settle by remember { derivedStateOf { vm.ghost?.settleTo } }
    LaunchedEffect(settle) {
        val to = settle ?: return@LaunchedEffect
        val start = vm.ghost ?: return@LaunchedEffect
        Animatable(0f).animateTo(1f, tween(170, easing = FastOutSlowInEasing)) {
            vm.ghost = start.copy(tl = lerpOffset(start.tl, to, value), scale = lerp(start.scale, start.settleScale, value))
        }
        vm.ghost = null
    }
    Canvas(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val g = vm.ghost ?: return@Canvas
        val b = Geom.bounds(g.m)
        translate(g.tl.x - origin.x - b.left * g.scale, g.tl.y - origin.y - b.top * g.scale) {
            drawMarkup(g.m, g.scale)
            drawSelection(g.m, g.scale, c.accent, dens, withHandles = g.settleTo == null)
        }
    }
}

private fun buildMarkup(tool: Tool, a: PointF, b: PointF, pts: List<PointF>, color: Int, w: Float): Markup? = when (tool) {
    Tool.Pen, Tool.Highlighter -> if (pts.size >= 2) Markup.Ink(pts.toList(), color, w, if (tool == Tool.Highlighter) 0.35f else 1f) else null
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
private suspend fun AwaitPointerEventScope.trackDrag(
    consume: Boolean, onTick: (() -> Unit)? = null, onMove: (Offset, Long) -> Unit,
): Boolean {
    while (true) {
        // With a tick handler we also wake every ~16 ms while the finger is held still (edge auto-scroll needs that).
        val ev = if (onTick == null) awaitPointerEvent(PointerEventPass.Main)
        else withTimeoutOrNull(16) { awaitPointerEvent(PointerEventPass.Main) }
        if (ev == null) { onTick?.invoke(); continue }
        if (ev.changes.count { it.pressed } > 1) return true
        val ch = ev.changes.firstOrNull() ?: return false
        if (!ch.pressed) return false
        onMove(ch.position, ch.uptimeMillis)
        if (consume) ch.consume()
    }
}

// ───────────────────────── painting ─────────────────────────

internal fun DrawScope.drawMarkup(m: Markup, s: Float) {
    when (m) {
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

internal fun DrawScope.drawSelection(m: Markup, s: Float, accent: Color, dens: Float, withHandles: Boolean) {
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
