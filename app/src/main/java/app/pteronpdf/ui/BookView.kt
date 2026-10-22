package app.pteronpdf.ui

import android.graphics.PointF
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
            // unhurried: a full turn takes about 1.2 s and eases in and out, so the page can be watched as it goes
            val ms = (640f + 560f * abs(target - state.t)).toInt()
            animate(
                state.t, target, initialVelocity = if (viewW > 0) (-velocity / viewW).coerceIn(-2.5f, 2.5f) else 0f,
                animationSpec = tween(ms, easing = FastOutSlowInEasing),
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

private const val COLS = 36        // the turning sheet is a smooth grid: this many cells along the page...
private const val ROWS = 6         // ...and this many down it
private const val LEAD = 0.6f      // how far the free edge runs ahead of the spine while turning
private const val PULL = 0.34f     // extra lead on the side the finger started from, so the page sweeps diagonally
private const val PAPER = 0xFFF7F3EB.toInt()

/**
 * The sheet's curve at turn progress [p] (0 flat, 1 turned over): how far along the page each sample has moved ([xs]),
 * how high it has risen ([zs]) and which way each cell faces ([phis]; 0 = up, pi = face down). The free edge leads and the
 * part by the spine follows, so the page bows like paper being turned by its edge.
 */
private fun sheetProfile(p: Float, len: Float, xs: FloatArray, zs: FloatArray, phis: FloatArray) {
    val ds = len / COLS
    var x = 0f; var z = 0f
    xs[0] = 0f; zs[0] = 0f
    for (i in 0 until COLS) {
        val u = (i + 0.5f) / COLS
        val f = (p * (1f + LEAD) - (1f - u) * LEAD).coerceIn(0f, 1f)
        val phi = (f * f * (3f - 2f * f) * PI.toFloat()).coerceIn(0f, PI.toFloat())     // smooth ease: curves, never creases
        x += cos(phi) * ds; z += sin(phi) * ds
        xs[i + 1] = x; zs[i + 1] = z; phis[i] = phi
    }
}

/**
 * One page of the book. The page lying still is drawn normally. When a turn begins, the page is captured as a picture, and
 * while it turns that picture is stretched over one smooth curved sheet (a grid of points that follow the bend), so the page
 * stays whole and bends continuously. The sheet hinges at the spine, its free edge leads, and the corner nearest the finger
 * sweeps furthest. Each point is lit by how it faces; past upright the back of the paper shows through, and the lifted sheet
 * throws a soft shadow on the page beneath.
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
    val density = LocalDensity.current
    val layer = rememberGraphicsLayer()
    val turning by remember(kind) {
        derivedStateOf { (kind == CURRENT && state.t > 0f) || (kind == PREV && state.t < 0f) }
    }
    // the page's picture, taken once when the turn starts (as an ordinary bitmap: a mesh cannot be drawn from a GPU-only one)
    var tex by remember(index) { mutableStateOf<android.graphics.Bitmap?>(null) }
    val recorded = remember(index) { BooleanArray(1) }
    LaunchedEffect(turning, index) {
        if (turning) {
            while (!recorded[0]) withFrameNanos { }
            tex = runCatching {
                val hw = layer.toImageBitmap().asAndroidBitmap()
                withContext(Dispatchers.Default) { hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false) }
            }.getOrNull()
        } else { tex = null; recorded[0] = false }
    }

    // buffers and objects reused every frame, so a turn allocates nothing
    val verts = remember { FloatArray((COLS + 1) * (ROWS + 1) * 2) }
    val frontColors = remember { IntArray((COLS + 1) * (ROWS + 1)) }
    val paperColors = remember { IntArray((COLS + 1) * (ROWS + 1)) }
    val xs = remember { FloatArray(COLS + 1) }
    val zs = remember { FloatArray(COLS + 1) }
    val phis = remember { FloatArray(COLS) }
    val shadow = remember { Path() }
    val paperBitmap = remember { android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(PAPER) } }
    val meshPaint = remember { android.graphics.Paint().apply { isFilterBitmap = true; isAntiAlias = true } }

    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                if (kind == NEXT) { alpha = if (state.t > 0f) 1f else 0f }
                else if (kind == PREV && state.t >= 0f) { alpha = 0f }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier.padding(top = with(density) { topPx.toDp() })
                .size(with(density) { pw.toDp() }, with(density) { ph.toDp() })
                .drawWithContent {
                    val t = state.t
                    if (!turning) {
                        drawContent()
                        // the page underneath is a little dim while covered and brightens as it is uncovered
                        if (kind == NEXT && t > 0f) {
                            val a = (1f - t) * 0.14f
                            if (a > 0.003f) drawRect(Color.Black.copy(alpha = a), Offset.Zero, size)
                        }
                        return@drawWithContent
                    }
                    val bmp = tex
                    if (bmp == null) {
                        // the first moments of a turn: take the page's picture, and keep showing the page as it is
                        layer.record(this, layoutDirection, androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())) { this@drawWithContent.drawContent() }
                        recorded[0] = true
                        drawContent()
                        return@drawWithContent
                    }
                    val w = size.width; val h = size.height
                    // p: 0 = page lying flat, 1 = turned over onto the left
                    val p = (if (kind == CURRENT) t else 1f + t).coerceIn(0f, 1f)

                    var reachTop = 0f; var reachMid = 0f; var reachBot = 0f
                    for (i in 0..ROWS) {
                        val v = i / ROWS.toFloat()
                        val pull = if (state.anchorTop) 1f - v else v          // the finger's side leads further
                        sheetProfile(p * (1f + PULL * pull), w, xs, zs, phis)
                        var rowMax = 0f
                        for (j in 0..COLS) {
                            val k = i * (COLS + 1) + j
                            if (xs[j] > rowMax) rowMax = xs[j]
                            val ky = 1f + zs[j] / w * 0.10f                        // nearer the viewer = slightly larger
                            verts[2 * k] = xs[j]
                            verts[2 * k + 1] = h / 2f + (v - 0.5f) * h * ky
                            val phi = if (j == 0) phis[0] else if (j == COLS) phis[COLS - 1] else (phis[j - 1] + phis[j]) / 2f
                            val sn = sin(phi)
                            val c = (255f * (1f - (0.04f + 0.18f * sn * sn))).toInt()
                            val rgb = (c shl 16) or (c shl 8) or c
                            // the picture shows on the face, and fades out as the sheet goes past upright (the paper's back shows)
                            val face = ((1.75f - phi) / 0.30f).coerceIn(0f, 1f)
                            val alpha = (255f * (0.14f + 0.86f * face * face * (3f - 2f * face))).toInt()
                            frontColors[k] = (alpha shl 24) or rgb
                            paperColors[k] = (0xFF shl 24) or rgb
                        }
                        if (i == 0) reachTop = rowMax
                        if (i == ROWS / 2) reachMid = rowMax
                        if (i == ROWS) reachBot = rowMax
                    }

                    // the shadow goes down first (it lies on the page beneath), then the sheet over it
                    val sw = 0.26f * w
                    val sa = 0.22f * sin(PI.toFloat() * p).coerceAtLeast(0f)
                    if (sa > 0.01f) {
                        shadow.reset()
                        shadow.moveTo(reachTop, 0f); shadow.lineTo(reachTop + sw, 0f)
                        shadow.lineTo(reachBot + sw, h); shadow.lineTo(reachBot, h)
                        shadow.close()
                        drawPath(
                            shadow,
                            Brush.horizontalGradient(
                                listOf(Color.Black.copy(alpha = sa), Color.Transparent),
                                startX = reachMid, endX = reachMid + sw,
                            ),
                        )
                    }
                    drawIntoCanvas { cv ->
                        val n = cv.nativeCanvas
                        n.drawBitmapMesh(paperBitmap, COLS, ROWS, verts, 0, paperColors, 0, meshPaint)
                        n.drawBitmapMesh(bmp, COLS, ROWS, verts, 0, frontColors, 0, meshPaint)
                    }
                },
        ) {
            PageItem(vm, engine, index, pw, viewW, onText, onEditText, trackRoot = kind == CURRENT)
        }
    }
}
