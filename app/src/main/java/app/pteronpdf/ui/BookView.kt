package app.pteronpdf.ui

import android.graphics.PointF
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pteronpdf.pdf.Item
import app.pteronpdf.pdf.PdfEngine
import app.pteronpdf.pdf.ReaderViewModel
import app.pteronpdf.theme.LocalPteron
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the book is open and how far a page turn has got. [t] runs from -1 to 1:
 * above 0 the current page is lifting away to the next one, below 0 the previous page is coming back over it.
 */
class BookState(initial: Int) {
    var current by mutableIntStateOf(initial)
    var t by mutableFloatStateOf(0f)
    fun jump(page: Int) { current = page; t = 0f }
}

private const val CURRENT = 0
private const val NEXT = 1
private const val PREV = 2

/**
 * Book mode: one whole page at a time, turned by swiping left or right (or tapping the page edges).
 * The turning page hinges on its left edge like a leaf of a book, lifting toward you and darkening a little, while the
 * page beneath stays still. Everything about the turn is drawn in graphics layers that read [BookState.t] at draw time,
 * so a turn costs no recomposition.
 */
@Composable
fun BookView(
    vm: ReaderViewModel, engine: PdfEngine, state: BookState, editing: Boolean,
    topPad: Dp, panelHeight: Dp,
    onText: (Int, PointF) -> Unit, onEditText: (Item) -> Unit, onTapEmpty: () -> Unit,
) {
    val c = LocalPteron.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var viewW by remember { mutableIntStateOf(0) }
    var viewH by remember { mutableIntStateOf(0) }
    val last = engine.pageCount - 1
    val settleJob = remember { arrayOfNulls<Job>(1) }

    LaunchedEffect(last) { if (state.current > last) state.jump(last.coerceAtLeast(0)) }
    LaunchedEffect(state.current) { vm.currentPage = state.current; vm.select(null) }

    fun settle(target: Float, velocity: Float = 0f) {
        settleJob[0]?.cancel()
        settleJob[0] = scope.launch {
            // a critically damped spring: quick, soft landing, never overshoots (an overshoot would flip the turn direction)
            animate(
                state.t, target, initialVelocity = if (viewW > 0) -velocity / viewW else 0f,
                animationSpec = spring(dampingRatio = 1f, stiffness = 380f, visibilityThreshold = 0.002f),
            ) { v, _ -> state.t = v.coerceIn(-1f, 1f) }
            if (target > 0.5f) state.current = (state.current + 1).coerceAtMost(last)
            else if (target < -0.5f) state.current = (state.current - 1).coerceAtLeast(0)
            state.t = 0f
        }
    }

    val drag = rememberDraggableState { dx ->
        if (viewW > 0) {
            settleJob[0]?.cancel()
            val lo = if (state.current > 0) -1f else 0f
            val hi = if (state.current < last) 1f else 0f
            state.t = (state.t - dx / viewW).coerceIn(lo, hi)
        }
    }

    val gutter = with(density) { 12.dp.toPx() }
    val topPx = with(density) { topPad.toPx() }
    val availH = (viewH - topPx - gutter * 1.5f).coerceAtLeast(1f)
    // while the editing card is up, the whole page shrinks (on the GPU, no re-layout) so its bottom stays reachable above the card
    val panelPx = with(density) { panelHeight.toPx() }
    val editScale = if (viewH == 0) 1f else ((viewH - topPx - panelPx - gutter) / availH).coerceIn(0.4f, 1f)
    val scale by animateFloatAsState(if (editing) editScale else 1f, tween(260, easing = FastOutSlowInEasing), label = "bookFit")

    // pictures of the neighbouring pages are made ahead of time, so a turn never reveals a blank page
    LaunchedEffect(state.current, viewW, viewH) {
        if (viewW == 0) return@LaunchedEffect
        for (n in intArrayOf(state.current + 1, state.current - 1)) {
            if (n < 0 || n > last) continue
            val a = engine.pages[n].aspect
            val pw = pageWidth(viewW, availH, gutter, a)
            vm.render(n, renderWidthFor(vm, pw, viewW))
        }
    }

    val dir by remember { derivedStateOf { if (state.t > 0f) 1 else if (state.t < 0f) -1 else 0 } }

    Box(
        Modifier.fillMaxSize().background(c.canvas)
            .onSizeChanged { viewW = it.width; viewH = it.height }
            .draggable(drag, Orientation.Horizontal, onDragStopped = { v ->
                val t = state.t
                settle(
                    when {
                        t > 0f && (t > 0.22f || v < -600f) -> 1f
                        t < 0f && (t < -0.22f || v > 600f) -> -1f
                        else -> 0f
                    },
                    v,
                )
            })
            .pointerInput(last) {
                detectTapGestures(onTap = { o ->
                    when {
                        o.x < size.width * 0.16f && state.current > 0 -> settle(-1f)
                        o.x > size.width * 0.84f && state.current < last -> settle(1f)
                        else -> onTapEmpty()
                    }
                })
            }
            .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0.5f, if (viewH == 0) 0f else topPx / viewH) },
    ) {
        if (viewW > 0 && viewH > 0) {
            // drawing order is the stacking order: whatever is turning must be drawn last, over the page beneath it
            if (dir > 0 && state.current < last) Leaf(NEXT, state.current + 1, state, vm, engine, viewW, viewH, availH, gutter, topPx, onText, onEditText)
            Leaf(CURRENT, state.current, state, vm, engine, viewW, viewH, availH, gutter, topPx, onText, onEditText)
            if (dir < 0 && state.current > 0) Leaf(PREV, state.current - 1, state, vm, engine, viewW, viewH, availH, gutter, topPx, onText, onEditText)
        }
    }
}

