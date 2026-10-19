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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlin.math.abs

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

    fun settle(target: Float) {
        settleJob[0]?.cancel()
        settleJob[0] = scope.launch {
            val ms = (abs(target - state.t) * 340f).toInt().coerceIn(120, 340)
            animate(state.t, target, animationSpec = tween(ms, easing = FastOutSlowInEasing)) { v, _ -> state.t = v }
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
                        t > 0f && (t > 0.3f || v < -900f) -> 1f
                        t < 0f && (t < -0.3f || v > 900f) -> -1f
                        else -> 0f
                    }
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
    val origin = TransformOrigin(left / viewW, (topPx + ph / 2f) / viewH)
    val density = LocalDensity.current

    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                val t = state.t
                when (kind) {
                    NEXT -> alpha = if (t > 0f) 1f else 0f
                    CURRENT -> if (t > 0f) { transformOrigin = origin; rotationY = -t * 90f; cameraDistance = 22f * density.density }
                    PREV -> if (t < 0f) { transformOrigin = origin; rotationY = -(1f + t) * 90f; cameraDistance = 22f * density.density } else alpha = 0f
                    else -> {}
                }
            }
            .drawWithContent {
                drawContent()
                val t = state.t
                // the page being lifted darkens as it turns away; the one underneath brightens as it is uncovered
                val shade = when (kind) {
                    CURRENT -> if (t > 0f) t * 0.45f else 0f
                    PREV -> if (t < 0f) (1f + t) * 0.45f else 0f
                    else -> if (t > 0f) (1f - t) * 0.28f else 0f
                }
                if (shade > 0.003f) drawRect(Color.Black.copy(alpha = shade), Offset(left, topPx), Size(pw.toFloat(), ph))
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.padding(top = with(density) { topPx.toDp() })) {
            PageItem(vm, engine, index, pw, viewW, onText, onEditText, trackRoot = kind == CURRENT)
        }
    }
}
