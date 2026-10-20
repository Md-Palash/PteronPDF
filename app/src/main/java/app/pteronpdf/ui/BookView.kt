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
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
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
    /** The turn is lifted from the top corner when the finger began in the upper half, otherwise from the bottom corner. */
    var anchorTop by mutableStateOf(false)
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
            .draggable(drag, Orientation.Horizontal, onDragStarted = { o -> state.anchorTop = o.y < viewH / 2f }, onDragStopped = { v ->
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

private const val BANDS = 20       // the turning page is drawn as this many narrow bands (each follows the sheet's bend)
private const val STEPS = 60       // finer sampling of the sheet's curve (three per band)
private const val LEAD = 0.6f      // how far the free edge runs ahead of the spine while turning
private const val PULL = 0.34f     // extra lead on the side the finger started from, so the page sweeps diagonally

/**
 * The sheet's curve at turn progress [p] (0 flat, 1 turned over): how far along the page each sample has moved ([xs]),
 * how high it has risen ([zs]) and which way it faces ([phis], 0 = up, pi = face down). The free edge leads and the
 * part by the spine follows, so the page bows like paper being turned by its edge.
 */
private fun sheetProfile(p: Float, len: Float, xs: FloatArray, zs: FloatArray?, phis: FloatArray?) {
    val ds = len / STEPS
    var x = 0f; var z = 0f
    xs[0] = 0f; zs?.set(0, 0f)
    for (i in 0 until STEPS) {
        val u = (i + 0.5f) / STEPS
        val f = (p * (1f + LEAD) - (1f - u) * LEAD).coerceIn(0f, 1f)
        val phi = (f * f * (3f - 2f * f) * PI.toFloat()).coerceIn(0f, PI.toFloat())     // smooth ease: curves, never creases
        x += cos(phi) * ds; z += sin(phi) * ds
        xs[i + 1] = x; zs?.set(i + 1, z); phis?.set(i, phi)
    }
}

/**
 * One page of the book. The page lying still is drawn normally. The page being turned is a sheet hinged at the spine that
 * curves smoothly as it lifts, with its free edge leading and the corner nearest your finger sweeping furthest.
 * It is drawn from one recording of the page, in narrow bands that follow the curve; bands facing away show the back of the
 * paper. Bands are lit by how they face, and the lifted sheet throws a soft shadow on the page beneath.
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
    // buffers and objects reused every frame, so a turn allocates nothing
    val xTop = remember { FloatArray(STEPS + 1) }
    val xBot = remember { FloatArray(STEPS + 1) }
    val zMid = remember { FloatArray(STEPS + 1) }
    val xMid = remember { FloatArray(STEPS + 1) }
    val phis = remember { FloatArray(STEPS) }
    val band = remember { Path() }
    val shadow = remember { Path() }
    val m = remember { Matrix() }

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
                        val a = (1f - t) * 0.14f
                        if (a > 0.003f) drawRect(Color.Black.copy(alpha = a), Offset(left, topPx), Size(pw.toFloat(), ph))
                    }
                    return@drawWithContent
                }
                // p: 0 = page lying flat, 1 = turned over onto the left
                val p = (if (kind == CURRENT) t else 1f + t).coerceIn(0f, 1f)
                layer.record(this, layoutDirection, androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())) { this@drawWithContent.drawContent() }

                val len = pw.toFloat()
                val ds = len / STEPS
                val topLead = if (state.anchorTop) PULL else 0f
                sheetProfile(p * (1f + topLead), len, xTop, null, null)
                sheetProfile(p * (1f + PULL - topLead), len, xBot, null, null)
                sheetProfile(p * (1f + PULL / 2f), len, xMid, zMid, phis)

                val cy = topPx + ph / 2f
                var reach = 0f                                        // furthest the sheet extends to the right (for the shadow)
                var reachTop = 0f; var reachBot = 0f
                for (i in 0..STEPS) {
                    if (xTop[i] > reachTop) reachTop = xTop[i]
                    if (xBot[i] > reachBot) reachBot = xBot[i]
                    if (xMid[i] > reach) reach = xMid[i]
                }

                // the shadow goes down first (it lies on the page beneath), then the sheet over it
                val sw = 0.26f * len
                val sa = 0.22f * sin(PI.toFloat() * p).coerceAtLeast(0f)
                if (sa > 0.01f) {
                    shadow.reset()
                    shadow.moveTo(left + reachTop, topPx); shadow.lineTo(left + reachTop + sw, topPx)
                    shadow.lineTo(left + reachBot + sw, topPx + ph); shadow.lineTo(left + reachBot, topPx + ph)
                    shadow.close()
                    drawPath(
                        shadow,
                        Brush.horizontalGradient(
                            listOf(Color.Black.copy(alpha = sa), Color.Transparent),
                            startX = left + reach, endX = left + reach + sw,
                        ),
                    )
                }

                val per = STEPS / BANDS
                for (b in 0 until BANDS) {
                    val i0 = b * per; val i1 = i0 + per
                    val z = (zMid[i0] + zMid[i1]) / 2f / len
                    val ky = 1f + z * 0.10f                               // nearer the viewer = slightly larger
                    val yT = cy - ph / 2f * ky; val yB = cy + ph / 2f * ky
                    val x0t = left + xTop[i0]; val x1t = left + xTop[i1]
                    val x0b = left + xBot[i0]; val x1b = left + xBot[i1]
                    val srcLeft = left + i0 * ds
                    val sw0 = (i1 - i0) * ds
                    val kx = ((xTop[i1] + xBot[i1]) - (xTop[i0] + xBot[i0])) / 2f / sw0
                    if (abs(kx) < 0.003f) continue
                    val mid = phis[i0 + per / 2]

                    band.reset()
                    band.moveTo(x0t, yT); band.lineTo(x1t + 0.8f, yT); band.lineTo(x1b + 0.8f, yB); band.lineTo(x0b, yB)
                    band.close()

                    if (kx > 0f) {
                        // x depends on y a little (the lower side leads further): that is the shear
                        val sh = ((xBot[i0] - xTop[i0]) + (xBot[i1] - xTop[i1])) / 2f / ph
                        m.reset()
                        m.values[Matrix.ScaleX] = kx
                        m.values[Matrix.SkewX] = sh
                        m.values[Matrix.ScaleY] = ky
                        m.values[Matrix.TranslateX] = left + (xTop[i0] + xBot[i0]) / 2f - kx * srcLeft - sh * cy
                        m.values[Matrix.TranslateY] = cy * (1f - ky)
                        withTransform({ clipPath(band); transform(m) }) { drawLayer(layer) }
                        val s = sin(mid)
                        drawPath(band, Color.Black.copy(alpha = 0.04f + 0.18f * s * s))
                    } else {
                        // the back of the paper
                        drawPath(band, Color(0xFFF7F3EB))
                        val s = sin(mid)
                        drawPath(band, Color.Black.copy(alpha = 0.06f + 0.14f * s * s))
                    }
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.padding(top = with(density) { topPx.toDp() })) {
            PageItem(vm, engine, index, pw, viewW, onText, onEditText, trackRoot = kind == CURRENT)
        }
    }
}
