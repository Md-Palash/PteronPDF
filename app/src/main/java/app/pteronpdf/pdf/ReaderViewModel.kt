package app.pteronpdf.pdf

import android.app.Application
import android.graphics.Bitmap
import android.graphics.PointF
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
    var dirty by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var needsSaveAs by mutableStateOf(false)
    var initialPage = 0; private set

    // ── view state ──
    var zoom by mutableFloatStateOf(1f)
    var currentPage by mutableIntStateOf(0)

    // ── markup state ──
    var tool by mutableStateOf(Tool.None)
    var color by mutableIntStateOf(0xFF0A84FF.toInt())
    /** thickness per tool, in dp */
    val widths = mutableMapOf(Tool.PenWrite to 2.2f, Tool.PenMark to 6f, Tool.Highlighter to 16f,
        Tool.Line to 3f, Tool.Arrow to 3f, Tool.Rect to 3f, Tool.Circle to 3f)
    var widthTick by mutableIntStateOf(0)       // bumps so the picker recomposes
    var textSizeDp by mutableFloatStateOf(16f)
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
            initialPage = prefs.recents().firstOrNull { it.uri == uri }?.lastPage?.coerceIn(0, e.pageCount - 1) ?: 0
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

    // ───────── markup actions ─────────
    private var idCounter = System.currentTimeMillis()
    fun addMarkup(page: Int, m: Markup) {
        val e = engine ?: return
        val id = ++idCounter
        viewModelScope.launch {
            try {
                e.add(page, id, m)
                undoStack += HistoryEntry.Added(page, id, m); redoStack.clear()
                dirty = true; bump(page)
            } catch (t: Throwable) { message = "Couldn't add markup: ${t.message}" }
        }
    }

    fun erase(page: Int, p: PointF, tolPdf: Float) {
        val e = engine ?: return
        viewModelScope.launch {
            try {
                val (id, removed) = e.eraseAt(page, p.x, p.y, tolPdf)
                if (!removed) return@launch
                dirty = true; bump(page)
                // If we made it this session we can bring it back with undo.
                val src = undoStack.lastOrNull { it.id == id } ?: redoStack.lastOrNull { it.id == id }
                if (id > 0 && src != null) { undoStack += HistoryEntry.Removed(page, id, src.markup); redoStack.clear() }
            } catch (t: Throwable) { message = "Couldn't erase: ${t.message}" }
        }
    }

    fun undo() {
        val e = engine ?: return
        val last = undoStack.lastOrNull() ?: return
        viewModelScope.launch {
            when (last) {
                is HistoryEntry.Added -> e.removeById(last.page, last.id)
                is HistoryEntry.Removed -> e.add(last.page, last.id, last.markup)
            }
            undoStack.removeAt(undoStack.lastIndex); redoStack += last
            dirty = true; bump(last.page)
        }
    }

    fun redo() {
        val e = engine ?: return
        val last = redoStack.lastOrNull() ?: return
        viewModelScope.launch {
            when (last) {
                is HistoryEntry.Added -> e.add(last.page, last.id, last.markup)
                is HistoryEntry.Removed -> e.removeById(last.page, last.id)
            }
            redoStack.removeAt(redoStack.lastIndex); undoStack += last
            dirty = true; bump(last.page)
        }
    }

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
            try { e.saveTo(uri); afterSave(uri) }
            catch (t: Throwable) { needsSaveAs = true }
        }
    }

    fun saveAs(target: Uri) {
        val e = engine ?: return
        viewModelScope.launch {
            try { e.saveTo(target); message = "Saved a copy"; dirty = false }
            catch (t: Throwable) { message = "Save failed: ${t.message}" }
        }
    }

    /** The source file's bytes changed under us, so reopen it (history is baked in now). */
    private suspend fun afterSave(@Suppress("UNUSED_PARAMETER") u: Uri) {
        val old = engine
        val fresh = PdfEngine.open(getApplication(), uri)
        engine = fresh
        cache.evictAll()
        undoStack.clear(); redoStack.clear()
        pageVersions.value = emptyMap()
        dirty = false
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
