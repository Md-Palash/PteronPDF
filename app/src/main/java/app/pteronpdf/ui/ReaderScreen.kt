package app.pteronpdf.ui

import android.graphics.PointF
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pteronpdf.pdf.*
import app.pteronpdf.theme.DarkMode
import app.pteronpdf.theme.LocalPteron
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    vm: ReaderViewModel, themeIndex: Int, darkMode: DarkMode,
    onTheme: (Int) -> Unit, onDark: (DarkMode) -> Unit, onClose: () -> Unit,
) {
    val c = LocalPteron.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val engine = vm.engine

    var chromeVisible by remember { mutableStateOf(true) }
    var searchOpen by remember { mutableStateOf(false) }
    var showThumbs by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showColor by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showUnsaved by remember { mutableStateOf(false) }
    var textRequest by remember { mutableStateOf<Pair<Int, PointF>?>(null) }
    val editing = vm.tool != Tool.None

    // restore last-read page once
    LaunchedEffect(engine) { if (engine != null && vm.initialPage > 0) listState.scrollToItem(vm.initialPage) }

    // current page = the one covering the viewport's upper third
    LaunchedEffect(listState, engine) {
        snapshotFlow {
            val info = listState.layoutInfo
            val line = info.viewportStartOffset + (info.viewportEndOffset - info.viewportStartOffset) / 3
            info.visibleItemsInfo.firstOrNull { it.offset + it.size >= line }?.index ?: 0
        }.collect { vm.currentPage = it }
    }

    // jump to the active search hit
    val density = LocalDensity.current
    LaunchedEffect(vm.hitIndex, vm.hits) {
        val h = vm.hits.getOrNull(vm.hitIndex) ?: return@LaunchedEffect
        val e = vm.engine ?: return@LaunchedEffect
        val info = e.pages[h.page]
        val li = listState.layoutInfo
        val pageWpx = (li.viewportSize.width - 2 * with(density) { 12.dp.toPx() }) * vm.zoom
        val s = pageWpx / info.width
        val viewH = li.viewportEndOffset - li.viewportStartOffset
        listState.animateScrollToItem(h.page, (h.rect.top * s - viewH / 2f).roundToInt())
    }

    vm.message?.let { msg -> LaunchedEffect(msg) { snackbar.showSnackbar(msg); vm.message = null } }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { u ->
        if (u != null) vm.saveAs(u)
    }
    if (vm.needsSaveAs) {
        AlertDialog(
            onDismissRequest = { vm.needsSaveAs = false },
            title = { Text("Can't overwrite this file") },
            text = { Text("This location is read-only. Save a copy instead?") },
            confirmButton = { TextButton({ vm.needsSaveAs = false; saveAs.launch(vm.name.removeSuffix(".pdf") + " (marked).pdf") }) { Text("Save a copy") } },
            dismissButton = { TextButton({ vm.needsSaveAs = false }) { Text("Cancel") } },
        )
    }

    fun requestClose() { if (vm.dirty) showUnsaved = true else onClose() }
    BackHandler {
        when {
            showThumbs -> showThumbs = false
            searchOpen -> { searchOpen = false; vm.clearSearch() }
            editing -> vm.tool = Tool.None
            else -> requestClose()
        }
    }

    Box(Modifier.fillMaxSize().background(c.canvas)) {
        when {
            vm.error != null -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(vm.error!!, color = c.onBar); Spacer(Modifier.height(16.dp))
                Button(onClick = onClose, colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)) { Text("Back") }
            }
            engine == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = c.accent)
            else -> {
                PageList(vm, engine, listState, onText = { p, pt -> textRequest = p to pt }, onTapEmpty = { chromeVisible = !chromeVisible })
                Scrubber(listState, engine.pageCount, Modifier.align(Alignment.CenterEnd))
            }
        }

        // ── top chrome ──
        AnimatedVisibility(
            visible = chromeVisible || editing || searchOpen,
            enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Box(Modifier.statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
                if (searchOpen) SearchBar(vm, onClose = { searchOpen = false; vm.clearSearch() })
                else PillCard(Modifier.fillMaxWidth()) {
                    BarIconButton(PIcon.Back, "Back", ::requestClose)
                    Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                        Text(vm.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.onBar, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (engine != null) Text("${vm.currentPage + 1} / ${engine.pageCount}" + if (vm.dirty) "  •  unsaved" else "",
                            fontSize = 11.sp, color = c.onBar.copy(alpha = 0.6f))
                    }
                    BarIconButton(PIcon.Search, "Search", { searchOpen = true })
                    BarIconButton(PIcon.Edit, "Markup tools", { vm.tool = if (editing) Tool.None else Tool.PenWrite }, selected = editing)
                    Box {
                        BarIconButton(PIcon.More, "More", { showMenu = true })
                        DropdownMenu(showMenu, { showMenu = false }) {
                            DropdownMenuItem(text = { Text("Pages") }, onClick = { showMenu = false; showThumbs = true })
                            DropdownMenuItem(text = { Text("Save") }, enabled = vm.dirty, onClick = { showMenu = false; vm.save() })
                            DropdownMenuItem(text = { Text("Save a copy…") }, onClick = { showMenu = false; saveAs.launch(vm.name.removeSuffix(".pdf") + " (copy).pdf") })
                            DropdownMenuItem(text = { Text("Theme") }, onClick = { showMenu = false; showTheme = true })
                        }
                    }
                }
            }
        }

        // ── markup bar ──
        AnimatedVisibility(
            visible = editing,
            enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            MarkupBar(vm, onColor = { showColor = true })
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 90.dp))
    }

    // ── sheets & dialogs ──
    if (showThumbs && engine != null) ModalBottomSheet(onDismissRequest = { showThumbs = false }, containerColor = c.bar) {
        ThumbnailGrid(vm, engine, onPick = { i -> showThumbs = false; scope.launch { listState.scrollToItem(i) } })
    }
    if (showTheme) ModalBottomSheet(onDismissRequest = { showTheme = false }, containerColor = c.bar) {
        ThemeSheetContent(themeIndex, darkMode, onTheme, onDark)
    }
    if (showColor) ModalBottomSheet(onDismissRequest = { showColor = false }, containerColor = c.bar) {
        ColorSheetContent(vm)
    }
    textRequest?.let { (page, at) ->
        TextDialog(
            vm.textSizeDp, onSize = { vm.textSizeDp = it }, onDismiss = { textRequest = null },
            onConfirm = { text, bold ->
                val e = vm.engine
                if (e != null && text.isNotBlank()) {
                    val info = e.pages[page]
                    // size in dp -> PDF points using the page's current on-screen scale (approx. 1dp ≈ pageWidth/screenWidthDp)
                    val fs = vm.textSizeDp * (info.width / 392f)
                    val lines = text.split('\n')
                    val w = lines.maxOf { it.length } * fs * 0.56f + 8f
                    val h = lines.size * fs * 1.25f + 6f
                    vm.addMarkup(page, Markup.Text(RectF(at.x, at.y, at.x + w, at.y + h), text, vm.color, fs, bold))
                }
                textRequest = null
            },
        )
    }
    if (showUnsaved) AlertDialog(
        onDismissRequest = { showUnsaved = false },
        title = { Text("Save your markup?") },
        text = { Text("You have unsaved changes to this PDF.") },
        confirmButton = { TextButton({ showUnsaved = false; vm.save(); onClose() }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton({ showUnsaved = false }) { Text("Cancel") }
                TextButton({ showUnsaved = false; onClose() }) { Text("Discard") }
            }
        },
    )
}

