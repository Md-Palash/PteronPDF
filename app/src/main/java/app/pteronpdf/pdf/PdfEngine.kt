package app.pteronpdf.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.SeekableInputStream
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
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

/** All MuPDF access is funnelled through ONE thread: the library is not thread-safe per document. */
class PdfEngine private constructor(
    private val context: Context,
    private val pfd: ParcelFileDescriptor,
    private val doc: PDFDocument,
    val pages: List<PageInfo>,
) {
    private val io: CoroutineDispatcher = ENGINE_DISPATCHER
    val pageCount get() = pages.size

    // ───────────── rendering ─────────────
    /** Renders [index] so its width is [widthPx]. Annotations are included. Caller keeps a white backdrop. */
    suspend fun render(index: Int, widthPx: Int): Bitmap = withContext(io) {
        val page = doc.loadPage(index)
        try {
            val b = page.bounds
            val s = widthPx / (b.x1 - b.x0)
            AndroidDrawDevice.drawPage(page, Matrix(s))
        } finally { page.destroy() }
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

    // ───────────── markup ─────────────
    suspend fun add(pageIndex: Int, id: Long, m: Markup) = withContext(io) {
        val page = doc.loadPage(pageIndex) as PDFPage
        try { create(page, id, m) } finally { page.destroy() }
    }

    suspend fun removeById(pageIndex: Int, id: Long): Boolean = withContext(io) {
        val page = doc.loadPage(pageIndex) as PDFPage
        try {
            val target = page.annotations?.firstOrNull { nameOf(it) == "pt-$id" } ?: return@withContext false
            page.deleteAnnotation(target); true
        } finally { page.destroy() }
    }

    /** Topmost annotation under [x],[y] (page coords). Returns its session id (or -1 if not ours) and whether anything was removed. */
    suspend fun eraseAt(pageIndex: Int, x: Float, y: Float, tol: Float): Pair<Long, Boolean> = withContext(io) {
        val page = doc.loadPage(pageIndex) as PDFPage
        try {
            val hit = page.annotations.orEmpty()
                .filter { a ->
                    val r = a.bounds
                    x >= r.x0 - tol && x <= r.x1 + tol && y >= r.y0 - tol && y <= r.y1 + tol &&
                        a.type != PDFAnnotation.TYPE_LINK && a.type != PDFAnnotation.TYPE_WIDGET
                }
                .minByOrNull { val r = it.bounds; (r.x1 - r.x0) * (r.y1 - r.y0) }
                ?: return@withContext -1L to false
            val nm = nameOf(hit)
            page.deleteAnnotation(hit)
            (nm?.removePrefix("pt-")?.toLongOrNull() ?: -1L) to true
        } finally { page.destroy() }
    }

    private fun nameOf(a: PDFAnnotation): String? = runCatching {
        val o = a.`object`.get("NM"); if (o != null && o.isString) o.asString() else null
    }.getOrNull()

    private fun rgb(c: Int) = floatArrayOf(
        ((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f,
    )

    private fun create(page: PDFPage, id: Long, m: Markup) {
        val a: PDFAnnotation = when (m) {
            is Markup.Ink -> page.createAnnotation(PDFAnnotation.TYPE_INK).apply {
                setInkList(arrayOf(m.pts.map { Point(it.x, it.y) }.toTypedArray()))
                setColor(rgb(m.color)); setBorderWidth(m.width); setOpacity(m.opacity)
            }
            is Markup.Shape -> when (m.kind) {
                ShapeKind.Line, ShapeKind.Arrow -> page.createAnnotation(PDFAnnotation.TYPE_LINE).apply {
                    setLine(Point(m.p0.x, m.p0.y), Point(m.p1.x, m.p1.y))
                    if (m.kind == ShapeKind.Arrow)
                        setLineEndingStyles(intArrayOf(PDFAnnotation.LINE_ENDING_NONE, PDFAnnotation.LINE_ENDING_OPEN_ARROW))
                    setColor(rgb(m.color)); setBorderWidth(m.width)
                }
                ShapeKind.Rect, ShapeKind.Circle -> page.createAnnotation(
                    if (m.kind == ShapeKind.Rect) PDFAnnotation.TYPE_SQUARE else PDFAnnotation.TYPE_CIRCLE
                ).apply {
                    setRect(Rect(minOf(m.p0.x, m.p1.x), minOf(m.p0.y, m.p1.y), maxOf(m.p0.x, m.p1.x), maxOf(m.p0.y, m.p1.y)))
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
        a.update()
    }

    // ───────────── save ─────────────
    /** Full rewrite to a temp file, then copy to [target]. Temp is always removed. */
    suspend fun saveTo(target: Uri) = withContext(io) {
        val tmp = File.createTempFile("pteron", ".pdf", context.cacheDir)
        try {
            doc.save(tmp.absolutePath, "garbage=compact,compress")
            context.contentResolver.openOutputStream(target, "wt")!!.use { out ->
                FileInputStream(tmp).use { it.copyTo(out, 64 * 1024) }
            }
        } finally { tmp.delete() }
    }

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
