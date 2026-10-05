package app.pteronpdf.ui

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import app.pteronpdf.pdf.Item
import app.pteronpdf.pdf.PdfEngine
import app.pteronpdf.pdf.ReaderViewModel
import app.pteronpdf.theme.LocalPteron
import kotlinx.coroutines.launch

const val MAX_ZOOM = 4f

@Composable
fun PageList(
    vm: ReaderViewModel, engine: PdfEngine, listState: LazyListState,
    onText: (Int, PointF) -> Unit, onEditText: (Item) -> Unit, onTapEmpty: () -> Unit,
    topPad: Dp, bottomPad: Dp,
) {
    val c = LocalPteron.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val hScroll = rememberScrollState()
    var viewW by remember { mutableIntStateOf(0) }
    var viewH by remember { mutableIntStateOf(0) }
    var live by remember { mutableFloatStateOf(1f) }          // pinch preview scale (GPU only, no re-render)
    var pivot by remember { mutableStateOf(Offset.Zero) }
    val gutterPx = with(density) { 12.dp.toPx() }

    // Page width in px at the committed zoom
    val pageW = if (viewW == 0) 0 else ((viewW - 2 * gutterPx) * vm.zoom).toInt()

    // Lets a mark that is being dragged scroll the list when the finger nears the top or the editing card.
    val topPx = with(density) { topPad.toPx() }
    val bottomPx = with(density) { bottomPad.toPx() }
    SideEffect { vm.edgeScroll = { dy -> scope.launch { listState.scrollBy(dy) } } }

    Box(
        Modifier.fillMaxSize().background(c.canvas)
            .onSizeChanged { viewW = it.width; viewH = it.height }
            .onGloballyPositioned {
                val top = it.positionInRoot().y
                vm.autoScrollTop = top + topPx + 16f * density.density
                vm.autoScrollBottom = top + it.size.height - bottomPx
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var pinched = false
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (ev.changes.count { it.pressed } >= 2) {
                            val z = ev.calculateZoom()
                            if (z != 1f) {
                                pinched = true
                                val minLive = 1f / vm.zoom
                                val maxLive = MAX_ZOOM / vm.zoom
                                live = (live * z).coerceIn(minLive.coerceAtMost(1f), maxLive.coerceAtLeast(1f))
                                pivot = ev.calculateCentroid(useCurrent = true)
                                ev.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (ev.changes.any { it.pressed })
                    if (pinched) {
                        val ratio = live
                        val newZoom = (vm.zoom * ratio).coerceIn(1f, MAX_ZOOM)
                        val r = newZoom / vm.zoom
                        val idx = listState.firstVisibleItemIndex
                        val off = listState.firstVisibleItemScrollOffset
                        val hx = hScroll.value
                        val p = pivot
                        vm.zoom = newZoom; live = 1f
                        scope.launch {
                            // anchor: keep the content under the fingers where it was
                            listState.scrollToItem(idx, 0)
                            listState.scrollBy(((off + p.y) * r - p.y))
                            withFrameNanos { }
                            hScroll.scrollTo(((hx + p.x) * r - p.x).toInt().coerceAtLeast(0))
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { o ->
                        val target = if (vm.zoom > 1.05f) 1f else 2.2f
                        val r = target / vm.zoom
                        val idx = listState.firstVisibleItemIndex; val off = listState.firstVisibleItemScrollOffset; val hx = hScroll.value
                        vm.zoom = target
                        scope.launch {
                            listState.scrollToItem(idx, 0); listState.scrollBy((off + o.y) * r - o.y)
                            withFrameNanos { }; hScroll.scrollTo(((hx + o.x) * r - o.x).toInt().coerceAtLeast(0))
                        }
                    },
                    onTap = { onTapEmpty() },
                )
            },
    ) {
        if (pageW > 0) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        scaleX = live; scaleY = live
                        transformOrigin = TransformOrigin(
                            if (viewW == 0) 0.5f else pivot.x / viewW, if (viewH == 0) 0.5f else pivot.y / viewH)
                    }
                    .horizontalScroll(hScroll)
            ) {
                LazyColumn(
                    Modifier.width(with(density) { (pageW + 2 * gutterPx).toDp() }).fillMaxHeight(),
                    state = listState,
                    contentPadding = PaddingValues(top = topPad, bottom = bottomPad),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                ) {
                    items(engine.pageCount, key = { it }) { i ->
                        PageItem(vm, engine, i, pageW, viewW, onText, onEditText)
                    }
                }
            }
        }
    }
}

@Composable
private fun PageItem(
    vm: ReaderViewModel, engine: PdfEngine, index: Int, pageW: Int, viewW: Int,
    onText: (Int, PointF) -> Unit, onEditText: (Item) -> Unit,
) {
    val density = LocalDensity.current
    val info = engine.pages[index]
    val hPx = (pageW * info.aspect).toInt()
    // Render width: bucketed (cache hits) and capped (bounded memory).
    val cap = (viewW * vm.renderCapFactor).toInt()
    val renderW = (((pageW.coerceAtMost(cap) + 63) / 64) * 64).coerceAtLeast(64)
    val version = vm.pageVersion(index)

    var bmp by remember(index) { mutableStateOf<Bitmap?>(vm.cached(index, renderW)) }
    LaunchedEffect(index, renderW, version) {
        bmp = vm.cached(index, renderW) ?: vm.render(index, renderW) ?: bmp
    }

    DisposableEffect(index) { onDispose { vm.pageRoots.remove(index) } }
    Box(
        Modifier.size(with(density) { pageW.toDp() }, with(density) { hPx.toDp() })
            .onGloballyPositioned { vm.pageRoots[index] = Rect(it.positionInRoot(), it.size.toSize()) }
            .shadow(5.dp, RoundedCornerShape0).background(Color.White)
    ) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        SearchHighlights(vm, index, info.width)
        MarkupOverlay(vm, index, info.width, onText, onEditText)
    }
}

private val RoundedCornerShape0 = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)

@Composable
private fun SearchHighlights(vm: ReaderViewModel, index: Int, pageWidthPdf: Float) {
    val hits = vm.hits
    if (hits.isEmpty()) return
    val mine = remember(hits, index) { hits.withIndex().filter { it.value.page == index } }
    if (mine.isEmpty()) return
    val current = vm.hitIndex
    Canvas(Modifier.fillMaxSize()) {
        val s = size.width / pageWidthPdf
        mine.forEach { (i, h) ->
            val r = h.rect
            val tl = Offset(r.left * s - 2f, r.top * s - 2f)
            val sz = Size((r.right - r.left) * s + 4f, (r.bottom - r.top) * s + 4f)
            val cur = i == current
            drawRoundRect(if (cur) Color(0xC3FFD60A) else Color(0x66FFD60A), tl, sz, CornerRadius(3f))
            if (cur) drawRoundRect(Color(0xEBFF8A00), tl, sz, CornerRadius(3f), style = Stroke(2f))
        }
    }
}