@Composable
private fun SearchBar(vm: ReaderViewModel, onClose: () -> Unit) {
    val c = LocalPteron.current
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    PillCard(Modifier.fillMaxWidth()) {
        BarIconButton(PIcon.Back, "Close search", onClose)
        Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            if (vm.query.isEmpty()) Text("Search in document", color = c.onBar.copy(alpha = 0.45f), fontSize = 15.sp)
            BasicTextField(
                vm.query, { vm.query = it },
                Modifier.fillMaxWidth().focusRequester(focus),
                singleLine = true, textStyle = TextStyle(color = c.onBar, fontSize = 15.sp),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.runSearch(); kb?.hide() }),
            )
        }
        when {
            vm.searching -> CircularProgressIndicator(Modifier.size(20.dp).padding(2.dp), strokeWidth = 2.dp, color = c.accent)
            vm.hits.isNotEmpty() -> Text("${vm.hitIndex + 1}/${vm.hits.size}", fontSize = 12.sp, color = c.onBar.copy(alpha = 0.7f))
            vm.searchedOnce -> Text("No results", fontSize = 12.sp, color = c.onBar.copy(alpha = 0.7f))
        }
        BarIconButton(PIcon.Up, "Previous", vm::prevHit, enabled = vm.hits.isNotEmpty())
        BarIconButton(PIcon.Down, "Next", vm::nextHit, enabled = vm.hits.isNotEmpty())
    }
}

