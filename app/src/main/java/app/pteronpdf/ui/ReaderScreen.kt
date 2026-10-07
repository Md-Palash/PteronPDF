package app.pteronpdf.ui

import android.graphics.PointF
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pteronpdf.data.AppSettings
import app.pteronpdf.pdf.*
import app.pteronpdf.theme.LocalPteron
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private sealed interface TextReq {
    data class New(val page: Int, val at: PointF) : TextReq
    data class Edit(val item: Item) : TextReq
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(vm: ReaderViewModel, settings: AppSettings, onClose: () -> Unit, onSettings: () -> Unit) {
    val c = LocalPteron.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val engine = vm.engine
    val reading = settings.readingMode

    var chromeVisible by remember { mutableStateOf(true) }
    var searchOpen by remember { mutableStateOf(false) }
    var showThumbs by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showColor by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showUnsaved by remember { mutableStateOf(false) }
    var deleteIndex by remember { mutableStateOf<Int?>(null) }
    var textRequest by remember { mutableStateOf<TextReq?>(null) }
    val editing = vm.tool != Tool.None
    // Reading mode: the bar hides until you tap the page. Otherwise the bar is always there.
    val barVisible = !reading || chromeVisible || editing || searchOpen

    LaunchedEffect(reading) { chromeVisible = !reading }

    // Keep screen awake (setting), only while reading
    val view = LocalView.current
    DisposableEffect(settings.keepAwake) {
        view.keepScreenOn = settings.keepAwake
        onDispose { view.keepScreenOn = false }
    }

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
            editing -> vm.chooseTool(Tool.None)
            else -> requestClose()
        }
    }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topPad = if (reading) statusTop + 8.dp else statusTop + TopBarHeight + 10.dp
    val bottomPad = if (editing) 290.dp else 96.dp

    Box(Modifier.fillMaxSize().background(c.canvas)) {
        when {
            vm.error != null -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(vm.error!!, color = c.onBar); Spacer(Modifier.height(16.dp))
                Button(onClick = onClose, colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)) { Text("Back") }
            }
            engine == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = c.accent)
            else -> {
                PageList(
                    vm, engine, listState,
                    onText = { p, pt -> textRequest = TextReq.New(p, pt) },
                    onEditText = { textRequest = TextReq.Edit(it) },
                    onTapEmpty = { if (reading && !editing) chromeVisible = !chromeVisible },
                    topPad = topPad, bottomPad = bottomPad,
                )
                Scrubber(listState, engine.pageCount, Modifier.align(Alignment.CenterEnd))
                // a mark being carried between pages is painted here, above the pages and under the bars
                DragGhost(vm)
            }
        }

        // ── fixed top bar ──
        AnimatedVisibility(
            visible = barVisible,
            enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            FixedTopBar {
                // search and the normal bar trade places with a quick cross-fade
                AnimatedContent(
                    searchOpen, Modifier.weight(1f),
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(110)) }, label = "bar",
                ) { isSearch ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (isSearch) SearchRow(vm, onClose = { searchOpen = false; vm.clearSearch() })
                        else {
                            BarIconButton(PIcon.Back, "Back", ::requestClose)
                            // the name takes whatever room is left and is cut off with … when it doesn't fit
                            Text(
                                vm.name, Modifier.weight(1f).padding(horizontal = 4.dp),
                                fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.onBar,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (vm.dirty) Box(Modifier.padding(horizontal = 4.dp).size(7.dp).clip(CircleShape).background(c.accent))
                            if (engine != null) Text(
                                "${vm.currentPage + 1}/${engine.pageCount}",
                                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onBar,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                            BarIconButton(
                                PIcon.Trash, "Delete this page", { deleteIndex = vm.currentPage },
                                enabled = engine != null && engine.pageCount > 1,
                            )
                            BarIconButton(PIcon.Search, "Search", { searchOpen = true })
                            Box {
                                BarIconButton(PIcon.More, "More", { showMenu = true })
                                DropdownMenu(
                                    expanded = showMenu, onDismissRequest = { showMenu = false },
                                    modifier = Modifier.width(230.dp),
                                    shape = RoundedCornerShape(20.dp), containerColor = c.bar,
                                ) {
                                    MenuItem(PIcon.Grid, "Pages") { showMenu = false; showThumbs = true }
                                    MenuItem(PIcon.Save, "Save", enabled = vm.dirty) { showMenu = false; vm.save() }
                                    MenuItem(PIcon.Copy, "Save a copy…") { showMenu = false; saveAs.launch(vm.name.removeSuffix(".pdf") + " (copy).pdf") }
                                    MenuItem(PIcon.Palette, "Theme") { showMenu = false; showTheme = true }
                                    MenuItem(PIcon.Settings, "Settings") { showMenu = false; onSettings() }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── edit button: small rounded square, bottom right ──
        AnimatedVisibility(
            visible = !editing && barVisible && engine != null,
            enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            Box(
                Modifier.navigationBarsPadding().padding(end = 16.dp, bottom = 16.dp).size(52.dp)
                    .shadow(8.dp, RoundedCornerShape(16.dp)).pressable(RoundedCornerShape(16.dp), c.accent) { vm.chooseTool(Tool.Pen) },
                contentAlignment = Alignment.Center,
            ) { PIconView(PIcon.Edit, c.onAccent, Modifier.size(24.dp), "Edit") }
        }

        // ── editing card: slides up from the bottom, top corners rounded ──
        AnimatedVisibility(
            visible = editing,
            enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            EditPanel(vm, onCustomColor = { showColor = true }, onEditText = { textRequest = TextReq.Edit(it) })
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (editing) 300.dp else 90.dp))
    }

    // ── sheets & dialogs ──
    if (showThumbs && engine != null) ModalBottomSheet(onDismissRequest = { showThumbs = false }, containerColor = c.bar) {
        ThumbnailGrid(vm, engine, onPick = { i -> showThumbs = false; scope.launch { listState.scrollToItem(i) } })
    }
    if (showTheme) ModalBottomSheet(onDismissRequest = { showTheme = false }, containerColor = c.bar) {
        ThemeSheetContent(settings)
    }
    if (showColor) ModalBottomSheet(onDismissRequest = { showColor = false }, containerColor = c.bar) {
        HueSheetContent(vm)
    }
    textRequest?.let { req ->
        TextDialog(
            title = if (req is TextReq.New) "Add comment" else "Edit comment",
            initial = (req as? TextReq.Edit)?.item?.m.let { (it as? Markup.Text)?.text ?: "" },
            onDismiss = { textRequest = null },
            onConfirm = { text ->
                when (req) {
                    is TextReq.New -> vm.addText(req.page, req.at, text)
                    is TextReq.Edit -> vm.updateText(req.item, text)
                }
                textRequest = null
            },
        )
    }
    deleteIndex?.let { idx ->
        AlertDialog(
            onDismissRequest = { deleteIndex = null },
            title = { Text("Delete page ${idx + 1}?") },
            text = { Text("The page, and any markup on it, is removed from the PDF. The file itself only changes when you save, and the undo history is cleared.") },
            confirmButton = { TextButton({ deleteIndex = null; vm.deletePage(idx) }) { Text("Delete") } },
            dismissButton = { TextButton({ deleteIndex = null }) { Text("Cancel") } },
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

/** A menu entry with its icon in front of the label. */
@Composable
private fun MenuItem(icon: PIcon, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalPteron.current
    DropdownMenuItem(
        text = { Text(label) }, onClick = onClick, enabled = enabled,
        leadingIcon = { PIconView(icon, if (enabled) c.onBar else c.onBar.copy(alpha = 0.38f), Modifier.size(20.dp)) },
    )
}

@Composable
private fun RowScope.SearchRow(vm: ReaderViewModel, onClose: () -> Unit) {
    val c = LocalPteron.current
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
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

private val PenSwatches = listOf(0xFF0A84FF, 0xFFE5383B, 0xFFFF9F0A, 0xFFFFD60A, 0xFF30D158, 0xFF0E6E5E, 0xFF8E44AD, 0xFFFF2D92, 0xFF1C1C1E, 0xFFFFFFFF)
private val HighlightSwatches = listOf(0xFFFFD60A, 0xFF7CF29A, 0xFFFF8FB8, 0xFFFFA24D, 0xFF6EC6FF, 0xFFC59BFF)

private data class ToolDef(val tool: Tool, val icon: PIcon, val label: String)
private val ToolDefs = listOf(
    ToolDef(Tool.Select, PIcon.Select, "Select"),
    ToolDef(Tool.Pen, PIcon.Pen, "Pen"),
    ToolDef(Tool.Highlighter, PIcon.Highlighter, "Highlighter"),
    ToolDef(Tool.Line, PIcon.Line, "Line"),
    ToolDef(Tool.Curve, PIcon.Curve, "Curve"),
    ToolDef(Tool.Arrow, PIcon.Arrow, "Arrow"),
    ToolDef(Tool.Rect, PIcon.Rect, "Square"),
    ToolDef(Tool.Circle, PIcon.Circle, "Circle"),
    ToolDef(Tool.Text, PIcon.Text, "Comment"),
    ToolDef(Tool.Erase, PIcon.Eraser, "Erase"),
)

@Composable
private fun EditPanel(vm: ReaderViewModel, onCustomColor: () -> Unit, onEditText: (Item) -> Unit) {
    val c = LocalPteron.current
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
    val sel = vm.selected
    val tool = vm.tool
    val selText = sel?.m as? Markup.Text
    val showText = tool == Tool.Text || selText != null
    val widthNow = vm.currentWidth()
    val showWidth = !showText && widthNow != null && tool != Tool.Erase
    val activeColor = sel?.m?.color ?: vm.color
    // a highlighter stroke is an ink stroke with some transparency; it gets its own colours and a wider thickness range
    val selInk = sel?.m as? Markup.Ink
    val isHighlighter = if (sel != null) selInk != null && selInk.opacity < 1f else tool == Tool.Highlighter
    val swatches = if (isHighlighter) HighlightSwatches else PenSwatches

    Column(
        Modifier.fillMaxWidth().shadow(14.dp, shape).clip(shape).background(c.bar)
            .animateContentSize(tween(220))        // rows that come and go (colours, thickness, text options) grow the card smoothly
            .navigationBarsPadding().padding(top = 8.dp, bottom = 8.dp)
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(c.onBar.copy(alpha = 0.22f)))
        Spacer(Modifier.height(8.dp))

        // tools
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ToolDefs.forEach { d ->
                val on = tool == d.tool
                // the highlight fades between tools instead of jumping (it fades from the accent at zero alpha, never through grey)
                val bg by animateColorAsState(if (on) c.accent else c.accent.copy(alpha = 0f), tween(180), label = "tool")
                val fg by animateColorAsState(if (on) c.onAccent else c.onBar, tween(180), label = "toolText")
                Column(
                    Modifier.width(60.dp).pressable(RoundedCornerShape(14.dp), bg) { vm.chooseTool(d.tool) }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PIconView(d.icon, fg, Modifier.size(22.dp), d.label)
                    Spacer(Modifier.height(3.dp))
                    Text(d.label, fontSize = 10.sp, color = fg, maxLines = 1)
                }
            }
        }

        // colours
        AnimatedVisibility(tool != Tool.Erase && tool != Tool.Select || sel != null) {
          Column {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                swatches.forEach { sw ->
                    val col = Color(sw); val on = activeColor == col.toArgb()
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(col)
                            .border(if (on) 3.dp else 1.dp, if (on) c.accent else c.onBar.copy(alpha = 0.2f), CircleShape)
                            .clickable { vm.applyColor(col.toArgb()) }
                    )
                }
                Box(
                    Modifier.size(30.dp).clip(CircleShape)
                        .background(Brush.sweepGradient((0..6).map { Color.hsv(it * 60f, 0.85f, 0.95f) }))
                        .border(1.dp, c.onBar.copy(alpha = 0.2f), CircleShape).clickable(onClick = onCustomColor)
                )
            }
          }
        }

        // thickness / text options / hints
        val mode = when {
            showWidth -> 1
            showText -> 2
            tool == Tool.Select && sel == null -> 3
            tool == Tool.Erase -> 4
            else -> 0
        }
        AnimatedContent(
            mode,
            transitionSpec = { fadeIn(tween(170)) togetherWith fadeOut(tween(100)) using SizeTransform(clip = false) },
            label = "options",
        ) { m ->
            Column {
                when (m) {
                    1 -> LabeledSlider("Thickness", widthNow ?: 1f, if (isHighlighter) 4f..40f else 0.5f..12f) { vm.setWidth(it) }
                    2 -> {
                        val size = selText?.fontSize ?: vm.textSize
                        val bold = selText?.bold ?: vm.textBold
                        LabeledSlider("Text size", size, 8f..48f) { vm.setTextStyle(size = it) }
                        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ToggleChip("Bold", bold) { vm.setTextStyle(bold = !bold, coalesce = false) }
                            if (sel != null && selText != null) ToggleChip("Edit text", false) { onEditText(sel) }
                            if (sel == null) Text("Tap the page to place a comment", fontSize = 12.sp, color = c.onBar.copy(alpha = 0.6f))
                        }
                    }
                    3 -> Hint("Tap a mark to select it, then drag to move or use the dots to resize")
                    4 -> Hint("Tap or drag over a mark to erase it")
                    else -> {}
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        // actions
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BarIconButton(PIcon.Undo, "Undo", vm::undo, enabled = vm.undoStack.isNotEmpty())
            BarIconButton(PIcon.Redo, "Redo", vm::redo, enabled = vm.redoStack.isNotEmpty())
            BarIconButton(PIcon.Trash, "Delete", vm::removeSelected, enabled = sel != null)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.clip(RoundedCornerShape(14.dp)).background(c.accent).clickable { vm.chooseTool(Tool.None) }
                    .padding(horizontal = 22.dp, vertical = 10.dp)
            ) { Text("Done", color = c.onAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun Hint(text: String) {
    val c = LocalPteron.current
    Text(text, Modifier.padding(horizontal = 18.dp, vertical = 8.dp), fontSize = 12.sp, color = c.onBar.copy(alpha = 0.65f))
}

@Composable
private fun ToggleChip(label: String, on: Boolean, onClick: () -> Unit) {
    val c = LocalPteron.current
    Box(
        Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) c.accent else c.chip).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) { Text(label, fontSize = 13.sp, color = if (on) c.onAccent else c.onBar) }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val c = LocalPteron.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$label  ${"%.1f".format(value)}", fontSize = 12.sp, color = c.onBar.copy(alpha = 0.7f), modifier = Modifier.width(108.dp))
        Slider(
            value.coerceIn(range), onChange, valueRange = range, modifier = Modifier.weight(1f).height(36.dp),
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.chip),
        )
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

/** Custom colour: a hue slider (the quick swatches live in the editing card). */
@Composable
private fun HueSheetContent(vm: ReaderViewModel) {
    val c = LocalPteron.current
    var hue by remember { mutableFloatStateOf(210f) }
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).navigationBarsPadding()) {
        Text("Custom colour", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.onBar)
        Spacer(Modifier.height(14.dp))
        val hueBrush = Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f, 0.85f, 0.95f) })
        Box(Modifier.fillMaxWidth().height(28.dp)) {
            Box(Modifier.align(Alignment.Center).fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(hueBrush))
            Slider(hue, { hue = it; vm.applyColor(Color.hsv(it, 0.85f, 0.95f).toArgb(), coalesce = true) }, valueRange = 0f..360f,
                colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent))
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(12.dp)).background(Color(vm.selected?.m?.color ?: vm.color)))
    }
}

@Composable
private fun TextDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 6) },
        confirmButton = { TextButton({ onConfirm(text) }) { Text(if (initial.isEmpty()) "Add" else "Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