private fun pageWidth(viewW: Int, availH: Float, gutter: Float, aspect: Float): Int {
    var w = viewW - 2 * gutter
    if (w * aspect > availH) w = availH / aspect
    return w.toInt().coerceAtLeast(1)
}

private const val STRIPS = 18          // the turning page is drawn as this many narrow bands, each bent a little differently
private const val STEPS = 54           // finer sampling of the page's curve (three per band)

/**
 * One page of the book. The page lying still is drawn normally. The page being turned is drawn as a bent sheet: its edge
 * leads and the part near the spine follows (so it bows like paper held by its corner), with a slight ripple travelling along it.
 * The sheet's real shape is worked out, then the page picture is drawn through it in narrow bands, each squeezed to the width
 * that band really covers and a touch larger as it comes toward you. Once past upright, the back of the paper shows.
 */
@Composable
private fun Leaf(
    kind: Int, index: Int, state: BookState, vm: ReaderViewModel, engine: PdfEngine,
    viewW: Int, viewH: Int, availH: Float, gutter: Float, topPx: Float,
    onText: (Int, PointF) -> Unit, onEditText: (Item) -> Unit,
) {
    val info = engine.pages[index]
    val pw = pageWidth(viewW, availH, gutter, info.aspect)
    val ph = pw * info.aspect
    val left = (viewW - pw) / 2f
    val density = LocalDensity.current
    val layer = rememberGraphicsLayer()
    // the sheet's outline is rebuilt only when the turn moves; these buffers are reused so a turn allocates nothing per frame
    val xs = remember { FloatArray(STEPS + 1) }
    val zs = remember { FloatArray(STEPS + 1) }
    val ph0 = remember { FloatArray(STEPS) }

    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                if (kind == NEXT) { alpha = if (state.t > 0f) 1f else 0f }
                else if (kind == PREV && state.t >= 0f) { alpha = 0f }
            }
            .drawWithContent {
                val t = state.t
                val turning = (kind == CURRENT && t > 0f) || (kind == PREV && t < 0f)
                if (!turning) {
                    drawContent()
                    // the page underneath is a little dim while covered and brightens as it is uncovered
                    if (kind == NEXT && t > 0f) {
                        val a = (1f - t) * 0.16f
                        if (a > 0.003f) drawRect(Color.Black.copy(alpha = a), Offset(left, topPx), Size(pw.toFloat(), ph))
                    }
                    return@drawWithContent
                }
                // p: 0 = lying flat on the right, 1 = turned over onto the left
                val p = (if (kind == CURRENT) t else 1f + t).coerceIn(0f, 1f)
                layer.record(this, layoutDirection, androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())) { this@drawWithContent.drawContent() }

                val L = pw.toFloat()
                val ds = L / STEPS
                val lead = 0.55f                         // how far the free edge runs ahead of the spine
                val ripple = 0.14f * sin(PI.toFloat() * p)
                var x = 0f; var z = 0f
                xs[0] = 0f; zs[0] = 0f
                for (i in 0 until STEPS) {
                    val u = (i + 0.5f) / STEPS
                    var f = p * (1f + lead) - (1f - u) * lead
                    f = f.coerceIn(0f, 1f)
                    // a smooth ease so the sheet curves instead of folding, plus the travelling ripple
                    val e = f * f * (3f - 2f * f)
                    val phi = (e * PI.toFloat() + ripple * sin(u * 7f - p * 6f)).coerceIn(0f, PI.toFloat())
                    ph0[i] = phi
                    x += cos(phi) * ds; z += sin(phi) * ds
                    xs[i + 1] = x; zs[i + 1] = z
                }

                val cy = topPx + ph / 2f
                val per = STEPS / STRIPS
                for (b in 0 until STRIPS) {
                    val i0 = b * per; val i1 = i0 + per
                    val x0 = left + xs[i0]; val x1 = left + xs[i1]
                    val mid = ph0[i0 + per / 2]
                    val zMid = (zs[i0] + zs[i1]) / 2f / L
                    val ky = 1f + zMid * 0.10f                       // nearer the viewer = slightly larger
                    val lo = minOf(x0, x1); val hi = maxOf(x0, x1)
                    if (hi - lo < 0.2f) continue
                    val srcLeft = left + i0 * ds
                    val sw = (i1 - i0) * ds
                    val kx = (x1 - x0) / sw
                    withTransform({ clipRect(lo, 0f, hi + 0.8f, size.height) }) {
                        if (kx > 0f) {
                            withTransform({
                                translate(left = x0 - srcLeft)
                                scale(kx, ky, pivot = Offset(srcLeft, cy))
                            }) { drawLayer(layer) }
                            // bands facing away from the light are a little darker; the bend shows as soft shading
                            val a = 0.16f * sin(mid) + 0.05f * p
                            drawRect(Color.Black.copy(alpha = a), Offset(lo, cy - ph / 2f * ky), Size(hi - lo + 0.8f, ph * ky))
                        } else {
                            // the back of the paper: plain, warm and shaded
                            val a = 0.10f + 0.10f * sin(mid)
                            drawRect(Color(0xFFF3EEE3), Offset(lo, cy - ph / 2f * ky), Size(hi - lo + 0.8f, ph * ky))
                            drawRect(Color.Black.copy(alpha = a), Offset(lo, cy - ph / 2f * ky), Size(hi - lo + 0.8f, ph * ky))
                        }
                    }
                }

                // a soft shadow thrown on the page beneath, just beyond the lifted edge
                val edge = left + xs[STEPS]
                if (edge < left + L - 4f) {
                    val w = 0.30f * L
                    val a = 0.20f * sin(PI.toFloat() * p).coerceAtLeast(0f)
                    drawRect(
                        Brush.horizontalGradient(listOf(Color.Black.copy(alpha = a), Color.Transparent), startX = edge, endX = edge + w),
                        Offset(edge, topPx), Size(w, ph),
                    )
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.padding(top = with(density) { topPx.toDp() })) {
            PageItem(vm, engine, index, pw, viewW, onText, onEditText, trackRoot = kind == CURRENT)
        }
    }
}