@Composable
private fun MarkupBar(vm: ReaderViewModel, onColor: () -> Unit) {
    val c = LocalPteron.current
    Box(Modifier.navigationBarsPadding().padding(horizontal = 10.dp, vertical = 10.dp)) {
        PillCard(Modifier.fillMaxWidth()) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                listOf(
                    Tool.PenWrite to PIcon.Pen, Tool.PenMark to PIcon.Marker, Tool.Highlighter to PIcon.Highlighter,
                    Tool.Line to PIcon.Line, Tool.Arrow to PIcon.Arrow, Tool.Rect to PIcon.Rect, Tool.Circle to PIcon.Circle,
                    Tool.Text to PIcon.Text, Tool.Erase to PIcon.Eraser,
                ).forEach { (t, icon) -> BarIconButton(icon, t.name, { vm.tool = t }, selected = vm.tool == t) }
            }
            Box(
                Modifier.padding(horizontal = 4.dp).size(30.dp).clip(CircleShape)
                    .background(Color(vm.color)).border(2.dp, c.onBar.copy(alpha = 0.25f), CircleShape)
                    .clickable(onClick = onColor)
            )
            BarIconButton(PIcon.Undo, "Undo", vm::undo, enabled = vm.undoStack.isNotEmpty())
            BarIconButton(PIcon.Redo, "Redo", vm::redo, enabled = vm.redoStack.isNotEmpty())
            BarIconButton(PIcon.Check, "Done", { vm.tool = Tool.None })
        }
    }
}

/** Slim draggable page scrubber with a floating page bubble. */
@Composable
private fun Scrubber(state: androidx.compose.foundation.lazy.LazyListState, count: Int, modifier: Modifier) {
    if (count < 3) return
    val c = LocalPteron.current
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var heightPx by remember { mutableIntStateOf(1) }
    var fraction by remember { mutableFloatStateOf(0f) }
    val page by remember { derivedStateOf { state.firstVisibleItemIndex } }
    val shown = if (dragging) fraction else page.toFloat() / (count - 1)
    Box(
        modifier.fillMaxHeight(0.6f).width(36.dp).onSizeChangedPx { heightPx = it }
            .pointerInput(count) {
                detectVerticalDragGestures(
                    onDragStart = { dragging = true; fraction = (it.y / heightPx).coerceIn(0f, 1f) },
                    onDragEnd = { dragging = false }, onDragCancel = { dragging = false },
                ) { change, _ ->
                    fraction = (change.position.y / heightPx).coerceIn(0f, 1f)
                    scope.launch { state.scrollToItem((fraction * (count - 1)).roundToInt()) }
                }
            },
    ) {
        Box(
            Modifier.align(Alignment.TopEnd)
                .offset { IntOffset(0, (shown * (heightPx - 56.dp.roundToPx())).roundToInt()) }
                .padding(end = 4.dp).size(width = 5.dp, height = 56.dp)
                .clip(RoundedCornerShape(3.dp)).background(c.accent.copy(alpha = if (dragging) 0.95f else 0.45f))
        )
        if (dragging) Box(
            Modifier.align(Alignment.TopEnd)
                .offset { IntOffset(-30.dp.roundToPx(), (shown * (heightPx - 56.dp.roundToPx())).roundToInt() + 10.dp.roundToPx()) }
                .clip(RoundedCornerShape(14.dp)).background(c.accent).padding(horizontal = 12.dp, vertical = 6.dp)
        ) { Text("${(fraction * (count - 1)).roundToInt() + 1}", color = c.onAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
    }
}

private fun Modifier.onSizeChangedPx(f: (Int) -> Unit) =
    this.then(Modifier.onSizeChanged { f(it.height) })

@Composable
private fun ThumbnailGrid(vm: ReaderViewModel, engine: PdfEngine, onPick: (Int) -> Unit) {
    val c = LocalPteron.current
    Text("Pages", Modifier.padding(horizontal = 20.dp, vertical = 6.dp), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.onBar)
    LazyVerticalGrid(
        GridCells.Fixed(3), Modifier.fillMaxWidth().heightIn(max = 520.dp),
        contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items((0 until engine.pageCount).toList(), key = { it }) { i ->
            var bmp by remember { mutableStateOf(vm.cached(i, 240)) }
            val v = vm.pageVersion(i)
            LaunchedEffect(i, v) { bmp = vm.cached(i, 240) ?: vm.render(i, 240) ?: bmp }
            val sel = i == vm.currentPage
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onPick(i) }) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1f / engine.pages[i].aspect).background(Color.White)
                        .border(if (sel) 2.dp else 0.dp, if (sel) c.accent else Color.Transparent, RoundedCornerShape(4.dp))
                ) { bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) } }
                Text("${i + 1}", Modifier.padding(top = 4.dp), fontSize = 12.sp, color = if (sel) c.accent else c.onBar.copy(alpha = 0.7f))
            }
        }
    }
}

