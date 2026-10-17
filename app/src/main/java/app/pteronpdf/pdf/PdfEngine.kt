package app.pteronpdf.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.SeekableInputStream
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.text.Normalizer
import java.util.concurrent.Executors

/** MuPDF pulls bytes on demand from the file descriptor — the PDF is never loaded into RAM or copied to storage. */
private class ChannelStream(private val ch: FileChannel) : SeekableInputStream {
    override fun read(b: ByteArray): Int = ch.read(ByteBuffer.wrap(b))
    override fun seek(offset: Long, whence: Int): Long {
        val np = when (whence) {
            SeekableInputStream.SEEK_SET -> offset
            SeekableInputStream.SEEK_CUR -> ch.position() + offset
            else -> ch.size() + offset
        }
        ch.position(np); return np
    }
    override fun position(): Long = ch.position()
}

class PageInfo(val width: Float, val height: Float) { val aspect get() = height / width }

/**
 * All MuPDF access is funnelled through ONE thread: the library is not thread-safe per document.
 *
 * Markup made in this session is NOT written into the document while you work: the UI paints it as vectors,
 * so every edit (move, resize, recolor…) is instant and pages never need re-rendering. It is written into the
 * PDF only at save time ([saveWith]).
 */
class PdfEngine private constructor(
    private val context: Context,
    private val pfd: ParcelFileDescriptor,
    private val doc: PDFDocument,
    initialPages: List<PageInfo>,
) {
    private val io: CoroutineDispatcher = ENGINE_DISPATCHER

    /** Observable so the page list and counters update when a page is deleted. */
    var pages: List<PageInfo> by mutableStateOf(initialPages); private set
    val pageCount get() = pages.size

    /** Removes page [index] from the open document (the file on disk only changes when you save). */
    suspend fun deletePage(index: Int) {
        withContext(io) { doc.deletePage(index) }
        pages = pages.filterIndexed { i, _ -> i != index }
    }

    // ───────────── rendering ─────────────
    /** Renders [index] so its width is [widthPx]. Annotations already in the file are included. Caller keeps a white backdrop.
     *  [onReady] receives the finished bitmap on the engine thread (used to cache it even when the requester gave up). */
    suspend fun render(index: Int, widthPx: Int, onReady: ((Bitmap) -> Unit)? = null): Bitmap = withContext(io) {
        val page = doc.loadPage(index)
        val bmp = try {
            val b = page.bounds
            val s = widthPx / (b.x1 - b.x0)
            AndroidDrawDevice.drawPage(page, Matrix(s))
        } finally { page.destroy() }
        onReady?.invoke(bmp)     // runs on the engine thread even if the caller was cancelled meanwhile
        bmp
    }

    // ───────────── search (NFC + NFD, as on desktop) ─────────────
    data class Hit(val page: Int, val rect: RectF)

    suspend fun search(query: String, onProgress: (Int) -> Unit = {}): List<Hit> = withContext(io) {
        val nfc = Normalizer.normalize(query, Normalizer.Form.NFC)
        val nfd = Normalizer.normalize(query, Normalizer.Form.NFD)
        val variants = if (nfc == nfd) listOf(nfc) else listOf(nfc, nfd)
        val out = ArrayList<Hit>()
        for (i in 0 until pageCount) {
            val page = doc.loadPage(i)
            try {
                for (v in variants) {
                    val quads = page.search(v)
                    if (quads.isNotEmpty()) {
                        quads.forEach { group ->
                            if (group.isNotEmpty()) {
                                var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var bt = -Float.MAX_VALUE
                                group.forEach { q ->
                                    l = minOf(l, q.ul_x, q.ll_x); t = minOf(t, q.ul_y, q.ur_y)
                                    r = maxOf(r, q.ur_x, q.lr_x); bt = maxOf(bt, q.ll_y, q.lr_y)
                                }
                                out += Hit(i, RectF(l, t, r, bt))
                            }
                        }
                        break
                    }
                }
            } finally { page.destroy() }
            if (i % 20 == 0) onProgress(i)
        }
        out
    }

    // ───────────── erasing annotations already stored in the file ─────────────
    /** Removes the topmost stored annotation under [x],[y] (page coords). True if one was removed. */
    suspend fun eraseAt(pageIndex: Int, x: Float, y: Float, tol: Float): Boolean = withContext(io) {
        val page = doc.loadPage(pageIndex) as PDFPage
        try {
            val hit = page.annotations.orEmpty()
                .filter { a ->
                    val r = a.bounds
                    x >= r.x0 - tol && x <= r.x1 + tol && y >= r.y0 - tol && y <= r.y1 + tol &&
                        a.type != PDFAnnotation.TYPE_LINK && a.type != PDFAnnotation.TYPE_WIDGET
                }
                .minByOrNull { val r = it.bounds; (r.x1 - r.x0) * (r.y1 - r.y0) }
                ?: return@withContext false
            page.deleteAnnotation(hit)
            true
        } finally { page.destroy() }
    }

    // ───────────── writing markup into the PDF (save time only) ─────────────
    private fun nameOf(a: PDFAnnotation): String? = runCatching {
        val o = a.`object`.get("NM"); if (o != null && o.isString) o.asString() else null
    }.getOrNull()

    private fun rgb(c: Int) = floatArrayOf(
        ((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f,
    )

    private fun pt(p: PointF) = Point(p.x, p.y)

    private fun ink(page: PDFPage, m: Markup, width: Float, opacity: Float): PDFAnnotation =
        page.createAnnotation(PDFAnnotation.TYPE_INK).apply {
            setInkList(Geom.strokes(m).map { line -> line.map(::pt).toTypedArray() }.toTypedArray())
            setColor(rgb(m.color)); setBorderWidth(width); setOpacity(opacity)
        }

    private fun create(page: PDFPage, id: Long, m: Markup) {
        val a: PDFAnnotation = when (m) {
            is Markup.Ink -> ink(page, m, m.width, m.opacity)
            is Markup.Curve -> ink(page, m, m.width, 1f)
            is Markup.Shape -> when (m.kind) {
                // Arrow is stored as a small ink drawing so the arrow head is exactly what you saw on screen.
                ShapeKind.Arrow, ShapeKind.Tick -> ink(page, m, m.width, 1f)
                ShapeKind.Line -> page.createAnnotation(PDFAnnotation.TYPE_LINE).apply {
                    setLine(pt(m.p0), pt(m.p1)); setColor(rgb(m.color)); setBorderWidth(m.width)
                }
                ShapeKind.Rect, ShapeKind.Circle -> page.createAnnotation(
                    if (m.kind == ShapeKind.Rect) PDFAnnotation.TYPE_SQUARE else PDFAnnotation.TYPE_CIRCLE
                ).apply {
                    val r = Geom.rectOf(m.p0, m.p1)
                    setRect(Rect(r.left, r.top, r.right, r.bottom))
                    setColor(rgb(m.color)); setBorderWidth(m.width)
                }
            }
            is Markup.Text -> page.createAnnotation(PDFAnnotation.TYPE_FREE_TEXT).apply {
                setRect(Rect(m.rect.left, m.rect.top, m.rect.right, m.rect.bottom))
                setContents(m.text)
                setDefaultAppearance(if (m.bold) "HeBo" else "Helv", m.fontSize, rgb(m.color))
                setBorderWidth(0f)
            }
        }
        a.`object`.put("NM", doc.newString("pt-$id"))
        a.setFlags(4)           // "print" flag: viewers and printers must draw it
        a.update()              // builds the appearance stream every viewer paints from
    }

    // ───────────── save ─────────────
    /**
     * Writes [items] into the document, saves a full rewrite to a temp file, copies it to [target], then takes the items
     * out of the in-memory document again (so the live document never holds them twice). Temp is always removed.
     */
    suspend fun saveWith(target: Uri, items: List<Item>): Int = withContext(io) {
        val tmp = File.createTempFile("pteron", ".pdf", context.cacheDir)
        // Every page that gets a mark stays OPEN until the file is written: MuPDF builds each mark's drawing (its appearance
        // stream, which is what other PDF viewers paint) for open pages, so closing them early could leave marks that only
        // MuPDF itself can see.
        val open = LinkedHashMap<Int, PDFPage>()
        val added = ArrayList<Pair<PDFPage, Item>>()
        var missing = 0
        try {
            for (it in items) {
                val pg = open.getOrPut(it.page) { doc.loadPage(it.page) as PDFPage }
                create(pg, it.id, it.m); added += pg to it
            }
            open.values.forEach { runCatching { it.update() } }
            try { doc.save(tmp.absolutePath, "garbage=compact,compress,appearance=all") }
            catch (t: Throwable) { doc.save(tmp.absolutePath, "garbage=compact,compress") }
            // Read the finished file back and count the marks that really made it in (with a drawing), so a failure is reported, never silent.
            if (items.isNotEmpty()) missing = items.size - countEmbedded(tmp, items)
            context.contentResolver.openOutputStream(target, "wt")!!.use { out ->
                FileInputStream(tmp).use { it.copyTo(out, 64 * 1024) }
            }
        } finally {
            tmp.delete()
            added.forEach { (pg, it) -> runCatching { pg.annotations?.firstOrNull { a -> nameOf(a) == "pt-${it.id}" }?.let(pg::deleteAnnotation) } }
            open.values.forEach { runCatching { it.destroy() } }
        }
        missing.coerceAtLeast(0)
    }

    /** How many of [items] are present (by their /NM name) in the saved file at [file]. */
    private fun countEmbedded(file: File, items: List<Item>): Int = runCatching {
        val want = items.groupBy { it.page }
        val d = com.artifex.mupdf.fitz.Document.openDocument(file.absolutePath) as PDFDocument
        try {
            var found = 0
            for ((pageIndex, list) in want) {
                val page = d.loadPage(pageIndex) as PDFPage
                try {
                    val names = page.annotations?.mapNotNull { nameOf(it) }?.toSet() ?: emptySet()
                    found += list.count { "pt-${it.id}" in names && hasDrawing(page, "pt-${it.id}") }
                } finally { page.destroy() }
            }
            found
        } finally { d.destroy() }
    }.getOrDefault(items.size)      // if the check itself can't run, don't raise a false alarm

    /** True when the mark named [nm] carries its own drawing (/AP), which is what non-MuPDF viewers paint. Unknown -> assume yes. */
    private fun hasDrawing(page: PDFPage, nm: String): Boolean = runCatching {
        val a = page.annotations?.firstOrNull { nameOf(it) == nm } ?: return@runCatching false
        val ap = a.`object`.get("AP")
        ap != null && !ap.isNull
    }.getOrDefault(true)

    suspend fun close() = withContext(io) {
        runCatching { doc.destroy() }
        runCatching { pfd.close() }
    }

    companion object {
        private val ENGINE_DISPATCHER = Executors.newSingleThreadExecutor { Thread(it, "mupdf").apply { priority = Thread.NORM_PRIORITY } }.asCoroutineDispatcher()

        suspend fun open(context: Context, uri: Uri): PdfEngine = withContext(ENGINE_DISPATCHER) {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Can't open file")
            try {
                val ch = FileInputStream(pfd.fileDescriptor).channel
                val d = com.artifex.mupdf.fitz.Document.openDocument(ChannelStream(ch), "application/pdf")
                val pdf = d as? PDFDocument ?: error("Not a PDF")
                if (pdf.needsPassword()) { pdf.destroy(); error("Password-protected PDFs aren't supported yet") }
                val n = pdf.countPages()
                val infos = ArrayList<PageInfo>(n)
                for (i in 0 until n) {
                    val p = pdf.loadPage(i)
                    val b = p.bounds
                    infos += PageInfo(b.x1 - b.x0, b.y1 - b.y0)
                    p.destroy()
                }
                PdfEngine(context.applicationContext, pfd, pdf, infos)
            } catch (t: Throwable) { runCatching { pfd.close() }; throw t }
        }
    }
}
