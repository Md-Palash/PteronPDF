package app.pteronpdf.pdf

import android.app.Application
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pteronpdf.data.Prefs
import app.pteronpdf.data.RecentDoc
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReaderViewModel(app: Application, val uri: Uri) : AndroidViewModel(app) {
    private val prefs = Prefs(app)

    // ── document state ──
    var engine by mutableStateOf<PdfEngine?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var name by mutableStateOf("PDF"); private set
    var message by mutableStateOf<String?>(null)
    var needsSaveAs by mutableStateOf(false)
    var initialPage = 0; private set

    /** Bumps on every change; [dirty] compares it to the value at the last save. */
    private var editCount by mutableIntStateOf(0)
    private var savedAt by mutableIntStateOf(0)
    private var engineEdited by mutableStateOf(false)   // an annotation stored in the file was erased
    val dirty: Boolean get() = editCount != savedAt || engineEdited

    // ── view state ──
    var zoom by mutableFloatStateOf(1f)
    var currentPage by mutableIntStateOf(0)

    // ── markup state ──
    var tool by mutableStateOf(Tool.None)
    var color by mutableIntStateOf(0xFF0A84FF.toInt()); private set
    /** Stroke width per tool, in PDF points. */
    val widths = mutableStateMapOf(
        Tool.Pen to 1.4f, Tool.Line to 2f, Tool.Curve to 2f, Tool.Arrow to 2f, Tool.Rect to 2f, Tool.Circle to 2f,
    )
    var textSize by mutableFloatStateOf(14f)     // PDF points
    var textBold by mutableStateOf(false)

    /** Markup made this session, in z-order. Painted as vectors by the overlay and written to the PDF on save. */
    val items = mutableStateListOf<Item>()
    var selectedId by mutableStateOf<Long?>(null); private set
    /** Item currently being dragged: the overlay paints this instead of the stored version. */
    var preview by mutableStateOf<Item?>(null); private set

    val selected: Item? get() = selectedId?.let { id -> items.firstOrNull { it.id == id } }

    val undoStack = mutableStateListOf<HistoryEntry>()
    val redoStack = mutableStateListOf<HistoryEntry>()

    private val pageVersions = mutableStateOf<Map<Int, Int>>(emptyMap())
    fun pageVersion(i: Int) = pageVersions.value[i] ?: 0
    private fun bump(i: Int) { pageVersions.value = pageVersions.value + (i to pageVersion(i) + 1) }

    // ── search state ──
    var query by mutableStateOf("")
    var hits by mutableStateOf<List<PdfEngine.Hit>>(emptyList()); private set
    var hitIndex by mutableIntStateOf(-1); private set
    var searching by mutableStateOf(false); private set
    var searchedOnce by mutableStateOf(false); private set
    private var searchJob: Job? = null

    // ── bitmap cache: sized from the device's own heap limit (works on 512 MB phones and flagships) ──
    private val memClass = (app.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).memoryClass
    /** Max render width relative to screen width. Smaller on low-memory phones so one zoomed page can't exhaust the heap. */
    val renderCapFactor = when { memClass <= 96 -> 1.4f; memClass <= 192 -> 2f; else -> 2.5f }
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceIn(6 shl 20, 64 shl 20)
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private fun key(i: Int, w: Int, v: Int) = "$i:$w:$v"
    fun cached(i: Int, w: Int): Bitmap? = cache.get(key(i, w, pageVersion(i)))

    suspend fun render(i: Int, w: Int): Bitmap? {
        val e = engine ?: return null
        val v = pageVersion(i)
        cache.get(key(i, w, v))?.let { return it }
        val bmp = e.render(i, w)
        cache.put(key(i, w, v), bmp)
        return bmp
    }

    init { viewModelScope.launch { load() } }

    private suspend fun load() {
        try {
            name = queryName(uri)
            val e = PdfEngine.open(getApplication(), uri)
            engine = e
            initialPage = if (prefs.rememberPosition)
                prefs.recents().firstOrNull { it.uri == uri }?.lastPage?.coerceIn(0, e.pageCount - 1) ?: 0
            else 0
            currentPage = initialPage
            touchRecent()
        } catch (t: Throwable) {
            error = t.message ?: "Couldn't open this PDF"
        }
    }

    private fun queryName(u: Uri): String = runCatching {
        getApplication<Application>().contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: u.lastPathSegment ?: "PDF"

    fun touchRecent() {
        val e = engine ?: return
        prefs.touchRecent(RecentDoc(uri, name, currentPage, e.pageCount, System.currentTimeMillis()))
    }

    // ───────── tools & selection ─────────
    private var inkColor = color
    private var highlightColor = 0xFFFFD60A.toInt()

    /** The text highlighter keeps its own colour (yellow by default) so switching tools doesn't turn pens yellow. */
    fun chooseTool(t: Tool) {
        val wasHl = tool == Tool.Highlight; val toHl = t == Tool.Highlight
        if (!wasHl && toHl) { inkColor = color; color = highlightColor }
        else if (wasHl && !toHl) { highlightColor = color; color = inkColor }
        tool = t
        if (t != Tool.Select) selectedId = null
        clearTextSelection()
    }

    fun select(id: Long?) { selectedId = id }

    fun pick(page: Int, p: PointF, tol: Float): Item? =
        items.lastOrNull { it.page == page && Geom.hit(it.m, p, tol) }

    // ───────── markup actions ─────────
    private var idCounter = System.currentTimeMillis()

    fun addMarkup(page: Int, m: Markup, selectIt: Boolean = true) {
        val item = Item(++idCounter, page, m)
        items += item
        undoStack += HistoryEntry.Added(item, items.lastIndex); redoStack.clear()
        if (selectIt) selectedId = item.id
        editCount++
    }

    fun liveEdit(item: Item?) { preview = item }

    /** Replaces [old] with [new]. [coalesce] merges into the previous step when it edited the same item (slider drags). */
    fun commitEdit(old: Item, new: Item, coalesce: Boolean = false) {
        val idx = items.indexOfFirst { it.id == old.id }
        if (idx < 0) { preview = null; return }
        items[idx] = new
        preview = null
        val last = undoStack.lastOrNull()
        if (coalesce && last is HistoryEntry.Edited && last.new.id == old.id) {
            undoStack[undoStack.lastIndex] = last.copy(new = new)
        } else {
            undoStack += HistoryEntry.Edited(old, new)
        }
        redoStack.clear()
        editCount++
    }

    private fun editSelected(coalesce: Boolean, f: (Markup) -> Markup) {
        val s = selected ?: return
        val nm = f(s.m)
        if (nm != s.m) commitEdit(s, s.copy(m = nm), coalesce)
    }

    /** Colour applies to the selected markup too, so everything stays editable after it is drawn. */
    fun applyColor(c: Int, coalesce: Boolean = false) {
        color = c
        editSelected(coalesce) { it.withColor(c) }
    }

    /** Thickness of the active tool, or of the selected markup if there is one. */
    fun setWidth(w: Float, coalesce: Boolean = true) {
        val s = selected
        if (s != null && Geom.widthOf(s.m) != null) editSelected(coalesce) { Geom.withWidth(it, w) }
        else widths[tool] = w
        if (s != null) toolForSelected()?.let { widths[it] = w }
    }

    fun currentWidth(): Float? {
        val s = selected
        if (s != null) return Geom.widthOf(s.m)
        return widths[tool]
    }

    private fun toolForSelected(): Tool? = when (val m = selected?.m) {
        is Markup.Ink -> Tool.Pen
        is Markup.Curve -> Tool.Curve
        is Markup.Shape -> when (m.kind) {
            ShapeKind.Line -> Tool.Line; ShapeKind.Arrow -> Tool.Arrow; ShapeKind.Rect -> Tool.Rect; ShapeKind.Circle -> Tool.Circle
        }
        else -> null
    }

    fun setTextStyle(size: Float? = null, bold: Boolean? = null, coalesce: Boolean = true) {
        size?.let { textSize = it }; bold?.let { textBold = it }
        editSelected(coalesce) { m ->
            if (m is Markup.Text) m.copy(fontSize = size ?: m.fontSize, bold = bold ?: m.bold) else m
        }
    }

    fun addText(page: Int, at: PointF, text: String) {
        if (text.isBlank()) return
        addMarkup(page, Markup.Text(Geom.textRect(at, text, textSize), text, color, textSize, textBold))
    }

    fun updateText(item: Item, text: String) {
        val m = item.m as? Markup.Text ?: return
        if (text.isBlank()) { remove(item); return }
        if (text == m.text) return
        // Grow the box if the new text no longer fits; never shrink what the user sized by hand.
        val need = Geom.textRect(PointF(m.rect.left, m.rect.top), text, m.fontSize)
        val box = RectF(m.rect.left, m.rect.top, maxOf(m.rect.right, need.right), maxOf(m.rect.bottom, need.bottom))
        commitEdit(item, item.copy(m = m.copy(text = text, rect = box)))
    }

    fun remove(item: Item) {
        val idx = items.indexOfFirst { it.id == item.id }
        if (idx < 0) return
        items.removeAt(idx)
        undoStack += HistoryEntry.Removed(item, idx); redoStack.clear()
        if (selectedId == item.id) selectedId = null
        editCount++
    }

    fun removeSelected() { selected?.let(::remove) }

    /** Eraser: takes out session markup first; otherwise annotations already stored in the file (not undoable). */
    fun erase(page: Int, p: PointF, tolPdf: Float) {
        pick(page, p, tolPdf)?.let { remove(it); return }
        val e = engine ?: return
        viewModelScope.launch {
            try {
                if (e.eraseAt(page, p.x, p.y, tolPdf)) { engineEdited = true; bump(page) }
            } catch (t: Throwable) { message = "Couldn't erase: ${t.message}" }
        }
    }

    // ───────── text highlight (live preview while dragging) ─────────
    var selPage by mutableIntStateOf(-1); private set
    var selRects by mutableStateOf<List<RectF>>(emptyList()); private set
    private var selJob: Job? = null

    fun previewTextSelection(page: Int, a: PointF, b: PointF) {
        val e = engine ?: return
        selJob?.cancel()
        selJob = viewModelScope.launch {
            val r = e.selectText(page, a, b)
            selPage = page; selRects = r
        }
    }

    fun commitTextSelection() {
        val job = selJob
        val page = selPage
        viewModelScope.launch {
            job?.join()
            val r = selRects
            if (r.isNotEmpty() && selPage >= 0) addMarkup(page, Markup.Highlight(r, color))
            clearTextSelection()
        }
    }

    fun clearTextSelection() { selJob?.cancel(); selPage = -1; selRects = emptyList() }

    // ───────── undo / redo ─────────
    fun undo() {
        val last = undoStack.lastOrNull() ?: return
        when (last) {
            is HistoryEntry.Added -> items.removeAll { it.id == last.item.id }
            is HistoryEntry.Removed -> items.add(last.index.coerceIn(0, items.size), last.item)
            is HistoryEntry.Edited -> replace(last.new.id, last.old)
        }
        undoStack.removeAt(undoStack.lastIndex); redoStack += last
        fixSelection(); editCount++
    }

    fun redo() {
        val last = redoStack.lastOrNull() ?: return
        when (last) {
            is HistoryEntry.Added -> items.add(last.index.coerceIn(0, items.size), last.item)
            is HistoryEntry.Removed -> items.removeAll { it.id == last.item.id }
            is HistoryEntry.Edited -> replace(last.old.id, last.new)
        }
        redoStack.removeAt(redoStack.lastIndex); undoStack += last
        fixSelection(); editCount++
    }

    private fun replace(id: Long, with: Item) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) items[i] = with
    }

    private fun fixSelection() { if (selectedId != null && selected == null) selectedId = null; preview = null }

    // ───────── search ─────────
    fun runSearch() {
        val e = engine ?: return
        searchJob?.cancel()
        val q = query.trim()
        if (q.isEmpty()) { hits = emptyList(); hitIndex = -1; searchedOnce = false; return }
        searchJob = viewModelScope.launch {
            searching = true
            val r = e.search(q)
            hits = r; hitIndex = if (r.isEmpty()) -1 else 0
            searching = false; searchedOnce = true
        }
    }
    fun nextHit() { if (hits.isNotEmpty()) hitIndex = (hitIndex + 1) % hits.size }
    fun prevHit() { if (hits.isNotEmpty()) hitIndex = (hitIndex - 1 + hits.size) % hits.size }
    fun clearSearch() { searchJob?.cancel(); query = ""; hits = emptyList(); hitIndex = -1; searching = false; searchedOnce = false }

    // ───────── save ─────────
    /** Writes back to the original file; if the provider is read-only, asks the UI for Save As. */
    fun save() {
        val e = engine ?: return
        viewModelScope.launch {
            try { e.saveWith(uri, items.toList()); afterSave() }
            catch (t: Throwable) { needsSaveAs = true }
        }
    }

    fun saveAs(target: Uri) {
        val e = engine ?: return
        viewModelScope.launch {
            try { e.saveWith(target, items.toList()); message = "Saved a copy"; savedAt = editCount; engineEdited = false }
            catch (t: Throwable) { message = "Save failed: ${t.message}" }
        }
    }

    /** The source file's bytes changed under us, so reopen it: the markup is baked in now, so the live list is emptied. */
    private suspend fun afterSave() {
        val old = engine
        val fresh = PdfEngine.open(getApplication(), uri)
        engine = fresh
        cache.evictAll()
        items.clear(); undoStack.clear(); redoStack.clear()
        selectedId = null; preview = null
        pageVersions.value = emptyMap()
        savedAt = editCount; engineEdited = false
        message = "Saved"
        old?.close()
    }

    override fun onCleared() {
        touchRecent()
        val e = engine; engine = null
        cache.evictAll()
        // viewModelScope is already cancelled here; close on a non-cancellable context.
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            withContext(NonCancellable) { e?.close() }
        }
    }
}