private val Swatches = listOf(0xFF0A84FF, 0xFFE5383B, 0xFFFF9F0A, 0xFFFFD60A, 0xFF30D158, 0xFF0E6E5E, 0xFF8E44AD, 0xFFFF2D92, 0xFF1C1C1E, 0xFFFFFFFF)

@Composable
private fun ColorSheetContent(vm: ReaderViewModel) {
    val c = LocalPteron.current
    var hue by remember { mutableFloatStateOf(210f) }
    val tool = vm.tool
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).navigationBarsPadding()) {
        Text("Color & thickness", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.onBar)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Swatches.forEach { s ->
                val col = Color(s)
                Box(Modifier.weight(1f).aspectRatio(1f).clip(CircleShape).background(col)
                    .border(if (vm.color == col.toArgb()) 3.dp else 1.dp, if (vm.color == col.toArgb()) c.accent else c.onBar.copy(alpha = 0.2f), CircleShape)
                    .clickable { vm.color = col.toArgb() })
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("Custom", fontSize = 12.sp, color = c.onBar.copy(alpha = 0.6f))
        val hueBrush = Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f, 0.85f, 0.95f) })
        Box(Modifier.fillMaxWidth().height(28.dp)) {
            Box(Modifier.align(Alignment.Center).fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(hueBrush))
            Slider(hue, { hue = it; vm.color = Color.hsv(it, 0.85f, 0.95f).toArgb() }, valueRange = 0f..360f,
                colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent))
        }
        if (tool != Tool.Text && tool != Tool.Erase && tool != Tool.None) {
            Spacer(Modifier.height(10.dp))
            val w = vm.widths[tool] ?: 3f
            @Suppress("UNUSED_VARIABLE") val t = vm.widthTick
            Text("Thickness  ${"%.1f".format(w)}", fontSize = 12.sp, color = c.onBar.copy(alpha = 0.6f))
            Slider(w, { vm.widths[tool] = it; vm.widthTick++ }, valueRange = 1f..28f,
                colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent))
            Box(Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth(0.6f).height(w.dp).clip(CircleShape).background(Color(vm.color).copy(alpha = if (tool == Tool.Highlighter) 0.35f else 1f)))
            }
        }
    }
}

@Composable
private fun TextDialog(size: Float, onSize: (Float) -> Unit, onDismiss: () -> Unit, onConfirm: (String, Boolean) -> Unit) {
    var text by remember { mutableStateOf("") }
    var bold by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add text") },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Size", fontSize = 13.sp); Spacer(Modifier.width(10.dp))
                    Slider(size, onSize, valueRange = 10f..40f, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(bold, { bold = it }); Text("Bold")
                }
            }
        },
        confirmButton = { TextButton({ onConfirm(text, bold) }) { Text("Add") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
